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

/**
 * Listens for short voice commands **after** a DaVoice wake event.
 *
 * Does not perform wake-word detection — that is handled by [DaVoiceWakeDetector].
 */
class CommandSpeechListener(
    context: Context,
    private val commandPhrases: List<String>,
    private val onCommandDetected: (String) -> Unit,
    private val onSpeechRecognized: (String) -> Unit = {},
    private val onCommandWindowEnded: () -> Unit = {},
) {
  companion object {
    private const val TAG = "CameraAccess:CommandSpeech"
    private const val RESTART_DELAY_MS = 400L
    private const val COMMAND_DEBOUNCE_MS = 1_500L
    private const val FUZZY_TOLERANCE_RATIO = 0.34f
  }

  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())
  private val normalizedCommands = commandPhrases.map { it.normalizeForMatching() }

  private var speechRecognizer: SpeechRecognizer? = null
  private var isListening = false
  private var lastCommandDetectedAtMs = 0L
  private var commandWindowTimeoutRunnable: Runnable? = null

  fun startCommandWindow(timeoutMs: Long = DaVoiceConfig.COMMAND_LISTEN_TIMEOUT_MS) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { startCommandWindow(timeoutMs) }
      return
    }
    if (isListening) return
    if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
      Log.w(TAG, "Speech recognition unavailable")
      onCommandWindowEnded()
      return
    }

    Log.i(TAG, "Opening post-wake command window (${timeoutMs}ms)")
    isListening = true
    mainHandler.removeCallbacksAndMessages(null)
    routeAudioToGlassesIfAvailable()
    ensureRecognizer()
    listen()

    commandWindowTimeoutRunnable =
        Runnable {
          Log.i(TAG, "Command window timed out")
          stop(cancelRecognizer = true)
          onCommandWindowEnded()
        }
    mainHandler.postDelayed(commandWindowTimeoutRunnable!!, timeoutMs)
  }

  fun stop(cancelRecognizer: Boolean = true) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { stop(cancelRecognizer) }
      return
    }
    isListening = false
    commandWindowTimeoutRunnable?.let(mainHandler::removeCallbacks)
    commandWindowTimeoutRunnable = null
    mainHandler.removeCallbacksAndMessages(null)
    try {
      if (cancelRecognizer) {
        speechRecognizer?.cancel()
      } else {
        speechRecognizer?.stopListening()
      }
      speechRecognizer?.destroy()
    } catch (e: Exception) {
      Log.w(TAG, "Error stopping command recognizer: ${e.message}")
    }
    speechRecognizer = null
    runCatching { audioManager.clearCommunicationDevice() }
    Log.i(TAG, "Command speech listener stopped")
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
                  Log.i(TAG, "Command recognition error: $error")
                  if (isListening) scheduleRestart()
                }

                override fun onResults(results: Bundle?) {
                  handleRecognizedText(results)
                  if (isListening) scheduleRestart()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                  handleRecognizedText(partialResults)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
              },
          )
        }
  }

  private fun listen() {
    if (!isListening || speechRecognizer == null) return
    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_400L)
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        }
    try {
      speechRecognizer?.startListening(intent)
    } catch (e: RuntimeException) {
      Log.e(TAG, "Failed to start command recognition", e)
      scheduleRestart()
    }
  }

  private fun scheduleRestart() {
    if (!isListening) return
    mainHandler.postDelayed({ listen() }, RESTART_DELAY_MS)
  }

  private fun handleRecognizedText(results: Bundle?) {
    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    if (matches.isEmpty()) return

    val recognizedText = matches.first()
    onSpeechRecognized(recognizedText)

    val matchedCommand =
        matches.firstOrNull { candidate -> candidate.matchesAnyCommand() } ?: return

    val now = System.currentTimeMillis()
    if (now - lastCommandDetectedAtMs < COMMAND_DEBOUNCE_MS) return
    lastCommandDetectedAtMs = now

    Log.i(TAG, "Command detected: '$matchedCommand'")
    isListening = false
    commandWindowTimeoutRunnable?.let(mainHandler::removeCallbacks)
    commandWindowTimeoutRunnable = null
    speechRecognizer?.cancel()
    onCommandDetected(matchedCommand)
  }

  private fun String.matchesAnyCommand(): Boolean {
    val normalized = normalizeForMatching()
    return normalizedCommands.any { command ->
      normalized.contains(command) ||
          normalized.split(" ").any { word -> word.isCloseMatchTo(command) }
    }
  }

  private fun String.normalizeForMatching(): String =
      lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

  private fun String.isCloseMatchTo(other: String): Boolean {
    if (this == other) return true
    val tolerance = maxOf(1, (maxOf(length, other.length) * FUZZY_TOLERANCE_RATIO).toInt())
    return levenshteinDistance(this, other) <= tolerance
  }

  private fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previousRow = IntArray(b.length + 1) { it }
    var currentRow = IntArray(b.length + 1)
    for (i in 1..a.length) {
      currentRow[0] = i
      for (j in 1..b.length) {
        val substitutionCost = if (a[i - 1] == b[j - 1]) 0 else 1
        currentRow[j] =
            minOf(
                currentRow[j - 1] + 1,
                previousRow[j] + 1,
                previousRow[j - 1] + substitutionCost,
            )
      }
      val swap = previousRow
      previousRow = currentRow
      currentRow = swap
    }
    return previousRow[b.length]
  }

  private fun routeAudioToGlassesIfAvailable() {
    val scoDevice =
        audioManager.availableCommunicationDevices.firstOrNull {
          it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } ?: return
    if (!audioManager.setCommunicationDevice(scoDevice)) {
      Log.w(TAG, "Failed to route command recognition to Bluetooth SCO")
    }
  }
}
