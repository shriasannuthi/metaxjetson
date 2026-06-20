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
import com.google.gson.JsonElement
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

  suspend fun postToGemini(
      prompt: String,
      images: List<Bitmap> = emptyList(),
      onPartialText: (String) -> Unit = {},
  ): String =
    withContext(Dispatchers.IO) {
      if (!isConfigured()) {
        throw GeminiException("GEMINI_API_KEY is not configured in local.properties.")
      }

      val startedAtMs = SystemClock.elapsedRealtime()
      val request =
          Request.Builder()
              .url("$GEMINI_ENDPOINT?alt=sse&key=$apiKey")
              .post(buildRequestBody(prompt, images).toRequestBody(JSON_MEDIA_TYPE))
              .build()

      Log.d(TAG, "Posting Gemini request with ${images.size} image(s)")
      httpClient.newCall(request).execute().use { response ->
        val durationMs = SystemClock.elapsedRealtime() - startedAtMs
        Log.d(
            TAG,
            "Gemini response started: http=${response.code}, durationMs=$durationMs",
        )
        if (!response.isSuccessful) {
          val responseBody = response.body?.string().orEmpty()
          Log.w(TAG, "Gemini request failed: HTTP ${response.code}, body=${responseBody.take(MAX_LOG_RESPONSE_CHARS)}")
          throw GeminiException(
              "Gemini request failed: HTTP ${response.code}. ${responseBody.toGeminiErrorSummary()}",
              responseBody,
          )
        }

        streamTextFromResponse(response, onPartialText).ifBlank {
          throw GeminiException("Gemini returned an empty response.")
        }
      }
    }

  suspend fun analyzeDocument(
      documentBitmap: Bitmap,
      onPartialText: (String) -> Unit = {},
  ): DocumentAnalysisResult {
    Log.i(
        TAG,
        "Document analysis request: bitmap=${documentBitmap.width}x${documentBitmap.height}",
    )
    val responseJson =
        postToGemini(
            buildDocumentAnalysisPrompt(),
            listOf(documentBitmap),
            onPartialText = onPartialText,
        )
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
                addProperty("candidateCount", 1)
                addProperty("maxOutputTokens", MAX_OUTPUT_TOKENS)
                addProperty("temperature", RESPONSE_TEMPERATURE)
                addProperty("mediaResolution", MEDIA_RESOLUTION)
                addProperty("responseMimeType", "application/json")
                add("responseSchema", documentAnalysisSchema())
              },
          )
        }
    )
  }

  private fun streamTextFromResponse(
      response: okhttp3.Response,
      onPartialText: (String) -> Unit,
  ): String {
    val responseBody = response.body ?: return ""
    val source = responseBody.source()
    val accumulated = StringBuilder()
    while (!source.exhausted()) {
      val line = source.readUtf8Line() ?: continue
      val trimmedLine = line.removePrefix("data:").trim()
      if (trimmedLine.isEmpty() || trimmedLine == "[DONE]") continue

      val chunk = runCatching { JsonParser.parseString(trimmedLine) }.getOrNull() ?: continue
      val text = chunk.extractText()
      if (text.isNotBlank()) {
        accumulated.append(text)
        onPartialText(accumulated.toString())
      }
    }
    return accumulated.toString().trim()
  }

  private fun JsonElement.extractText(): String =
      buildString {
        val candidates = asJsonObject.getAsJsonArray("candidates") ?: return@buildString
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

  private fun buildDocumentAnalysisPrompt(): String {
    return """
      Analyze this smart-glasses snapshot quickly.
      If text is visible, read the key text. Identify what is shown, summarize it, explain the important details, and suggest immediate next actions.
      Return extractedFields as short "label: value" strings.
      Keep the response concise. Use empty arrays when there are no risk flags or actions.
    """.trimIndent()
  }

  private fun documentAnalysisSchema(): JsonObject =
      JsonObject().apply {
        addProperty("type", "OBJECT")
        add(
            "properties",
            JsonObject().apply {
              add("documentType", JsonObject().apply { addProperty("type", "STRING") })
              add(
                  "extractedFields",
                  JsonObject().apply {
                    addProperty("type", "ARRAY")
                    add("items", JsonObject().apply { addProperty("type", "STRING") })
                  },
              )
              add("summary", JsonObject().apply { addProperty("type", "STRING") })
              add("explanation", JsonObject().apply { addProperty("type", "STRING") })
              add(
                  "riskFlags",
                  JsonObject().apply {
                    addProperty("type", "ARRAY")
                    add("items", JsonObject().apply { addProperty("type", "STRING") })
                  },
              )
              add(
                  "recommendedActions",
                  JsonObject().apply {
                    addProperty("type", "ARRAY")
                    add("items", JsonObject().apply { addProperty("type", "STRING") })
                  },
              )
            },
        )
        add(
            "required",
            JsonArray().apply {
              add("documentType")
              add("extractedFields")
              add("summary")
              add("explanation")
              add("riskFlags")
              add("recommendedActions")
            },
        )
      }

  private fun String.toGeminiErrorSummary(): String {
    val parsedMessage =
        runCatching {
              JsonParser.parseString(this)
                  .asJsonObject
                  .getAsJsonObject("error")
                  ?.get("message")
                  ?.asString
            }
            .getOrNull()
    return parsedMessage ?: take(MAX_LOG_RESPONSE_CHARS)
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
    private const val MODEL_ID = "gemini-3.1-flash-lite"
    private const val GEMINI_ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_ID:streamGenerateContent"
    private const val PLACEHOLDER_API_KEY = "your_actual_key_here"
    private const val JPEG_QUALITY = 65
    private const val MAX_IMAGE_SIDE_PX = 768
    private const val MAX_OUTPUT_TOKENS = 350
    private const val RESPONSE_TEMPERATURE = 0.2
    private const val MEDIA_RESOLUTION = "MEDIA_RESOLUTION_LOW"
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
