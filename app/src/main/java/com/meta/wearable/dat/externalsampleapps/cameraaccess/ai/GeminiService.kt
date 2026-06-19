/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.CustomerRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiService(
  private val context: Context,
  private val customerRepository: CustomerRepository = CustomerRepository(context),
  private val httpClient: OkHttpClient = OkHttpClient(),
  private val gson: Gson = Gson(),
  private val apiKey: String = BuildConfig.GEMINI_API_KEY,
) {
  suspend fun postToGemini(prompt: String, images: List<Bitmap> = emptyList()): String =
    withContext(Dispatchers.IO) {
      if (apiKey.isBlank() || apiKey == PLACEHOLDER_API_KEY) {
        throw GeminiException("GEMINI_API_KEY is not configured in local.properties.")
      }

      val request =
          Request.Builder()
              .url("$GEMINI_ENDPOINT?key=$apiKey")
              .post(buildRequestBody(prompt, images).toRequestBody(JSON_MEDIA_TYPE))
              .build()

      Log.d(TAG, "Posting Gemini request with ${images.size} image(s)")
      httpClient.newCall(request).execute().use { response ->
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          throw GeminiException("Gemini request failed: HTTP ${response.code}", responseBody)
        }

        parseStreamingText(responseBody).ifBlank {
          throw GeminiException("Gemini returned an empty response.", responseBody)
        }
      }
    }

  suspend fun detectAndMatchFace(currentFrame: Bitmap): Customer? {
    val customers = customerRepository.loadCustomers()
    if (customers.isEmpty()) return null

    val prompt = buildFaceMatchPrompt(customers)
    val referenceImages = loadCustomerFaceImages(customers)
    val responseJson = postToGemini(prompt, listOf(currentFrame) + referenceImages)
    val match =
        parseJsonOnly(responseJson)?.let { gson.fromJson(it, FaceMatchResponse::class.java) }

    val confidence = match?.confidence ?: 0.0
    val matchedCustomerId = match?.matchedCustomerId.orEmpty()
    if (!match?.isMatch.orFalse() || confidence < MIN_FACE_MATCH_CONFIDENCE) return null

    return customers.firstOrNull { it.id.equals(matchedCustomerId, ignoreCase = true) }
  }

  suspend fun analyzeDocument(
      documentBitmap: Bitmap,
      customer: Customer?,
  ): DocumentAnalysisResult {
    val responseJson = postToGemini(buildDocumentAnalysisPrompt(customer), listOf(documentBitmap))
    val parsed = parseJsonOnly(responseJson)
    return DocumentAnalysisResult(rawJson = responseJson, json = parsed)
  }

  suspend fun processVoiceCommand(transcribedText: String): VoiceCommandResult {
    val normalized = transcribedText.trim().lowercase()
    if (normalized.contains("hey meta") && normalized.contains("scan")) {
      return VoiceCommandResult(intent = VoiceCommandIntent.SCAN_DOCUMENT, confidence = 1.0)
    }

    val responseJson =
        postToGemini(
            """
            You are an intent classifier for a bank relationship manager assistant.
            Respond with valid JSON only using this schema:
            {"intent":"SCAN_DOCUMENT|NONE","confidence":0.0,"reason":"short reason"}

            Classify whether this text asks the assistant to scan a document:
            "$transcribedText"
            """.trimIndent()
        )
    val parsed = parseJsonOnly(responseJson)
    val response = parsed?.let { gson.fromJson(it, VoiceIntentResponse::class.java) }
    val intent =
        if (response?.intent.equals("SCAN_DOCUMENT", ignoreCase = true)) {
          VoiceCommandIntent.SCAN_DOCUMENT
        } else {
          VoiceCommandIntent.NONE
        }
    return VoiceCommandResult(
        intent = intent,
        confidence = response?.confidence ?: 0.0,
        reason = response?.reason.orEmpty(),
        rawJson = responseJson,
    )
  }

  private fun buildRequestBody(prompt: String, images: List<Bitmap>): String {
    val parts =
        JsonArray().apply {
          add(JsonObject().apply { addProperty("text", prompt) })
          images.forEach { bitmap ->
            add(
                JsonObject().apply {
                  add(
                      "inlineData",
                      JsonObject().apply {
                        addProperty("mimeType", "image/jpeg")
                        addProperty("data", bitmap.toJpegBase64())
                      },
                  )
                }
            )
          }
        }
    val content =
        JsonObject().apply {
          addProperty("role", "user")
          add("parts", parts)
        }
    return gson.toJson(
        JsonObject().apply {
          add("contents", JsonArray().apply { add(content) })
          add(
              "generationConfig",
              JsonObject().apply {
                add(
                    "thinkingConfig",
                    JsonObject().apply { addProperty("thinkingLevel", "HIGH") },
                )
              },
          )
        }
    )
  }

  private fun parseStreamingText(responseBody: String): String {
    val trimmed = responseBody.trim()
    if (trimmed.isEmpty()) return ""

    val chunks =
        when {
          trimmed.startsWith("[") -> JsonParser.parseString(trimmed).asJsonArray.toList()
          trimmed.startsWith("{") -> listOf(JsonParser.parseString(trimmed))
          else ->
              trimmed
                  .lineSequence()
                  .map { it.removePrefix("data:").trim() }
                  .filter { it.isNotEmpty() && it != "[DONE]" }
                  .mapNotNull { runCatching { JsonParser.parseString(it) }.getOrNull() }
                  .toList()
        }

    return buildString {
      chunks.forEach chunkLoop@{ chunk ->
        val candidates = chunk.asJsonObject.getAsJsonArray("candidates") ?: return@chunkLoop
        candidates.forEach candidateLoop@{ candidate ->
          val parts =
              candidate
                  .asJsonObject
                  .getAsJsonObject("content")
                  ?.getAsJsonArray("parts")
                  ?: return@candidateLoop
          parts.forEach { part ->
            part.asJsonObject.get("text")?.asString?.let(::append)
          }
        }
      }
    }.trim()
  }

  private fun buildFaceMatchPrompt(customers: List<Customer>): String {
    val customerSummaries =
        customers.joinToString(separator = "\n") { customer ->
          "- ${customer.id}: ${customer.name}, phone ${customer.phone}, profile: ${customer.profile}, faceImage: ${customer.faceImage}"
        }
    return """
      You are matching a live camera frame to a small bank customer reference list.
      Image 1 is the current camera frame. The remaining images are reference face images in the same order as the customer list below when available.

      Customers:
      $customerSummaries

      Respond with valid JSON only using this schema:
      {
        "isMatch": true,
        "matchedCustomerId": "CUST001",
        "confidence": 0.0,
        "reason": "short visual matching reason"
      }
      Use "isMatch": false and an empty matchedCustomerId when confidence is low.
    """.trimIndent()
  }

  private fun buildDocumentAnalysisPrompt(customer: Customer?): String {
    val customerContext =
        customer?.let {
          """
          Current customer:
          - id: ${it.id}
          - name: ${it.name}
          - phone: ${it.phone}
          - profile: ${it.profile}
          - lastVisit: ${it.lastVisit}
          """.trimIndent()
        } ?: "No customer has been matched yet."

    return """
      You are a bank relationship manager assistant. Perform OCR on the document image and analyze it with banking context.

      $customerContext

      Respond with valid JSON only using this schema:
      {
        "documentType": "string",
        "extractedFields": {},
        "summary": "string",
        "customerRelevance": "string",
        "riskFlags": [],
        "recommendedActions": []
      }
      Keep extractedFields as key-value pairs. Use empty arrays when there are no flags or actions.
    """.trimIndent()
  }

  private fun loadCustomerFaceImages(customers: List<Customer>): List<Bitmap> =
      customers.mapNotNull { customer ->
        runCatching {
              context.assets
                  .open(customerRepository.faceAssetPath(customer))
                  .use(BitmapFactory::decodeStream)
            }
            .onFailure {
              Log.w(TAG, "Missing face reference for ${customer.id}: ${customer.faceImage}")
            }
            .getOrNull()
      }

  private fun parseJsonOnly(text: String): JsonObject? {
    val trimmed = text.trim().removeSurrounding("```json", "```").removeSurrounding("```")
    return runCatching { JsonParser.parseString(trimmed).asJsonObject }
        .recoverCatching {
          val start = trimmed.indexOf('{')
          val end = trimmed.lastIndexOf('}')
          if (start >= 0 && end > start) {
            JsonParser.parseString(trimmed.substring(start, end + 1)).asJsonObject
          } else {
            throw it
          }
        }
        .getOrNull()
  }

  private fun Bitmap.toJpegBase64(): String {
    val resized = resizeForGemini()
    return ByteArrayOutputStream().use { outputStream ->
      resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
      Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
  }

  private fun Bitmap.resizeForGemini(): Bitmap {
    val longestSide = maxOf(width, height)
    if (longestSide <= MAX_IMAGE_SIDE_PX) return this

    val scale = MAX_IMAGE_SIDE_PX.toFloat() / longestSide.toFloat()
    val resizedWidth = (width * scale).toInt().coerceAtLeast(1)
    val resizedHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, resizedWidth, resizedHeight, true)
  }

  private fun Boolean?.orFalse(): Boolean = this == true

  companion object {
    private const val TAG = "CameraAccess:GeminiService"
    private const val MODEL_ID = "gemma-4-26b-a4b-it"
    private const val GEMINI_ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_ID:streamGenerateContent"
    private const val PLACEHOLDER_API_KEY = "your_actual_key_here"
    private const val JPEG_QUALITY = 85
    private const val MAX_IMAGE_SIDE_PX = 1536
    private const val MIN_FACE_MATCH_CONFIDENCE = 0.65
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }
}

data class DocumentAnalysisResult(
  val rawJson: String,
  val json: JsonObject?,
)

data class VoiceCommandResult(
  val intent: VoiceCommandIntent,
  val confidence: Double,
  val reason: String = "",
  val rawJson: String = "",
)

enum class VoiceCommandIntent {
  SCAN_DOCUMENT,
  NONE,
}

class GeminiException(
  message: String,
  val responseBody: String? = null,
) : IOException(message)

private data class FaceMatchResponse(
  val isMatch: Boolean = false,
  val matchedCustomerId: String = "",
  val confidence: Double = 0.0,
  val reason: String = "",
)

private data class VoiceIntentResponse(
  val intent: String = "NONE",
  val confidence: Double = 0.0,
  val reason: String = "",
)
