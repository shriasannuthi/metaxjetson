/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
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
    routeAudioToGlassesIfAvailable()
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
                  val fallback = bestPartialTranscript?.cleanTranscript()
                  if (!fallback.isNullOrBlank()) {
                    finishWithTranscript(fallback)
                  } else {
                    finishWithError(error.description())
                  }
                }

                override fun onResults(results: Bundle?) {
                  val transcript = results.bestTranscript()
                  if (transcript.isBlank()) {
                    val fallback = bestPartialTranscript?.cleanTranscript().orEmpty()
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
                  val transcript = partialResults.bestTranscript()
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
          Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_800L)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1_200L,
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
          }
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
      bestPartialTranscript?.cleanTranscript()?.takeIf { it.isNotBlank() }?.let {
        finishWithTranscript(it)
        return
      }
    }
    finished = true
    runCatching { recognizer?.cancel() }
    destroyRecognizer()
  }

  private fun Bundle?.bestTranscript(): String {
    val matches = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    return matches
        .map { it.cleanTranscript() }
        .filter { it.isNotBlank() }
        .maxByOrNull { it.length }
        .orEmpty()
  }

  private fun String.cleanTranscript(): String = trim().replace(Regex("\\s+"), " ")

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
    runCatching { audioManager.clearCommunicationDevice() }
  }

  private fun routeAudioToGlassesIfAvailable() {
    val scoDevice =
        audioManager.availableCommunicationDevices.firstOrNull {
          it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    if (scoDevice != null) {
      audioManager.setCommunicationDevice(scoDevice)
    }
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
