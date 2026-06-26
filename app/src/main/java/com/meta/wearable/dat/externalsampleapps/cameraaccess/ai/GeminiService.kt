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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class GeminiService(
  @Suppress("UNUSED_PARAMETER") context: Context,
  private val httpClient: OkHttpClient = DEFAULT_HTTP_CLIENT,
  private val gson: Gson = Gson(),
  private val apiKey: String = BuildConfig.GEMINI_API_KEY,
  private val groqApiKey: String = BuildConfig.GROQ_API_KEY,
  private val xaiApiKey: String = BuildConfig.XAI_API_KEY,
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
        "Document grounding request: bitmap=${documentBitmap.width}x${documentBitmap.height}",
    )
    return groundDocumentImage(documentBitmap)
        .also { groundedText ->
          onPartialText(groundedText)
          Log.i(TAG, "Document grounding completed: chars=${groundedText.length}")
          if (groundedText.isBlank()) {
            throw GeminiException("Could not read text from the scanned document.")
          }
        }
  }

  suspend fun groundDocumentImage(documentBitmap: Bitmap): String =
      withContext(Dispatchers.IO) {
        val providers = configuredGroundingProviders()
        if (providers.isEmpty()) {
          throw GeminiException("No document grounding provider is configured.")
        }

        val providerNames = providers.joinToString { "${it.name}/${it.modelId}" }
        Log.i(TAG, "Document grounding race started: providers=[$providerNames]")

        try {
          withTimeout(DOCUMENT_GROUNDING_TIMEOUT_MS) {
            supervisorScope {
              val channel = Channel<GroundingProviderResult>(capacity = providers.size)
              val jobs = providers.map { provider ->
                launch {
                  val result =
                      try {
                        provider.ground(documentBitmap)
                      } catch (e: CancellationException) {
                        throw e
                      } catch (e: Exception) {
                        GroundingProviderResult.Failure(
                            providerName = provider.name,
                            modelId = provider.modelId,
                            latencyMs = 0L,
                            reason = e.message ?: e.javaClass.simpleName,
                        )
                      }
                  channel.send(result)
                }
              }

              val failures = mutableListOf<GroundingProviderResult.Failure>()
              repeat(providers.size) {
                when (val result = channel.receive()) {
                  is GroundingProviderResult.Success -> {
                    jobs.forEach { it.cancel() }
                    channel.close()
                    Log.i(
                        TAG,
                        "Document grounding winner: provider=${result.providerName}, model=${result.modelId}, latencyMs=${result.latencyMs}, chars=${result.text.length}",
                    )
                    if (BuildConfig.DEBUG) {
                      Log.d(
                          TAG,
                          "Grounded document preview: ${result.text.take(DEBUG_GROUNDED_TEXT_CHARS)}",
                      )
                    }
                    return@supervisorScope result.text
                  }
                  is GroundingProviderResult.Failure -> {
                    failures += result
                    Log.w(
                        TAG,
                        "Document grounding provider failed: provider=${result.providerName}, model=${result.modelId}, latencyMs=${result.latencyMs}, reason=${result.reason}",
                    )
                  }
                }
              }
              throw GeminiException(buildGroundingFailureMessage(failures))
            }
          }
        } catch (e: TimeoutCancellationException) {
          Log.w(TAG, "Document grounding race timed out after ${DOCUMENT_GROUNDING_TIMEOUT_MS}ms")
          throw GeminiException("Could not read document: all document readers timed out after 35s.")
        }
      }

  suspend fun answerDocumentQuestion(
      documentText: String,
      question: String,
      conversation: List<ConversationTurn>,
  ): String {
    Log.i(
        TAG,
        "Document question request: documentTextChars=${documentText.length}, questionChars=${question.length}, turns=${conversation.size}",
    )
    return postToGemini(
            prompt = buildDocumentQuestionPrompt(documentText, question, conversation),
            maxOutputTokens = DOCUMENT_QA_MAX_OUTPUT_TOKENS,
        )
        .trim()
  }

  private fun configuredGroundingProviders(): List<DocumentGroundingProvider> =
      buildList {
        if (isConfigured()) {
          add(
              GeminiGroundingProvider(
                  modelId = BuildConfig.GEMINI_DOCUMENT_GROUNDING_MODEL_ID,
                  apiKey = apiKey,
              )
          )
        }
        if (groqApiKey.isConfiguredKey()) {
          add(
              OpenAiCompatibleGroundingProvider(
                  name = "Groq",
                  endpoint = GROQ_CHAT_COMPLETIONS_ENDPOINT,
                  modelId = BuildConfig.GROQ_DOCUMENT_GROUNDING_MODEL_ID,
                  apiKey = groqApiKey,
              )
          )
        }
        if (xaiApiKey.isConfiguredKey()) {
          add(
              XaiResponsesGroundingProvider(
                  name = "xAI",
                  modelId = BuildConfig.XAI_DOCUMENT_GROUNDING_MODEL_ID,
                  apiKey = xaiApiKey,
              )
          )
        }
      }

  private interface DocumentGroundingProvider {
    val name: String
    val modelId: String

    suspend fun ground(documentBitmap: Bitmap): GroundingProviderResult
  }

  private inner class GeminiGroundingProvider(
      override val modelId: String,
      private val apiKey: String,
  ) : DocumentGroundingProvider {
    override val name: String = "Gemini"

    override suspend fun ground(documentBitmap: Bitmap): GroundingProviderResult {
      val startedAtMs = SystemClock.elapsedRealtime()
      Log.i(TAG, "Document grounding provider request started: provider=$name, model=$modelId")
      val request =
          Request.Builder()
              .url("${geminiGenerateEndpoint(modelId)}?key=$apiKey")
              .post(
                  buildRequestBody(
                          prompt = buildDocumentGroundingPrompt(),
                          images = listOf(documentBitmap),
                          responseMimeType = null,
                          responseSchema = null,
                          maxOutputTokens = DOCUMENT_GROUNDING_MAX_OUTPUT_TOKENS,
                      )
                      .toRequestBody(JSON_MEDIA_TYPE)
              )
              .build()
      return executeGroundingRequest(
          providerName = name,
          modelId = modelId,
          startedAtMs = startedAtMs,
          request = request,
          parseText = { body ->
            JsonParser.parseString(body).extractText()
          },
      )
    }
  }

  private inner class OpenAiCompatibleGroundingProvider(
      override val name: String,
      private val endpoint: String,
      override val modelId: String,
      private val apiKey: String,
  ) : DocumentGroundingProvider {
    override suspend fun ground(documentBitmap: Bitmap): GroundingProviderResult {
      val startedAtMs = SystemClock.elapsedRealtime()
      Log.i(TAG, "Document grounding provider request started: provider=$name, model=$modelId")
      val request =
          Request.Builder()
              .url(endpoint)
              .addHeader("Authorization", "Bearer $apiKey")
              .post(buildOpenAiCompatibleGroundingBody(documentBitmap).toRequestBody(JSON_MEDIA_TYPE))
              .build()
      return executeGroundingRequest(
          providerName = name,
          modelId = modelId,
          startedAtMs = startedAtMs,
          request = request,
          parseText = { body ->
            JsonParser.parseString(body)
                .asJsonObject
                .getAsJsonArray("choices")
                ?.firstOrNull()
                ?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")
                ?.asString
                .orEmpty()
          },
      )
    }

    private fun buildOpenAiCompatibleGroundingBody(documentBitmap: Bitmap): String =
        gson.toJson(
            JsonObject().apply {
              addProperty("model", modelId)
              addProperty("temperature", RESPONSE_TEMPERATURE)
              addProperty("max_completion_tokens", DOCUMENT_GROUNDING_MAX_OUTPUT_TOKENS)
              add(
                  "messages",
                  JsonArray().apply {
                    add(
                        JsonObject().apply {
                          addProperty("role", "user")
                          add(
                              "content",
                              JsonArray().apply {
                                add(
                                    JsonObject().apply {
                                      addProperty("type", "text")
                                      addProperty("text", buildDocumentGroundingPrompt())
                                    }
                                )
                                add(
                                    JsonObject().apply {
                                      addProperty("type", "image_url")
                                      add(
                                          "image_url",
                                          JsonObject().apply {
                                            addProperty(
                                                "url",
                                                "data:image/jpeg;base64,${documentBitmap.toJpegBase64()}",
                                            )
                                          },
                                      )
                                    }
                                )
                              },
                          )
                        }
                    )
                  },
              )
            }
        )
  }

  private inner class XaiResponsesGroundingProvider(
      override val name: String,
      override val modelId: String,
      private val apiKey: String,
  ) : DocumentGroundingProvider {
    override suspend fun ground(documentBitmap: Bitmap): GroundingProviderResult {
      val startedAtMs = SystemClock.elapsedRealtime()
      Log.i(TAG, "Document grounding provider request started: provider=$name, model=$modelId")
      val request =
          Request.Builder()
              .url(XAI_RESPONSES_ENDPOINT)
              .addHeader("Authorization", "Bearer $apiKey")
              .post(buildXaiResponsesGroundingBody(documentBitmap).toRequestBody(JSON_MEDIA_TYPE))
              .build()
      return executeGroundingRequest(
          providerName = name,
          modelId = modelId,
          startedAtMs = startedAtMs,
          request = request,
          parseText = { body ->
            JsonParser.parseString(body).extractResponsesText()
          },
      )
    }

    private fun buildXaiResponsesGroundingBody(documentBitmap: Bitmap): String =
        gson.toJson(
            JsonObject().apply {
              addProperty("model", modelId)
              addProperty("temperature", RESPONSE_TEMPERATURE)
              addProperty("max_output_tokens", DOCUMENT_GROUNDING_MAX_OUTPUT_TOKENS)
              addProperty("store", false)
              add(
                  "input",
                  JsonArray().apply {
                    add(
                        JsonObject().apply {
                          addProperty("role", "user")
                          add(
                              "content",
                              JsonArray().apply {
                                add(
                                    JsonObject().apply {
                                      addProperty("type", "input_image")
                                      addProperty(
                                          "image_url",
                                          "data:image/jpeg;base64,${documentBitmap.toJpegBase64()}",
                                      )
                                      addProperty("detail", "high")
                                    }
                                )
                                add(
                                    JsonObject().apply {
                                      addProperty("type", "input_text")
                                      addProperty("text", buildDocumentGroundingPrompt())
                                    }
                                )
                              },
                          )
                        }
                    )
                  },
              )
            }
        )
  }

  private suspend fun executeGroundingRequest(
      providerName: String,
      modelId: String,
      startedAtMs: Long,
      request: Request,
      parseText: (String) -> String,
  ): GroundingProviderResult {
    val httpResult = executeRequest(request)
    val latencyMs = SystemClock.elapsedRealtime() - startedAtMs
    Log.i(
        TAG,
        "Document grounding provider response: provider=$providerName, model=$modelId, http=${httpResult.code}, latencyMs=$latencyMs",
    )
    if (!httpResult.isSuccessful) {
      val reason = "HTTP ${httpResult.code}. ${httpResult.body.toProviderErrorSummary()}"
      Log.w(TAG, "$providerName grounding failed: $reason")
      return GroundingProviderResult.Failure(providerName, modelId, latencyMs, reason)
    }

    val text =
        runCatching { parseText(httpResult.body).trim() }
            .getOrElse { error ->
              return GroundingProviderResult.Failure(
                  providerName,
                  modelId,
                  latencyMs,
                  "Parse error: ${error.message ?: error.javaClass.simpleName}",
              )
            }
    if (text.isBlank()) {
      return GroundingProviderResult.Failure(providerName, modelId, latencyMs, "Empty output")
    }
    return GroundingProviderResult.Success(providerName, modelId, latencyMs, text)
  }

  private suspend fun executeRequest(request: Request): HttpTextResult =
      suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
              override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resume(HttpTextResult(code = 0, body = e.message.orEmpty(), isSuccessful = false))
              }

              override fun onResponse(call: Call, response: Response) {
                response.use {
                  val body = it.body?.string().orEmpty()
                  if (!continuation.isCancelled) {
                    continuation.resume(
                        HttpTextResult(
                            code = it.code,
                            body = body,
                            isSuccessful = it.isSuccessful,
                        )
                    )
                  }
                }
              }
            }
        )
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

  private fun JsonElement.extractResponsesText(): String =
      buildString {
        fun visit(element: JsonElement?) {
          if (element == null || element.isJsonNull) return
          when {
            element.isJsonArray -> element.asJsonArray.forEach(::visit)
            element.isJsonObject -> {
              val json = element.asJsonObject
              json.get("output_text")?.takeIf { it.isJsonPrimitive }?.asString?.let(::appendLine)
              val type = json.get("type")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
              if (type == "output_text") {
                json.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.let(::appendLine)
              }
              json.get("output")?.let(::visit)
              json.get("content")?.let(::visit)
            }
          }
        }
        val root = asJsonObject
        root.get("output_text")?.takeIf { it.isJsonPrimitive }?.asString?.let(::appendLine)
        root.get("output")?.let(::visit)
      }.trim()

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
      question: String,
      conversation: List<ConversationTurn>,
  ): String =
      buildString {
        appendLine("You are answering questions about one scanned smart-glasses document.")
        appendLine("Use only the grounded document text and prior Q&A turns as evidence.")
        appendLine("Answer directly and crisply. Prefer 1-3 short bullets or 1 short paragraph.")
        appendLine("When the question refers to a section, numbered point, row, or field, locate that exact item in the grounded text before answering.")
        appendLine("Do not invent facts. If the grounded text does not contain enough evidence, say exactly what is missing.")
        appendLine()
        appendLine("Grounded document text:")
        appendLine(documentText.take(MAX_GROUNDED_DOCUMENT_CHARS))
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

  private fun String.toProviderErrorSummary(): String {
    val parsedMessage =
        runCatching {
              val parsed = JsonParser.parseString(this).asJsonObject
              parsed.getAsJsonObject("error")?.get("message")?.asString
                  ?: parsed.get("error")?.asString
                  ?: parsed.get("message")?.asString
            }
            .getOrNull()
    return (parsedMessage ?: take(MAX_LOG_RESPONSE_CHARS)).ifBlank { "No response body" }
  }

  private fun String.isConfiguredKey(): Boolean = isNotBlank() && this != PLACEHOLDER_API_KEY

  private fun buildGroundingFailureMessage(
      failures: List<GroundingProviderResult.Failure>
  ): String {
    if (failures.isEmpty()) {
      return "Could not read document: all document readers timed out."
    }
    val summary =
        failures.joinToString("; ") { failure ->
          "${failure.providerName}: ${failure.reason}"
        }
    val hasOnlyGemini = failures.size == 1 && failures.first().providerName == "Gemini"
    return if (hasOnlyGemini && summary.contains("503")) {
      "Gemini is overloaded. No alternate document reader is configured."
    } else {
      "Could not read document. $summary"
    }
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

  private sealed interface GroundingProviderResult {
    val providerName: String
    val modelId: String
    val latencyMs: Long

    data class Success(
        override val providerName: String,
        override val modelId: String,
        override val latencyMs: Long,
        val text: String,
    ) : GroundingProviderResult

    data class Failure(
        override val providerName: String,
        override val modelId: String,
        override val latencyMs: Long,
        val reason: String,
    ) : GroundingProviderResult
  }

  private data class HttpTextResult(
      val code: Int,
      val body: String,
      val isSuccessful: Boolean,
  )

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
    private const val DOCUMENT_GROUNDING_TIMEOUT_MS = 35_000L
    private const val DEBUG_GROUNDED_TEXT_CHARS = 300
    private const val GROQ_CHAT_COMPLETIONS_ENDPOINT =
        "https://api.groq.com/openai/v1/chat/completions"
    private const val XAI_RESPONSES_ENDPOINT = "https://api.x.ai/v1/responses"
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

    private fun geminiGenerateEndpoint(modelId: String): String =
        "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent"
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
