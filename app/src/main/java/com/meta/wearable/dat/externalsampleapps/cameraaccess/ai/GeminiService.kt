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
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiService(
  @Suppress("UNUSED_PARAMETER") context: Context,
  private val httpClient: OkHttpClient = DEFAULT_HTTP_CLIENT,
  private val gson: Gson = Gson(),
  private val apiKey: String = BuildConfig.GEMINI_API_KEY,
) {
  fun isConfigured(): Boolean = apiKey.isNotBlank() && apiKey != PLACEHOLDER_API_KEY

  suspend fun postToGemini(prompt: String, images: List<Bitmap> = emptyList()): String =
    withContext(Dispatchers.IO) {
      if (!isConfigured()) {
        throw GeminiException("GEMINI_API_KEY is not configured in local.properties.")
      }

      val startedAtMs = SystemClock.elapsedRealtime()
      val request =
          Request.Builder()
              .url("$GEMINI_ENDPOINT?key=$apiKey")
              .post(buildRequestBody(prompt, images).toRequestBody(JSON_MEDIA_TYPE))
              .build()

      Log.d(TAG, "Posting Gemini request with ${images.size} image(s)")
      httpClient.newCall(request).execute().use { response ->
        val responseBody = response.body?.string().orEmpty()
        val durationMs = SystemClock.elapsedRealtime() - startedAtMs
        Log.d(
            TAG,
            "Gemini response received: http=${response.code}, durationMs=$durationMs, bodyChars=${responseBody.length}",
        )
        if (!response.isSuccessful) {
          throw GeminiException("Gemini request failed: HTTP ${response.code}", responseBody)
        }

        parseStreamingText(responseBody).ifBlank {
          throw GeminiException("Gemini returned an empty response.", responseBody)
        }
      }
    }

  suspend fun analyzeDocument(documentBitmap: Bitmap): DocumentAnalysisResult {
    Log.i(
        TAG,
        "Document analysis request: bitmap=${documentBitmap.width}x${documentBitmap.height}",
    )
    val responseJson = postToGemini(buildDocumentAnalysisPrompt(), listOf(documentBitmap))
    val parsed = parseJsonOnly(responseJson)
    Log.i(
        TAG,
        "Document analysis response parsed=${parsed != null}, rawChars=${responseJson.length}, documentType=${parsed?.get("documentType")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()}",
    )
    if (parsed == null) {
      Log.w(TAG, "Document analysis raw response: ${responseJson.take(MAX_LOG_RESPONSE_CHARS)}")
    }
    return DocumentAnalysisResult(rawJson = responseJson, json = parsed)
  }

  private fun buildRequestBody(prompt: String, images: List<Bitmap>): String {
    Log.d(
        TAG,
        "Building Gemini request: promptChars=${prompt.length}, imageSizes=${images.joinToString { "${it.width}x${it.height}" }}",
    )
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

  private fun buildDocumentAnalysisPrompt(): String {
    return """
      You are a document explanation assistant. Analyze the image from the smart glasses stream.
      Perform OCR when text is visible, identify the type of document or object shown, and explain the important details clearly.

      Respond with valid JSON only using this schema:
      {
        "documentType": "string",
        "extractedFields": {},
        "summary": "string",
        "explanation": "string",
        "riskFlags": [],
        "recommendedActions": []
      }
      Keep extractedFields as key-value pairs. Use empty arrays when there are no flags or actions.
    """.trimIndent()
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

  companion object {
    private const val TAG = "CameraAccess:GeminiService"
    private const val MODEL_ID = "gemma-4-26b-a4b-it"
    private const val GEMINI_ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_ID:streamGenerateContent"
    private const val PLACEHOLDER_API_KEY = "your_actual_key_here"
    private const val JPEG_QUALITY = 78
    private const val MAX_IMAGE_SIDE_PX = 1024
    private const val MAX_LOG_RESPONSE_CHARS = 500
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val DEFAULT_HTTP_CLIENT =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()
  }
}

data class DocumentAnalysisResult(
  val rawJson: String,
  val json: JsonObject?,
)

class GeminiException(
  message: String,
  val responseBody: String? = null,
) : IOException(message)
