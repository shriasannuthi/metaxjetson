/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.data

data class CustomerDatabase(
  val customers: List<Customer> = emptyList(),
)

data class Customer(
  val id: String,
  val name: String,
  val phone: String,
  val profile: String,
  val faceImage: String,
  val lastVisit: String,
  val accounts: List<CustomerAccount> = emptyList(),
  val transactions: List<CustomerTransaction> = emptyList(),
  val history: List<CustomerHistoryEntry> = emptyList(),
  val conversationHistory: List<ConversationTurn> = emptyList(),
)

data class CustomerAccount(
  val accountNumber: String = "",
  val type: String = "",
  val balance: String = "",
  val status: String = "",
)

data class CustomerTransaction(
  val id: String = "",
  val date: String = "",
  val description: String = "",
  val category: String = "",
  val amount: String = "",
  val direction: String = "",
  val status: String = "",
)

data class CustomerHistoryEntry(
  val date: String = "",
  val type: String = "",
  val notes: String = "",
)

enum class ConversationRole { CUSTOMER, ASSISTANT }

data class ConversationTurn(
  val role: ConversationRole,
  val text: String,
  val timestampMs: Long = System.currentTimeMillis(),
)

data class CustomerSession(
  val id: String,
  val customerId: String,
  val startedAtMs: Long,
  val endedAtMs: Long? = null,
  val turns: List<ConversationTurn> = emptyList(),
)
