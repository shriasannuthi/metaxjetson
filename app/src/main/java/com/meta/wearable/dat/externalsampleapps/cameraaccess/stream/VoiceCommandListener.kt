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
  private val onSpeechRecognized: (String, Boolean) -> Unit = { _, _ -> },
  private val onListeningStarted: () -> Unit = {},
) {
  companion object {
    private const val TAG = "CameraAccess:VoiceCommandListener"
    private const val RESTART_DELAY_MS = 500L
    private const val MANUAL_RESTART_DELAY_MS = 200L
    private const val COMMAND_DEBOUNCE_MS = 1_500L

    // Tolerance for fuzzy matching, expressed as a fraction of the compared string's length.
    // SpeechRecognizer frequently mis-segments "hey meta" into things like "he metal" / "hemetal" /
    // "a meta" — these constants control how much edit-distance slack we allow before rejecting a
    // candidate as "not the command".
    private const val FUZZY_WORD_TOLERANCE_RATIO = 0.34
    private const val FUZZY_PHRASE_TOLERANCE_RATIO = 0.35
    private const val FUZZY_PHRASE_MIN_TOLERANCE = 2
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
    if (isListening) {
      Log.d(TAG, "Already listening, ignoring start() call")
      return
    }
    if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
      Log.w(TAG, "Speech recognition is not available on this device")
      return
    }

    Log.i(TAG, "Starting speech recognizer for command: $command")
    isListening = true
    mainHandler.removeCallbacksAndMessages(null)
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
    try {
      speechRecognizer?.cancel()
      speechRecognizer?.destroy()
    } catch (e: Exception) {
      Log.w(TAG, "Error stopping speech recognizer: ${e.message}")
    }
    speechRecognizer = null
    try {
      audioManager.clearCommunicationDevice()
    } catch (e: Exception) {
      Log.w(TAG, "Error clearing communication device: ${e.message}")
    }
    Log.i(TAG, "Stopped speech recognizer")
  }

  fun restartListeningNow() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { restartListeningNow() }
      return
    }
    Log.d(TAG, "Manual restart requested")
    if (!isListening) {
      Log.d(TAG, "Not listening, starting instead")
      start()
      return
    }
    mainHandler.removeCallbacksAndMessages(null)
    try {
      speechRecognizer?.cancel()
    } catch (e: Exception) {
      Log.w(TAG, "Error canceling recognizer: ${e.message}")
    }
    mainHandler.postDelayed({
      Log.d(TAG, "Restarting listening after delay")
      listen()
    }, MANUAL_RESTART_DELAY_MS)
  }

  private fun ensureRecognizer() {
    if (speechRecognizer != null) {
      Log.d(TAG, "Speech recognizer already exists")
      return
    }

    try {
      Log.d(TAG, "Creating new SpeechRecognizer instance")
      speechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(appContext).apply {
          setRecognitionListener(
            object : RecognitionListener {
              override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Speech recognizer ready for speech")
                onListeningStarted()
              }

              override fun onBeginningOfSpeech() {
                Log.d(TAG, "User started speaking")
              }

              override fun onRmsChanged(rmsdB: Float) = Unit

              override fun onBufferReceived(buffer: ByteArray?) = Unit

              override fun onEndOfSpeech() {
                Log.d(TAG, "User stopped speaking")
              }

              override fun onError(error: Int) {
                Log.i(TAG, "Speech recognition error: $error (${getErrorDescription(error)})")
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                  Log.i(TAG, "Recreating speech recognizer due to error: $error")
                  recreateRecognizer()
                }
                scheduleRestart()
              }

              override fun onResults(results: Bundle?) {
                Log.d(TAG, "Speech recognition results received")
                handleRecognizedText(results, isFinal = true)
                scheduleRestart()
              }

              override fun onPartialResults(partialResults: Bundle?) {
                Log.d(TAG, "Partial speech recognition results")
                handleRecognizedText(partialResults, isFinal = false)
              }

              override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }
          )
        }
      Log.d(TAG, "SpeechRecognizer created successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create SpeechRecognizer: ${e.message}", e)
      speechRecognizer = null
    }
  }

  private fun recreateRecognizer() {
    try {
      speechRecognizer?.cancel()
      speechRecognizer?.destroy()
    } catch (e: Exception) {
      Log.w(TAG, "Error cleaning up old speech recognizer: ${e.message}")
    }
    speechRecognizer = null
    ensureRecognizer()
  }

  private fun getErrorDescription(errorCode: Int): String {
    return when (errorCode) {
      SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
      SpeechRecognizer.ERROR_CLIENT -> "Client side error"
      SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
      SpeechRecognizer.ERROR_NETWORK -> "Network error"
      SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
      SpeechRecognizer.ERROR_NO_MATCH -> "No speech input detected"
      SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
      SpeechRecognizer.ERROR_SERVER -> "Server error"
      SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
      else -> "Unknown error ($errorCode)"
    }
  }

  private fun listen() {
    if (!isListening) {
      Log.d(TAG, "Not listening, skipping listen()")
      return
    }

    if (speechRecognizer == null) {
      ensureRecognizer()
    }
    if (speechRecognizer == null) {
      Log.w(TAG, "SpeechRecognizer is null, cannot start listening")
      return
    }

    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
          RecognizerIntent.EXTRA_LANGUAGE_MODEL,
          RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_000L)
      }

    try {
      Log.i(TAG, "Calling SpeechRecognizer.startListening")
      speechRecognizer?.startListening(intent)
    } catch (e: RuntimeException) {
      Log.e(TAG, "Failed to start speech recognition: ${e.message}", e)
      scheduleRestart()
    }
  }

  private fun scheduleRestart() {
    if (!isListening) return
    Log.i(TAG, "Scheduling speech recognizer restart")
    try {
      speechRecognizer?.cancel()
    } catch (e: Exception) {
      Log.w(TAG, "Error canceling speech recognizer during restart: ${e.message}")
    }
    mainHandler.postDelayed({ listen() }, RESTART_DELAY_MS)
  }

  private fun handleRecognizedText(results: Bundle?, isFinal: Boolean) {
    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    if (matches.isEmpty()) {
      Log.d(TAG, "No speech recognition results available")
      return
    }

    val recognizedText = matches.first()
    Log.i(TAG, "Recognized speech: '$recognizedText'")
    Log.d(TAG, "All candidates: $matches")

    // Report the recognized text to the callback
    onSpeechRecognized(recognizedText, isFinal)

    // Check if it matches the voice command
    if (matches.none { it.matchesVoiceCommand() }) {
      Log.d(TAG, "Speech did not match command: $command")
      return
    }

    val now = System.currentTimeMillis()
    if (now - lastCommandDetectedAtMs < COMMAND_DEBOUNCE_MS) {
      Log.d(TAG, "Command detected too recently, ignoring (debounce)")
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

    val words = normalized.split(" ").filter { it.isNotBlank() }
    if (hasFuzzyWordMatches(words)) {
      return true
    }

    // Fallback: SpeechRecognizer often mis-segments "hey meta" into things like "he metal" /
    // "hemetal" / "a meta", which breaks word-by-word matching entirely (the words above don't
    // line up 1:1 with the command words anymore). Compare the whole utterance against the
    // command using a sliding-window edit distance so small phonetic/segmentation slips are still
    // accepted, while unrelated speech is still rejected.
    return hasFuzzyPhraseMatch(normalized)
  }

  /** True if every word in the command has a close spelling/phonetic match somewhere in [words]. */
  private fun hasFuzzyWordMatches(words: List<String>): Boolean {
    if (commandWords.isEmpty()) return false
    return commandWords.all { commandWord -> words.any { it.isCloseMatchTo(commandWord) } }
  }

  private fun String.isCloseMatchTo(other: String): Boolean {
    if (this == other) return true
    val tolerance = maxOf(1, (maxOf(length, other.length) * FUZZY_WORD_TOLERANCE_RATIO).toInt())
    return levenshteinDistance(this, other) <= tolerance
  }

  /**
   * Slides a window across [text] (spaces stripped) and checks the edit distance against the
   * command (also stripped of spaces). This catches cases where the recognizer's word boundaries
   * don't match the command's word boundaries at all, e.g. "hemetal scan" vs "hey meta scan".
   */
  private fun hasFuzzyPhraseMatch(text: String): Boolean {
    val collapsedCommand = normalizedCommand.replace(" ", "")
    val collapsedText = text.replace(" ", "")
    if (collapsedCommand.isEmpty() || collapsedText.length < collapsedCommand.length - 2) {
      return false
    }

    val tolerance =
      maxOf(
        FUZZY_PHRASE_MIN_TOLERANCE,
        (collapsedCommand.length * FUZZY_PHRASE_TOLERANCE_RATIO).toInt(),
      )
    val minWindow = (collapsedCommand.length - 2).coerceAtLeast(1)
    val maxWindow = collapsedCommand.length + 2

    for (windowSize in minWindow..maxWindow) {
      if (windowSize > collapsedText.length) continue
      for (start in 0..(collapsedText.length - windowSize)) {
        val window = collapsedText.substring(start, start + windowSize)
        if (levenshteinDistance(window, collapsedCommand) <= tolerance) {
          return true
        }
      }
    }
    return false
  }

  /** Standard iterative Levenshtein edit distance, O(a.length * b.length), no allocation per cell. */
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
            currentRow[j - 1] + 1, // insertion
            previousRow[j] + 1, // deletion
            previousRow[j - 1] + substitutionCost, // substitution
          )
      }
      val swap = previousRow
      previousRow = currentRow
      currentRow = swap
    }
    return previousRow[b.length]
  }
}