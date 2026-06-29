/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

object VoiceWakeConfig {
  const val MODEL_CONFIG_ASSET = "wakeword/hey_charly.json"
  const val WAKE_PHRASE = "hey_charly"
  const val POST_WAKE_TIMEOUT_MS = 8_000L
  const val WAKE_COOLDOWN_MS = 2_000L
  /** Full handoff when SCO must be established from idle. */
  const val BLUETOOTH_HANDOFF_DELAY_MS = 2_000L
  /** Shorter delay when glasses SCO is already available on the phone. */
  const val BLUETOOTH_SCO_READY_DELAY_MS = 400L
  const val POST_TTS_COOLDOWN_MS = 600L
  const val POST_WAKE_MIC_DELAY_MS = 150L

  val POST_WAKE_COMMAND_PHRASES =
      listOf(
          "scan",
          "scan document",
          "read this",
          "take a photo",
          "capture photo",
          "cancel",
          "stop",
      )
}
