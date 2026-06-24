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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.ConversationRole
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.ConversationTurn
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
      modelId: String = BuildConfig.GEMINI_SESSION_MODEL_ID,
      images: List<Bitmap> = emptyList(),
      onPartialText: (String) -> Unit = {},
      responseMimeType: String? = null,
      responseSchema: JsonObject? = null,
      maxOutputTokens: Int = MAX_OUTPUT_TOKENS,
  ): String =
    withContext(Dispatchers.IO) {
      if (!isConfigured()) {
        throw GeminiException("GEMINI_API_KEY is not configured in local.properties.")
      }

      val startedAtMs = SystemClock.elapsedRealtime()
      val request =
          Request.Builder()
              .url("${geminiEndpoint(modelId)}?alt=sse&key=$apiKey")
              .post(
                  buildRequestBody(
                          prompt = prompt,
                          images = images,
                          responseMimeType = responseMimeType,
                          responseSchema = responseSchema,
                          maxOutputTokens = maxOutputTokens,
                      )
                      .toRequestBody(JSON_MEDIA_TYPE)
              )
              .build()

      Log.d(TAG, "Posting Gemini request: model=$modelId, images=${images.size}")
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
      documentText: String,
      onPartialText: (String) -> Unit = {},
  ): DocumentAnalysisResult {
    Log.i(
        TAG,
        "Document analysis request from grounded text: chars=${documentText.length}",
    )
    val responseJson =
        postToGemini(
            prompt = buildDocumentAnalysisPrompt(documentText),
            onPartialText = onPartialText,
            responseMimeType = "application/json",
            responseSchema = documentAnalysisSchema(),
        )
    val parsed = parseJsonOnly(responseJson)
    Log.i(
        TAG,
        "Document analysis response parsed=${parsed != null}, rawChars=${responseJson.length}, documentType=${parsed?.get("documentType")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()}",
    )
    if (parsed == null) {
      Log.w(TAG, "Document analysis raw response: ${responseJson.take(MAX_LOG_RESPONSE_CHARS)}")
    }
    return DocumentAnalysisResult(rawJson = responseJson, json = parsed, documentText = documentText)
  }

  suspend fun transcribeDocumentImage(
      documentBitmap: Bitmap,
      onPartialText: (String) -> Unit = {},
  ): String {
    Log.i(
        TAG,
        "Document grounding request: bitmap=${documentBitmap.width}x${documentBitmap.height}, model=${BuildConfig.GEMINI_DOCUMENT_GROUNDING_MODEL_ID}",
    )
    return postToGemini(
            prompt = buildDocumentGroundingPrompt(),
            modelId = BuildConfig.GEMINI_DOCUMENT_GROUNDING_MODEL_ID,
            images = listOf(documentBitmap),
            onPartialText = onPartialText,
            maxOutputTokens = DOCUMENT_GROUNDING_MAX_OUTPUT_TOKENS,
        )
        .trim()
        .also { groundedText ->
          Log.i(TAG, "Document grounding completed: chars=${groundedText.length}")
          if (groundedText.isBlank()) {
            throw GeminiException("Gemini could not read text from the scanned document.")
          }
        }
  }

  suspend fun answerDocumentQuestion(
      documentText: String,
      analysis: DocumentAnalysisResult,
      question: String,
      conversation: List<ConversationTurn>,
  ): String {
    Log.i(
        TAG,
        "Document question request: documentTextChars=${documentText.length}, questionChars=${question.length}, turns=${conversation.size}",
    )
    return postToGemini(
            prompt = buildDocumentQuestionPrompt(documentText, analysis, question, conversation),
            maxOutputTokens = DOCUMENT_QA_MAX_OUTPUT_TOKENS,
        )
        .trim()
  }

  private fun buildRequestBody(
      prompt: String,
      images: List<Bitmap>,
      responseMimeType: String?,
      responseSchema: JsonObject?,
      maxOutputTokens: Int,
  ): String {
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
                addProperty("maxOutputTokens", maxOutputTokens)
                addProperty("temperature", RESPONSE_TEMPERATURE)
                addProperty("mediaResolution", MEDIA_RESOLUTION)
                responseMimeType?.let { addProperty("responseMimeType", it) }
                responseSchema?.let { add("responseSchema", it) }
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

  private fun buildDocumentGroundingPrompt(): String {
    return """
      Convert this smart-glasses document photo into faithful Markdown text.

      Rules:
      - Preserve the visible document structure: headings, section names, paragraph order, bullet order, numbered points, labels, dates, amounts, signatures, checkboxes, and table rows/columns.
      - Do not summarize, explain, correct, reorder, or infer missing content.
      - If a point is visible but not numbered, preserve its order as a bullet.
      - If text is uncertain or unreadable, write [unclear] in that location.
      - If a table is visible, render it as a Markdown table.
      - Return only the document text in Markdown. Do not wrap it in code fences.
    """.trimIndent()
  }

  private fun buildDocumentAnalysisPrompt(documentText: String): String {
    return """
      Analyze this smart-glasses scanned document using only the grounded document text below.
      Identify what is shown, summarize it, explain the important details, and suggest immediate next actions.
      Return extractedFields as short "label: value" strings.
      Keep the response concise. Use empty arrays when there are no risk flags or actions.

      Grounded document text:
      ${documentText.take(MAX_GROUNDED_DOCUMENT_CHARS)}
    """.trimIndent()
  }

  private fun buildDocumentQuestionPrompt(
      documentText: String,
      analysis: DocumentAnalysisResult,
      question: String,
      conversation: List<ConversationTurn>,
  ): String =
      buildString {
        appendLine("You are answering questions about one scanned smart-glasses document.")
        appendLine("Use the grounded document text as the primary evidence.")
        appendLine("Use the document analysis only as supporting context.")
        appendLine("Answer directly and crisply. Prefer 1-3 short bullets or 1 short paragraph.")
        appendLine("When the question refers to a section, numbered point, row, or field, locate that exact item in the grounded text before answering.")
        appendLine("Do not invent facts. If the grounded text does not contain enough evidence, say exactly what is missing.")
        appendLine()
        appendLine("Grounded document text:")
        appendLine(documentText.take(MAX_GROUNDED_DOCUMENT_CHARS))
        appendLine()
        appendLine("Initial document analysis:")
        appendLine(analysis.toPromptContext())
        if (conversation.isNotEmpty()) {
          appendLine()
          appendLine("Prior Q&A in this document session:")
          conversation.takeLast(MAX_DOCUMENT_QA_TURNS).forEach { turn ->
            val speaker =
                when (turn.role) {
                  ConversationRole.CUSTOMER -> "Question"
                  ConversationRole.ASSISTANT -> "Answer"
                }
            appendLine("$speaker: ${turn.text}")
          }
        }
        appendLine()
        appendLine("Current question:")
        appendLine(question)
      }

  private fun DocumentAnalysisResult.toPromptContext(): String {
    val parsed = json
    if (parsed == null) return rawJson.take(MAX_DOCUMENT_CONTEXT_CHARS)
    return buildString {
          parsed.stringField("documentType")?.let { appendLine("Document type: $it") }
          parsed.stringField("summary")?.let { appendLine("Summary: $it") }
          parsed.stringField("explanation")?.let { appendLine("Explanation: $it") }
          parsed.get("extractedFields")?.takeIf { !it.isJsonNull }?.let {
            appendLine("Extracted fields: ${it.toCompactPromptValue()}")
          }
          parsed.get("riskFlags")?.takeIf { !it.isJsonNull }?.let {
            appendLine("Risk flags: ${it.toCompactPromptValue()}")
          }
          parsed.get("recommendedActions")?.takeIf { !it.isJsonNull }?.let {
            appendLine("Recommended actions: ${it.toCompactPromptValue()}")
          }
        }
        .ifBlank { rawJson }
        .take(MAX_DOCUMENT_CONTEXT_CHARS)
  }

  private fun JsonObject.stringField(key: String): String? =
      get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

  private fun JsonElement.toCompactPromptValue(): String =
      when {
        isJsonPrimitive -> asString
        isJsonArray -> asJsonArray.joinToString { it.toCompactPromptValue() }
        isJsonObject ->
            asJsonObject.entrySet().joinToString("; ") { (key, value) ->
              "$key: ${value.toCompactPromptValue()}"
            }
        else -> toString()
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
    private const val PLACEHOLDER_API_KEY = "your_actual_key_here"
    private const val JPEG_QUALITY = 65
    private const val MAX_IMAGE_SIDE_PX = 768
    private const val MAX_OUTPUT_TOKENS = 350
    private const val DOCUMENT_GROUNDING_MAX_OUTPUT_TOKENS = 4_096
    private const val DOCUMENT_QA_MAX_OUTPUT_TOKENS = 220
    private const val RESPONSE_TEMPERATURE = 0.2
    private const val MEDIA_RESOLUTION = "MEDIA_RESOLUTION_LOW"
    private const val MAX_LOG_RESPONSE_CHARS = 500
    private const val MAX_DOCUMENT_QA_TURNS = 8
    private const val MAX_DOCUMENT_CONTEXT_CHARS = 2_500
    private const val MAX_GROUNDED_DOCUMENT_CHARS = 12_000
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val DEFAULT_HTTP_CLIENT =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()

    private fun geminiEndpoint(modelId: String): String =
        "https://generativelanguage.googleapis.com/v1beta/models/$modelId:streamGenerateContent"
  }
}

data class DocumentAnalysisResult(
  val rawJson: String,
  val json: JsonObject?,
  val documentText: String,
)

class GeminiException(
  message: String,
  val responseBody: String? = null,
) : IOException(message)
