/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant

import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer

enum class AssistantMode {
  PHASE_5A,
  PHASE_5B,
}

enum class ConversationRole {
  CUSTOMER,
  ASSISTANT,
}

data class ConversationTurn(
  val role: ConversationRole,
  val text: String,
  val timestampMs: Long = System.currentTimeMillis(),
)

data class AssistantUiState(
  val customers: List<Customer> = emptyList(),
  val selectedCustomerIndex: Int = 0,
  val recognizedCustomer: Customer? = null,
  val mode: AssistantMode = AssistantMode.PHASE_5A,
  val isListening: Boolean = false,
  val speechStatus: String? = null,
  val partialTranscript: String? = null,
  val lastQuestion: String? = null,
  val answer: String? = null,
  val isAnswering: Boolean = false,
  val error: String? = null,
  val conversation: List<ConversationTurn> = emptyList(),
) {
  val customer: Customer?
    get() = recognizedCustomer ?: customers.getOrNull(selectedCustomerIndex)
}
