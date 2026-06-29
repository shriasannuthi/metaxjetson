/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

enum class VoiceSessionState {
  Idle,
  Routing,
  Listening,
  Resolving,
  Executing,
  Speaking,
  Error,
  ;

  companion object {
    val WAKE_ELIGIBLE_STATES: Set<VoiceSessionState> =
        setOf(Idle, Listening, Routing, Error)
  }
}

enum class RecognitionProvider {
  ON_DEVICE,
  ONLINE,
  UNAVAILABLE,
}

data class VoiceDiagnostics(
    val recognitionProvider: RecognitionProvider = RecognitionProvider.UNAVAILABLE,
    val routeLabel: String? = null,
    val phoneAudioFallback: Boolean = false,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
)

data class VoiceSessionSnapshot(
    val state: VoiceSessionState = VoiceSessionState.Idle,
    val partialTranscript: String? = null,
    val finalTranscript: String? = null,
    val statusMessage: String? = null,
    val matchedIntent: VoiceAppIntent? = null,
    val isWakeListening: Boolean = false,
    val isDictationActive: Boolean = false,
    val diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
)
