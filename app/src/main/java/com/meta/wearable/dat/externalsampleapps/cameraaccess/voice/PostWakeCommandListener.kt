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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.debug.DebugTrace

/**
 * Short-lived [SpeechRecognizer] session after a wake word detection.
 */
class PostWakeCommandListener(
    context: Context,
    private val commandContext: () -> VoiceCommandContext = { VoiceCommandContext() },
    private val onTranscript: (String) -> Unit = {},
    private val onSessionEnded: (String?) -> Unit,
    private val onError: (String) -> Unit = {},
) {
  companion object {
    private const val TAG = "CameraAccess:PostWakeCommand"
  }

  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())

  private var speechRecognizer: SpeechRecognizer? = null
  private var isActive = false
  private var bestTranscript: String? = null
  private var usesGlassesMicrophone = false

  fun start() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { start() }
      return
    }
    if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
      onError("Speech recognition unavailable")
      onSessionEnded(null)
      return
    }
    isActive = true
    bestTranscript = null
    usesGlassesMicrophone = SpeechCaptureSession.routeInput(appContext, audioManager)
    speechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(appContext).apply {
          setRecognitionListener(recognitionListener)
        }
    mainHandler.postDelayed({ listenOnce() }, commandListenDelayMs())
    mainHandler.postDelayed({ stop(cancel = true) }, VoiceWakeConfig.POST_WAKE_TIMEOUT_MS)
  }

  fun stop(cancel: Boolean = true) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { stop(cancel) }
      return
    }
    if (!isActive) return
    isActive = false
    mainHandler.removeCallbacksAndMessages(null)
    runCatching { if (cancel) speechRecognizer?.cancel() else speechRecognizer?.stopListening() }
    runCatching { speechRecognizer?.destroy() }
    speechRecognizer = null
    SpeechCaptureSession.releaseRoute(audioManager)
    onSessionEnded(bestTranscript)
  }

  private fun commandListenDelayMs(): Long {
    return if (usesGlassesMicrophone) {
      if (VoiceAudioEnvironment.hasGlassesBluetoothSco(audioManager)) {
        VoiceWakeConfig.BLUETOOTH_SCO_READY_DELAY_MS
      } else {
        VoiceWakeConfig.BLUETOOTH_HANDOFF_DELAY_MS
      }
    } else {
      VoiceWakeConfig.POST_WAKE_MIC_DELAY_MS
    }
  }

  private fun listenOnce() {
    if (!isActive || speechRecognizer == null) return
    runCatching { speechRecognizer?.startListening(SpeechCaptureSession.createRecognizerIntent()) }
        .onFailure {
          onError(it.message ?: "Failed to start command listener")
          stop()
        }
  }

  private val recognitionListener =
      object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
          if (!isActive) return
          if (SpeechCaptureSession.shouldRetry(error)) {
            mainHandler.postDelayed({ listenOnce() }, SpeechCaptureSession.retryDelayMs())
            return
          }
          val fallback = bestTranscript?.let { SpeechCaptureSession.cleanTranscript(it) }
          if (!fallback.isNullOrBlank()) {
            stop(cancel = false)
          } else {
            onError("Command recognition error: $error")
            stop()
          }
        }

        override fun onResults(results: Bundle?) {
          val transcript = recordTranscript(results)
          val context = commandContext()
          // #region agent log
          DebugTrace.log(
              location = "PostWakeCommandListener:onResults",
              message = "final transcript",
              hypothesisId = "H7",
              data =
                  mapOf(
                      "transcript" to (transcript.ifBlank { "null" }),
                      "actionable" to VoiceCommandResolver.isActionableCommand(transcript, context).toString(),
                      "intent" to VoiceCommandResolver.resolve(transcript, context).name,
                  ),
          )
          // #endregion
          if (transcript.isBlank() || !VoiceCommandResolver.isActionableCommand(transcript, context)) {
            mainHandler.postDelayed({ listenOnce() }, SpeechCaptureSession.retryDelayMs())
            return
          }
          stop(cancel = false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
          val transcript = recordTranscript(partialResults)
          if (transcript.isBlank()) return
          val context = commandContext()
          if (VoiceCommandResolver.isActionableCommand(transcript, context)) {
            handleResults(partialResults)
          }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
      }

  private fun recordTranscript(results: Bundle?): String {
    val transcript = SpeechCaptureSession.extractBestTranscript(results)
    if (transcript.isNotBlank()) {
      val current = bestTranscript
      if (current == null || transcript.length >= current.length) {
        bestTranscript = transcript
      }
      onTranscript(transcript)
    }
    return transcript
  }

  private fun handleResults(results: Bundle?) {
    val transcript = SpeechCaptureSession.extractBestTranscript(results)
    if (transcript.isBlank()) return
    bestTranscript = transcript
    onTranscript(transcript)
    Log.d(TAG, "Post-wake transcript: $transcript")
  }
}
