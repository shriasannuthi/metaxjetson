/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

import android.content.Context
import android.media.AudioManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.HandoffPhase

/**
 * Coordinates voice-session audio handoffs and exposes SCO / mock-device status for the UI.
 */
class VoiceSessionController(private val context: Context) {
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  fun isMockDeviceMode(): Boolean = VoiceAudioEnvironment.isMockDeviceActive(context)

  fun isGlassesScoConnected(): Boolean =
      !VoiceAudioEnvironment.prefersPhoneAudio(context, audioManager) &&
          VoiceAudioEnvironment.hasGlassesBluetoothSco(audioManager)

  suspend fun awaitGlassesAudio(onProgress: (HandoffPhase) -> Unit): Boolean {
    return VoiceAudioEnvironment.awaitAudioRoute(
        context = context,
        audioManager = audioManager,
        target = VoiceAudioEnvironment.RouteTarget.GLASSES_SCO,
        onProgress = onProgress,
    )
  }

  suspend fun awaitCameraHandoff(onProgress: (HandoffPhase) -> Unit): Boolean {
    return VoiceAudioEnvironment.awaitAudioRoute(
        context = context,
        audioManager = audioManager,
        target = VoiceAudioEnvironment.RouteTarget.CAMERA,
        onProgress = onProgress,
    )
  }

  fun prewarmGlassesSco() {
    VoiceAudioEnvironment.routeSpeechOutput(context, audioManager)
  }

  fun releaseAudioRoute() {
    VoiceAudioEnvironment.releaseCommunicationDevice(audioManager)
  }
}
