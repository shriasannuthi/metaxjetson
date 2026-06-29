/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Coordinates DaVoice wake-word detection with post-wake command recognition.
 *
 * DaVoice runs continuously with low CPU use. When the wake phrase fires, DaVoice pauses and
 * [CommandSpeechListener] opens a short command window (e.g. "scan", "scan document").
 */
class VoiceWakeSession(
    context: Context,
    private val commandPhrases: List<String>,
    private val onScanCommand: () -> Unit,
    private val onStatusChanged: (String) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
  companion object {
    private const val TAG = "CameraAccess:VoiceWakeSession"
  }

  private val daVoice = DaVoiceWakeDetector(context, mainHandler)
  private val commandListener =
      CommandSpeechListener(
          context = context,
          commandPhrases = commandPhrases,
          onCommandDetected = { transcript ->
            onTranscript(transcript)
            onStatusChanged("Command detected")
            onScanCommand()
            resumeWakeListening()
          },
          onSpeechRecognized = onTranscript,
          onCommandWindowEnded = { resumeWakeListening() },
      )

  private var isActive = false

  fun start() {
    runOnMain {
      if (isActive) return@runOnMain
      isActive = true
      onStatusChanged("Starting DaVoice wake listener…")
      daVoice.start(
          onWake = { openCommandWindow() },
          onError = { message ->
            Log.e(TAG, "DaVoice error: $message")
            onStatusChanged(message)
          },
      )
      if (daVoice.isLibraryPresent && DaVoiceConfig.isConfigured()) {
        onStatusChanged("DaVoice listening for wake phrase")
      }
    }
  }

  fun stop() {
    runOnMain {
      if (!isActive) return@runOnMain
      isActive = false
      commandListener.stop()
      daVoice.stop()
      onStatusChanged("Voice stopped")
    }
  }

  /** Opens the post-wake command window without requiring a DaVoice trigger (mic test button). */
  fun openCommandWindowForTest() {
    runOnMain {
      if (!isActive) {
        isActive = true
      }
      openCommandWindow(manual = true)
    }
  }

  fun pauseForDocumentCapture() {
    runOnMain {
      commandListener.stop()
      daVoice.pause()
      onStatusChanged("Voice paused for document capture")
    }
  }

  fun resumeAfterDocumentCapture() {
    runOnMain {
      if (!isActive) return@runOnMain
      daVoice.resume()
      onStatusChanged("DaVoice listening for wake phrase")
    }
  }

  private fun openCommandWindow(manual: Boolean = false) {
    daVoice.pause()
    onStatusChanged(
        if (manual) {
          "Listening for command…"
        } else {
          "Wake detected — say scan or scan document"
        },
    )
    commandListener.startCommandWindow()
  }

  private fun resumeWakeListening() {
    if (!isActive) return
    commandListener.stop()
    daVoice.resume()
    onStatusChanged("DaVoice listening for wake phrase")
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
    } else {
      mainHandler.post(block)
    }
  }
}
