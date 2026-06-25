/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GemmaAssistantService(
  @Suppress("UNUSED_PARAMETER") context: Context,
  private val httpClient: OkHttpClient = DEFAULT_HTTP_CLIENT,
  private val gson: Gson = Gson(),
  private val apiKey: String = BuildConfig.GEMINI_API_KEY,
  private val modelId: String = DEFAULT_GEMMA_MODEL_ID
) {
  fun isConfigured(): Boolean = apiKey.isNotBlank()

  suspend fun answerCustomerQuestion(
      customer: Customer,
      question: String,
      mode: AssistantMode,
      conversation: List<ConversationTurn>,
      onPartialText: (String) -> Unit = {},
  ): String =
      withContext(Dispatchers.IO) {
        if (!isConfigured()) {
          throw GemmaAssistantException("GEMINI_API_KEY is not configured in local.properties.")
        }

        val request =
            Request.Builder()
                .url("$GEMINI_ENDPOINT_BASE/$modelId:streamGenerateContent?alt=sse&key=$apiKey")
                .post(buildRequestBody(customer, question, mode, conversation).toRequestBody(JSON_MEDIA_TYPE))
                .build()

        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            val responseBody = response.body?.string().orEmpty()
            throw GemmaAssistantException(
                "Gemma request failed: HTTP ${response.code}. ${responseBody.toGemmaErrorSummary()}",
                responseBody,
            )
          }
          streamTextFromResponse(response, onPartialText).ifBlank {
            throw GemmaAssistantException("Gemma returned an empty response.")
          }
        }
      }

  private fun buildRequestBody(
      customer: Customer,
      question: String,
      mode: AssistantMode,
      conversation: List<ConversationTurn>,
  ): String {
    val prompt = buildPrompt(customer, question, mode, conversation)
    val content =
        JsonObject().apply {
          addProperty("role", "user")
          add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", prompt) }) })
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
                addProperty("topP", 0.9)
              },
          )
        }
    )
  }

  private fun buildPrompt(
      customer: Customer,
      question: String,
      mode: AssistantMode,
      conversation: List<ConversationTurn>,
  ): String =
      buildString {
        appendLine("You are a real-time relationship-manager assistant for a bank branch.")
        appendLine("Answer the RM, not the customer. Be accurate, concise, and action-oriented.")
        appendLine("Use only the customer data provided. If data is missing, say what to verify.")
        appendLine("Keep the answer under 5 short bullet points unless the RM asks for detail.")
        appendLine()
        appendLine("Customer:")
        appendLine("- ID: ${customer.id}")
        appendLine("- Name: ${customer.name}")
        appendLine("- Phone: ${customer.phone}")
        appendLine("- Profile: ${customer.profile}")
        appendLine("- Last visit: ${customer.lastVisit}")
        appendLine("- Accounts: ${customer.accounts.joinToString("; ") { "${it.type} ${it.accountNumber} ${it.balance} ${it.status}" }}")
        appendLine("- Recent transactions: ${customer.transactions.takeLast(6).joinToString("; ") { "${it.date} ${it.description} ${it.amount} ${it.direction} ${it.status}" }}")
        appendLine("- Relationship history: ${customer.history.takeLast(4).joinToString("; ") { "${it.date} ${it.type}: ${it.notes}" }}")
        if (mode == AssistantMode.PHASE_5B && conversation.isNotEmpty()) {
          appendLine()
          appendLine("Current conversation history:")
          conversation.takeLast(10).forEach { turn ->
            appendLine("${turn.role.name}: ${turn.text}")
          }
        }
        appendLine()
        appendLine("Customer's current spoken query:")
        appendLine(question)
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

  private fun String.toGemmaErrorSummary(): String {
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

  private companion object {
    const val GEMINI_ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    const val DEFAULT_GEMMA_MODEL_ID = "gemini-3.1-flash-lite"
    const val MAX_OUTPUT_TOKENS = 320
    const val RESPONSE_TEMPERATURE = 0.2
    const val MAX_LOG_RESPONSE_CHARS = 500
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    val DEFAULT_HTTP_CLIENT =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
  }
}

class GemmaAssistantException(
  message: String,
  val responseBody: String? = null,
) : IOException(message)
