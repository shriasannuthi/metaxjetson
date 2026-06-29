/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

object VoiceWakeConfig {
  const val WAKE_PHRASE = "Okay meta"
  const val LANGUAGE_TAG = "en-US"
  const val MIN_EXECUTION_CONFIDENCE = 0.55f
  const val STABLE_PARTIAL_CONFIDENCE = 0.72f
  const val POST_TTS_COOLDOWN_MS = 450L
  const val POST_ROUTE_STABILIZATION_MS = 500L
  const val POST_CAMERA_RELEASE_DELAY_MS = 600L
  /** Meta DAT docs: allow Bluetooth HFP/A2DP handoff to settle after camera stream stops. */
  const val POST_CAMERA_TO_VOICE_DELAY_MS = 3_500L
  const val VOICE_SESSION_READY_TIMEOUT_MS = 12_000L
  const val STREAMING_READY_TIMEOUT_MS = 12_000L
  const val RECOGNIZER_RETRY_DELAY_MS = 800L
  const val ROUTE_CONFIRM_TIMEOUT_MS = 3_000L
  const val BLUETOOTH_SCO_CONNECT_TIMEOUT_MS = 2_500L
  /** Avoid glasses HFP while DAT camera stream may still hold Bluetooth. */
  const val MIC_ROUTE_STABILIZATION_MS = 400L
  const val MAX_TYPING_DISTANCE_SHORT = 1
  const val MAX_TYPING_DISTANCE_LONG = 2
  const val SHORT_WORD_LENGTH = 5
}

object VoiceCommandRegistry {
  val commands: List<CommandDefinition> =
      listOf(
          CommandDefinition(
              intent = VoiceAppIntent.SCAN_DOCUMENT,
              phrases = listOf("scan", "scan document", "read this"),
              spokenResponse = "Scanning the document.",
          ),
          CommandDefinition(
              intent = VoiceAppIntent.CAPTURE_PHOTO,
              phrases = listOf("take a photo", "capture photo"),
              spokenResponse = "Photo captured.",
          ),
          CommandDefinition(
              intent = VoiceAppIntent.REPEAT_RESPONSE,
              phrases = listOf("repeat", "say that again"),
              spokenResponse = null,
          ),
          CommandDefinition(
              intent = VoiceAppIntent.CANCEL,
              phrases = listOf("cancel", "stop"),
              spokenResponse = "Cancelled.",
          ),
      )

  fun biasPhrases(): List<String> =
      buildList {
        add(VoiceWakeConfig.WAKE_PHRASE)
        commands.flatMap { it.phrases }.forEach { phrase ->
          add("${VoiceWakeConfig.WAKE_PHRASE} $phrase")
          add(phrase)
        }
      }
}
