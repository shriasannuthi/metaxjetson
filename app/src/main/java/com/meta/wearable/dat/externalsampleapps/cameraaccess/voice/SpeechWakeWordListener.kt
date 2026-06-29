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
 * Cloud-backed wake phrase listener used when on-device [MicroWakeWord] init is unavailable.
 */
class SpeechWakeWordListener(
    context: Context,
    private val wakePhrase: String,
    private val onWakeWordDetected: () -> Unit,
    private val onStatusChanged: (String) -> Unit = {},
) {
  companion object {
    private const val TAG = "CameraAccess:SpeechWakeListener"
    private const val RESTART_DELAY_MS = 500L
    private const val WAKE_DEBOUNCE_MS = 2_000L
  }

  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())
  private val normalizedWakePhrase = wakePhrase.normalizeForMatching()
  private var speechRecognizer: SpeechRecognizer? = null
  private var isRunning = false
  private var lastWakeAtMs = 0L

  fun start() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { start() }
      return
    }
    if (isRunning) return
    if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
      onStatusChanged("Speech recognition unavailable")
      return
    }
    isRunning = true
    onStatusChanged("Listening for \"$wakePhrase\" (speech)")
    VoiceAudioEnvironment.routeSpeechInput(appContext, audioManager)
    ensureRecognizer()
    listen()
  }

  fun stop() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { stop() }
      return
    }
    isRunning = false
    mainHandler.removeCallbacksAndMessages(null)
    runCatching { speechRecognizer?.cancel() }
    runCatching { speechRecognizer?.destroy() }
    speechRecognizer = null
    VoiceAudioEnvironment.releaseCommunicationDevice(audioManager)
  }

  private fun ensureRecognizer() {
    if (speechRecognizer != null) return
    speechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(appContext).apply {
          setRecognitionListener(
              object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                  if (
                      error != SpeechRecognizer.ERROR_NO_MATCH &&
                          error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                  ) {
                    Log.i(TAG, "Speech wake error: $error")
                  }
                  scheduleRestart()
                }

                override fun onResults(results: Bundle?) {
                  handleResults(results)
                  scheduleRestart()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                  handleResults(partialResults)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
              },
          )
        }
  }

  private fun listen() {
    if (!isRunning) return
    val recognizer = speechRecognizer ?: return
    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
    recognizer.startListening(intent)
  }

  private fun scheduleRestart() {
    if (!isRunning) return
    mainHandler.postDelayed({ listen() }, RESTART_DELAY_MS)
  }

  private fun handleResults(results: Bundle?) {
    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    if (matches.isEmpty()) return
    if (matches.none { it.matchesWakePhrase() }) return

    val now = System.currentTimeMillis()
    if (now - lastWakeAtMs < WAKE_DEBOUNCE_MS) return
    lastWakeAtMs = now
    Log.i(TAG, "Wake phrase detected via speech: $wakePhrase")
    onWakeWordDetected()
  }

  private fun String.normalizeForMatching(): String =
      lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

  private fun String.matchesWakePhrase(): Boolean {
    val normalized = normalizeForMatching()
    if (normalized.contains(normalizedWakePhrase)) return true
    // SpeechRecognizer often hears "hey charly" as "hey charlie" / "hey Charlie".
    return normalized.contains("hey charl")
  }
}
