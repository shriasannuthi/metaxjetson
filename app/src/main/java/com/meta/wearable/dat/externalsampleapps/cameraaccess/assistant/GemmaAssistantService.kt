/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant

import android.content.Context
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ai.LocalAiClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ai.LocalAiException
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ai.LocalAiResponseMode
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer
import java.io.IOException

class GemmaAssistantService(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val localAiClient: LocalAiClient = LocalAiClient(),
) {
  fun isConfigured(): Boolean = localAiClient.isConfigured()

  suspend fun answerCustomerQuestion(
      customer: Customer,
      question: String,
      mode: AssistantMode,
      conversation: List<ConversationTurn>,
      onPartialText: (String) -> Unit = {},
  ): String {
    if (!isConfigured()) {
      throw GemmaAssistantException("Local AI server is not configured in local.properties")
    }
    val answer =
        try {
          localAiClient.chat(
              prompt = buildPrompt(customer, question, mode, conversation),
              responseMode = LocalAiResponseMode.TEXT,
              maxTokens = MAX_OUTPUT_TOKENS,
          )
        } catch (e: LocalAiException) {
          throw GemmaAssistantException(e.message ?: "Local Gemma request failed", e.responseBody, e)
        }
    onPartialText(answer)
    return answer
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
          conversation.takeLast(10).forEach { turn -> appendLine("${turn.role.name}: ${turn.text}") }
        }
        appendLine()
        appendLine("Customer's current spoken query:")
        appendLine(question)
      }

  private companion object {
    const val MAX_OUTPUT_TOKENS = 320
  }
}

class GemmaAssistantException(
    message: String,
    val responseBody: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)
