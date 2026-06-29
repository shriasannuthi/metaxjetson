/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

import java.util.Locale

object VoiceCommandResolver {
  private val scanPhrases = listOf("scan", "scan document", "scan a document", "read this", "document")
  private val photoPhrases =
      listOf("take a photo", "take photo", "capture photo", "capture a photo", "photo")
  private val cancelPhrases = listOf("cancel", "stop")
  private val qnaPhrases =
      listOf("ask a question", "ask question", "question", "help me", "q and a", "qna")

  fun resolve(transcript: String?): VoiceCommandIntent {
    val normalized = normalize(transcript)
    if (normalized.isBlank()) return VoiceCommandIntent.NO_COMMAND
    if (matchesAny(normalized, cancelPhrases)) return VoiceCommandIntent.CANCEL
    if (matchesAny(normalized, scanPhrases)) return VoiceCommandIntent.SCAN_DOCUMENT
    if (matchesAny(normalized, photoPhrases)) return VoiceCommandIntent.CAPTURE_PHOTO
    if (matchesAny(normalized, qnaPhrases)) return VoiceCommandIntent.OPEN_QNA
    return VoiceCommandIntent.NO_COMMAND
  }

  /** True when the transcript maps to an actionable post-wake command (not [NO_COMMAND]). */
  fun isActionableCommand(transcript: String?): Boolean =
      resolve(transcript) != VoiceCommandIntent.NO_COMMAND

  private fun matchesAny(normalized: String, phrases: List<String>): Boolean =
      phrases.any { phrase -> normalized.contains(phrase) }

  private fun normalize(text: String?): String =
      text
          .orEmpty()
          .lowercase(Locale.US)
          .replace(Regex("[^a-z0-9 ]"), " ")
          .replace(Regex("\\s+"), " ")
          .trim()
}
