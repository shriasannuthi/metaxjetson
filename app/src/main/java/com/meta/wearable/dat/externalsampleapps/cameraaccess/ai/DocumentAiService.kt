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
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.ConversationRole
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.ConversationTurn
import java.io.IOException

class DocumentAiService(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val localAiClient: LocalAiClient = LocalAiClient(),
) {
  fun isConfigured(): Boolean = localAiClient.isConfigured()

  suspend fun analyzeDocument(
      documentText: String,
      onPartialText: (String) -> Unit = {},
  ): DocumentAnalysisResult {
    val responseJson =
        request {
          localAiClient.chat(
              prompt = buildDocumentAnalysisPrompt(documentText),
              responseMode = LocalAiResponseMode.DOCUMENT_ANALYSIS,
              maxTokens = DOCUMENT_ANALYSIS_MAX_OUTPUT_TOKENS,
          )
        }
    onPartialText(responseJson)
    val parsed = parseJsonOnly(responseJson)
    if (parsed == null) {
      Log.w(TAG, "Local document analysis did not return valid JSON")
    }
    return DocumentAnalysisResult(rawJson = responseJson, json = parsed, documentText = documentText)
  }

  suspend fun transcribeDocumentImage(
      documentBitmap: Bitmap,
      onPartialText: (String) -> Unit = {},
  ): String {
    val groundedText = request { localAiClient.ground(documentBitmap) }
    if (groundedText.isBlank()) {
      throw DocumentAiException("Local OCR could not read text from the scanned document")
    }
    onPartialText(groundedText)
    return groundedText
  }

  suspend fun answerDocumentQuestion(
      documentText: String,
      question: String,
      conversation: List<ConversationTurn>,
  ): String =
      request {
            localAiClient.chat(
                prompt = buildDocumentQuestionPrompt(documentText, question, conversation),
                responseMode = LocalAiResponseMode.TEXT,
                maxTokens = DOCUMENT_QA_MAX_OUTPUT_TOKENS,
            )
          }
          .trim()

  private suspend fun request(block: suspend () -> String): String {
    if (!isConfigured()) {
      throw DocumentAiException("Local AI server is not configured in local.properties")
    }
    return try {
      block()
    } catch (e: LocalAiException) {
      throw DocumentAiException(e.message ?: "Local AI request failed", e.responseBody, e)
    }
  }

  private fun buildDocumentAnalysisPrompt(documentText: String): String =
      """
      Analyze this smart-glasses scanned document using only the grounded document text below.
      Identify what is shown, summarize it, explain the important details, and suggest immediate next actions.
      Return extractedFields as short "label: value" strings.
      Keep the response concise. Use empty arrays when there are no risk flags or actions.

      Grounded document text:
      ${documentText.take(MAX_GROUNDED_DOCUMENT_CHARS)}
      """.trimIndent()

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

  private companion object {
    const val TAG = "CameraAccess:DocumentAi"
    const val DOCUMENT_ANALYSIS_MAX_OUTPUT_TOKENS = 350
    const val DOCUMENT_QA_MAX_OUTPUT_TOKENS = 220
    const val MAX_DOCUMENT_QA_TURNS = 8
    const val MAX_GROUNDED_DOCUMENT_CHARS = 12_000
  }
}

data class DocumentAnalysisResult(
    val rawJson: String,
    val json: JsonObject?,
    val documentText: String,
)

class DocumentAiException(
    message: String,
    val responseBody: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)
