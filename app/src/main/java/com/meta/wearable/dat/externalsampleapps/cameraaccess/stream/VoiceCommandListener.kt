/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

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
 * Listens for a spoken command without retaining audio data.
 *
 * SpeechRecognizer owns the transient microphone stream. This class only inspects recognized text
 * and restarts listening while streaming is active.
 */
class VoiceCommandListener(
    context: Context,
    private val command: String,
    private val onCommandDetected: () -> Unit,
) {
  companion object {
    private const val TAG = "CameraAccess:VoiceCommandListener"
    private const val RESTART_DELAY_MS = 500L
    private const val COMMAND_DEBOUNCE_MS = 1_500L
  }

  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())
  private val normalizedCommand = command.normalizeForCommandMatching()
  private val commandWords = normalizedCommand.split(" ").filter { it.isNotBlank() }

  private var speechRecognizer: SpeechRecognizer? = null
  private var isListening = false
  private var lastCommandDetectedAtMs = 0L

  fun start() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { start() }
      return
    }
    if (isListening) return
    if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
      Log.w(TAG, "Speech recognition is not available on this device")
      return
    }

    Log.i(TAG, "Starting speech recognizer for command: $command")
    isListening = true
    routeAudioToGlassesIfAvailable()
    ensureRecognizer()
    listen()
  }

  fun stop() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { stop() }
      return
    }
    isListening = false
    mainHandler.removeCallbacksAndMessages(null)
    speechRecognizer?.cancel()
    speechRecognizer?.destroy()
    speechRecognizer = null
    audioManager.clearCommunicationDevice()
    Log.i(TAG, "Stopped speech recognizer")
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
                  Log.i(TAG, "Speech recognition error: $error")
                  scheduleRestart()
                }

                override fun onResults(results: Bundle?) {
                  handleRecognizedText(results)
                  scheduleRestart()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                  handleRecognizedText(partialResults)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
              }
          )
        }
  }

  private fun listen() {
    if (!isListening) return

    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(
              RecognizerIntent.EXTRA_LANGUAGE_MODEL,
              RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
          )
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_000L)
        }

    try {
      Log.i(TAG, "Calling SpeechRecognizer.startListening")
      speechRecognizer?.startListening(intent)
    } catch (e: RuntimeException) {
      Log.e(TAG, "Failed to start speech recognition", e)
      scheduleRestart()
    }
  }

  private fun scheduleRestart() {
    if (!isListening) return
    Log.i(TAG, "Scheduling speech recognizer restart")
    mainHandler.postDelayed({ listen() }, RESTART_DELAY_MS)
  }

  private fun handleRecognizedText(results: Bundle?) {
    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    if (matches.isNotEmpty()) {
      Log.i(TAG, "Recognized speech candidates: $matches")
    }

    if (matches.none { it.matchesVoiceCommand() }) {
      if (matches.isNotEmpty()) {
        Log.i(TAG, "Speech did not match command: $command")
      }
      return
    }

    val now = System.currentTimeMillis()
    if (now - lastCommandDetectedAtMs < COMMAND_DEBOUNCE_MS) {
      return
    }

    lastCommandDetectedAtMs = now
    Log.i(TAG, "Voice command detected: $command")
    onCommandDetected()
  }

  private fun routeAudioToGlassesIfAvailable() {
    val scoDevice =
        audioManager.availableCommunicationDevices.firstOrNull {
          it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    if (scoDevice == null) {
      Log.i(TAG, "No Bluetooth SCO device found; using default microphone for speech recognition")
      return
    }
    if (!audioManager.setCommunicationDevice(scoDevice)) {
      Log.w(TAG, "Failed to route speech recognition audio to Bluetooth SCO device")
    }
  }

  private fun String.normalizeForCommandMatching(): String =
      lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

  private fun String.matchesVoiceCommand(): Boolean {
    val normalized = normalizeForCommandMatching()
    if (normalized.contains(normalizedCommand)) {
      return true
    }

    val words = normalized.split(" ").filter { it.isNotBlank() }.toSet()
    val hasAllCommandWords = commandWords.all { it in words }
    val hasCoreIntent = "scan" in words && ("meta" in words || "hey" in words)
    return hasAllCommandWords || hasCoreIntent
  }
}
