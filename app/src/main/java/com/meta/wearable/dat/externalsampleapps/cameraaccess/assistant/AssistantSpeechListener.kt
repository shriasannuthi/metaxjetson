/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.SpeechCaptureSession
import java.util.Locale

class AssistantSpeechListener(
  context: Context,
  private val onReady: () -> Unit,
  private val onPartialTranscript: (String) -> Unit,
  private val onFinalTranscript: (String) -> Unit,
  private val onError: (String) -> Unit,
) {
  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null
  private var bestPartialTranscript: String? = null
  private var finished = false

  fun start() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { start() }
      return
    }
    if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
      onError("Speech recognition is not available on this device")
      return
    }
    finished = false
    bestPartialTranscript = null
    SpeechCaptureSession.routeInput(appContext, audioManager)
    recognizer =
        SpeechRecognizer.createSpeechRecognizer(appContext).apply {
          setRecognitionListener(
              object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                  onReady()
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                  Log.w(TAG, "Assistant speech recognition error: $error (${error.description()})")
                  if (SpeechCaptureSession.shouldRetry(error)) {
                    mainHandler.postDelayed(
                        {
                          recognizer?.startListening(
                              SpeechCaptureSession.createRecognizerIntent(Locale.getDefault().toLanguageTag()),
                          )
                        },
                        SpeechCaptureSession.retryDelayMs(),
                    )
                    return
                  }
                  val fallback = bestPartialTranscript?.let { SpeechCaptureSession.cleanTranscript(it) }
                  if (!fallback.isNullOrBlank()) {
                    finishWithTranscript(fallback)
                  } else {
                    finishWithError(error.description())
                  }
                }

                override fun onResults(results: Bundle?) {
                  val transcript = SpeechCaptureSession.extractBestTranscript(results)
                  if (transcript.isBlank()) {
                    val fallback =
                        bestPartialTranscript?.let { SpeechCaptureSession.cleanTranscript(it) }.orEmpty()
                    if (fallback.isNotBlank()) {
                      finishWithTranscript(fallback)
                    } else {
                      finishWithError("I couldn't hear the customer clearly")
                    }
                  } else {
                    finishWithTranscript(transcript)
                  }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                  val transcript = SpeechCaptureSession.extractBestTranscript(partialResults)
                  if (transcript.isNotBlank()) {
                    bestPartialTranscript = transcript
                    onPartialTranscript(transcript)
                  }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
              }
          )
        }
    try {
      recognizer?.startListening(
          SpeechCaptureSession.createRecognizerIntent(Locale.getDefault().toLanguageTag()),
      )
    } catch (e: RuntimeException) {
      Log.w(TAG, "Unable to start assistant speech recognition", e)
      finishWithError("Unable to start listening: ${e.message.orEmpty()}")
    }
  }

  fun stop(cancel: Boolean = true) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { stop(cancel) }
      return
    }
    if (!finished && !cancel) {
      bestPartialTranscript?.let { SpeechCaptureSession.cleanTranscript(it) }?.takeIf { it.isNotBlank() }?.let {
        finishWithTranscript(it)
        return
      }
    }
    finished = true
    runCatching { recognizer?.cancel() }
    destroyRecognizer()
  }

  private fun finishWithTranscript(transcript: String) {
    if (finished) return
    finished = true
    destroyRecognizer()
    onFinalTranscript(transcript)
  }

  private fun finishWithError(message: String) {
    if (finished) return
    finished = true
    destroyRecognizer()
    onError(message)
  }

  private fun destroyRecognizer() {
    runCatching { recognizer?.destroy() }
    recognizer = null
    SpeechCaptureSession.releaseRoute(audioManager)
  }

  private fun Int.description(): String =
      when (this) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognizer client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing"
        SpeechRecognizer.ERROR_NETWORK -> "Speech recognizer network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognizer network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "I couldn't hear the customer clearly"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
        SpeechRecognizer.ERROR_SERVER -> "Speech recognizer server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        11 -> "Speech recognizer was not ready. Tap Ask again."
        else -> "Speech recognition error $this"
      }

  private companion object {
    const val TAG = "CameraAccess:AssistantSpeech"
  }
}
