/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Short-lived [SpeechRecognizer] session after a wake word detection.
 *
 * Routes speech input to the Meta glasses microphone over Bluetooth SCO when glasses are connected
 * (same pattern as [com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.AssistantSpeechListener]
 * and the DAT microphones-and-speakers guide). Falls back to the phone mic for MockDeviceKit.
 *
 * Note: on-device wake word detection still uses the phone microphone at 16 kHz (model requirement).
 */
class PostWakeCommandListener(
    context: Context,
    private val onTranscript: (String) -> Unit = {},
    private val onSessionEnded: (String?) -> Unit,
    private val onError: (String) -> Unit = {},
) {
  companion object {
    private const val TAG = "CameraAccess:PostWakeCommand"
    private const val RETRY_DELAY_MS = 350L
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
    usesGlassesMicrophone = routeAudioForCommandListening()
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
    runCatching { VoiceAudioEnvironment.releaseCommunicationDevice(audioManager) }
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
    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_800L)
          putExtra(
              RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
              1_200L,
          )
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
        }
    runCatching { speechRecognizer?.startListening(intent) }
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
          when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
              mainHandler.postDelayed({ listenOnce() }, RETRY_DELAY_MS)
            }
            else -> {
              val fallback = bestTranscript?.cleanTranscript()
              if (!fallback.isNullOrBlank()) {
                stop(cancel = false)
              } else {
                onError("Command recognition error: $error")
                stop()
              }
            }
          }
        }

        override fun onResults(results: Bundle?) {
          val transcript = results.bestTranscript()
          if (transcript.isBlank() || !VoiceCommandResolver.isActionableCommand(transcript)) {
            mainHandler.postDelayed({ listenOnce() }, RETRY_DELAY_MS)
            return
          }
          handleResults(results)
          stop(cancel = false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
          val transcript = partialResults.bestTranscript()
          if (transcript.isBlank() || !VoiceCommandResolver.isActionableCommand(transcript)) return
          handleResults(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
      }

  private fun handleResults(results: Bundle?) {
    val transcript = results.bestTranscript()
    if (transcript.isBlank()) return
    bestTranscript = transcript
    onTranscript(transcript)
    Log.d(TAG, "Post-wake transcript: $transcript")
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

  private fun routeAudioForCommandListening(): Boolean {
    return VoiceAudioEnvironment.routeSpeechInput(appContext, audioManager)
  }
}
