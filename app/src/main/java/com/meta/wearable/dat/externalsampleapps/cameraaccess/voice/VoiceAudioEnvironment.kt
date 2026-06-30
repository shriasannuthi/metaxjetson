/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.HandoffPhase
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import kotlinx.coroutines.delay

/**
 * Chooses phone vs glasses Bluetooth audio routing.
 *
 * MockDeviceKit runs without physical glasses audio, so speech I/O stays on the phone even if a
 * Bluetooth SCO device is connected.
 *
 * Meta glasses microphone access uses the system Bluetooth HFP/SCO profile — see
 * https://wearables.developer.meta.com/docs/microphones-and-speakers/
 */
object VoiceAudioEnvironment {
  private const val TAG = "CameraAccess:VoiceAudio"
  private const val MOCK_HANDOFF_DELAY_MS = 150L
  private const val ROUTE_POLL_INTERVAL_MS = 100L
  private const val ROUTE_READY_DELAY_MS = 400L
  private const val ROUTE_MAX_TIMEOUT_MS = 2_000L

  enum class RouteTarget {
    PHONE_MIC,
    GLASSES_SCO,
    CAMERA,
  }

  fun isMockDeviceActive(context: Context): Boolean {
    return runCatching { MockDeviceKit.getInstance(context).isEnabled }.getOrDefault(false)
  }

  fun hasGlassesBluetoothSco(audioManager: AudioManager): Boolean {
    return audioManager.availableCommunicationDevices.any {
      it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }
  }

  fun prefersPhoneAudio(context: Context, audioManager: AudioManager): Boolean {
    return isMockDeviceActive(context) || !hasGlassesBluetoothSco(audioManager)
  }

  fun bluetoothHandoffDelayMs(context: Context, audioManager: AudioManager): Long {
    return if (prefersPhoneAudio(context, audioManager)) {
      MOCK_HANDOFF_DELAY_MS
    } else if (hasGlassesBluetoothSco(audioManager)) {
      ROUTE_READY_DELAY_MS
    } else {
      VoiceWakeConfig.BLUETOOTH_HANDOFF_DELAY_MS
    }
  }

  /**
   * Polls until the requested audio route is ready or the timeout elapses.
   *
   * Uses adaptive delays: 400 ms when SCO is already available, up to 2 s max.
   */
  suspend fun awaitAudioRoute(
      context: Context,
      audioManager: AudioManager,
      target: RouteTarget,
      onProgress: (HandoffPhase) -> Unit = {},
  ): Boolean {
    val progressPhase =
        when (target) {
          RouteTarget.GLASSES_SCO -> HandoffPhase.SWITCHING_TO_GLASSES
          RouteTarget.CAMERA -> HandoffPhase.SWITCHING_TO_CAMERA
          RouteTarget.PHONE_MIC -> HandoffPhase.IDLE
        }
    if (progressPhase != HandoffPhase.IDLE) {
      onProgress(progressPhase)
    }

    when (target) {
      RouteTarget.PHONE_MIC -> {
        releaseCommunicationDevice(audioManager)
        delay(MOCK_HANDOFF_DELAY_MS)
        onProgress(HandoffPhase.IDLE)
        return true
      }
      RouteTarget.GLASSES_SCO -> {
        if (prefersPhoneAudio(context, audioManager)) {
          releaseCommunicationDevice(audioManager)
          delay(MOCK_HANDOFF_DELAY_MS)
          onProgress(HandoffPhase.IDLE)
          return false
        }
        val routed = routeSpeechInput(context, audioManager)
        if (routed) {
          delay(ROUTE_READY_DELAY_MS)
          onProgress(HandoffPhase.IDLE)
          return true
        }
        val deadline = System.currentTimeMillis() + ROUTE_MAX_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
          if (routeSpeechInput(context, audioManager)) {
            delay(ROUTE_READY_DELAY_MS)
            onProgress(HandoffPhase.IDLE)
            return true
          }
          delay(ROUTE_POLL_INTERVAL_MS)
        }
        onProgress(HandoffPhase.IDLE)
        return false
      }
      RouteTarget.CAMERA -> {
        releaseCommunicationDevice(audioManager)
        val delayMs = bluetoothHandoffDelayMs(context, audioManager)
        val deadline = System.currentTimeMillis() + delayMs.coerceAtMost(ROUTE_MAX_TIMEOUT_MS)
        while (System.currentTimeMillis() < deadline) {
          delay(ROUTE_POLL_INTERVAL_MS.coerceAtMost(delayMs))
        }
        onProgress(HandoffPhase.IDLE)
        return true
      }
    }
  }

  /**
   * Routes [SpeechRecognizer] / [android.media.AudioRecord] input to the glasses microphone when
   * connected over Bluetooth SCO. Falls back to the phone microphone for MockDeviceKit or when
   * no SCO device is available.
   *
   * @return true when input is routed to glasses, false when using the phone mic.
   */
  fun routeSpeechInput(context: Context, audioManager: AudioManager): Boolean {
    if (prefersPhoneAudio(context, audioManager)) {
      releaseCommunicationDevice(audioManager)
      return false
    }
    val scoDevice =
        audioManager.availableCommunicationDevices.firstOrNull {
          it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    if (scoDevice == null) {
      Log.w(TAG, "No glasses found on the Bluetooth SCO audio profile")
      releaseCommunicationDevice(audioManager)
      return false
    }
    audioManager.mode = AudioManager.MODE_NORMAL
    if (!audioManager.setCommunicationDevice(scoDevice)) {
      Log.w(TAG, "Failed to route speech input to the glasses microphone")
      return false
    }
    Log.i(TAG, "Speech input routed to glasses microphone")
    return true
  }

  /**
   * Routes TTS output to the glasses speaker over Bluetooth SCO when available.
   *
   * @return true when output is routed to glasses, false when using the phone speaker.
   */
  fun routeSpeechOutput(context: Context, audioManager: AudioManager): Boolean {
    return routeSpeechInput(context, audioManager)
  }

  fun releaseCommunicationDevice(audioManager: AudioManager) {
    runCatching {
      audioManager.mode = AudioManager.MODE_NORMAL
      audioManager.clearCommunicationDevice()
    }
  }
}
