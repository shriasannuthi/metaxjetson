/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.DocumentScanPhase
import java.util.Locale

data class VoiceCommandContext(
    val isDocumentSessionActive: Boolean = false,
    val documentScanPhase: DocumentScanPhase = DocumentScanPhase.IDLE,
    val isDocumentAnalyzing: Boolean = false,
)

object VoiceCommandResolver {
  private val scanPhrases = listOf("scan", "scan document", "scan a document", "read this", "document")
  private val rescanPhrases = listOf("scan again", "new document", "rescan", "another document")
  private val photoPhrases =
      listOf(
          "take a photo",
          "take photo",
          "take a picture",
          "take picture",
          "capture photo",
          "capture a photo",
          "capture picture",
          "photo",
          "picture",
          "snap",
          "selfie",
      )
  private val photoTokens = setOf("photo", "picture", "pic", "snap", "selfie", "shot")
  private val cancelPhrases = listOf("cancel", "stop")
  private val qnaPhrases =
      listOf("ask a question", "ask question", "ask", "question", "help me", "q and a", "qna", "help")

  fun resolve(transcript: String?, context: VoiceCommandContext = VoiceCommandContext()): VoiceCommandIntent {
    val normalized = normalize(transcript)
    if (normalized.isBlank()) return VoiceCommandIntent.NO_COMMAND

    if (context.isDocumentAnalyzing || context.documentScanPhase.isScanning()) {
      return VoiceCommandIntent.NO_COMMAND
    }

    if (matchesAny(normalized, cancelPhrases)) return VoiceCommandIntent.CANCEL

    if (context.isDocumentSessionActive && matchesAny(normalized, rescanPhrases)) {
      return VoiceCommandIntent.SCAN_DOCUMENT
    }

    if (matchesAny(normalized, scanPhrases)) return VoiceCommandIntent.SCAN_DOCUMENT
    if (matchesAny(normalized, photoPhrases) || photoTokens.any { token -> normalized.contains(token) }) {
      return VoiceCommandIntent.CAPTURE_PHOTO
    }

    if (matchesAny(normalized, qnaPhrases)) {
      return if (context.isDocumentSessionActive && context.documentScanPhase == DocumentScanPhase.READY) {
        VoiceCommandIntent.DOCUMENT_QNA
      } else {
        VoiceCommandIntent.OPEN_QNA
      }
    }

    return VoiceCommandIntent.NO_COMMAND
  }

  fun isActionableCommand(
      transcript: String?,
      context: VoiceCommandContext = VoiceCommandContext(),
  ): Boolean = resolve(transcript, context) != VoiceCommandIntent.NO_COMMAND

  private fun DocumentScanPhase.isScanning(): Boolean =
      this == DocumentScanPhase.CAPTURING ||
          this == DocumentScanPhase.GROUNDING ||
          this == DocumentScanPhase.ANALYZING

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
