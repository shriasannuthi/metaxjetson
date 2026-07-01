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
import com.meta.wearable.dat.mockdevice.MockDeviceKit

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
  private const val BLUETOOTH_HANDOFF_DELAY_MS = 2_000L

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
    } else {
      BLUETOOTH_HANDOFF_DELAY_MS
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

  fun releaseCommunicationDevice(audioManager: AudioManager) {
    runCatching {
      audioManager.mode = AudioManager.MODE_NORMAL
      audioManager.clearCommunicationDevice()
    }
  }
}
