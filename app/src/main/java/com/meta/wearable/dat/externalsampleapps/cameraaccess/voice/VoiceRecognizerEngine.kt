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
import android.media.AudioAttributes
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.Executor

class VoiceRecognizerEngine(
    context: Context,
    private val onPartial: (List<RecognizedCandidate>) -> Unit,
    private val onFinal: (List<RecognizedCandidate>) -> Unit,
    private val onReadyForSpeech: () -> Unit,
    private val onError: (Int, String) -> Unit,
    private val mainExecutor: Executor,
) {
  companion object {
    private const val TAG = "CameraAccess:VoiceRecognizer"
    private const val DEFAULT_CONFIDENCE = 0.65f

    /** Server disconnected — common when on-device recognizer fails with Bluetooth audio. */
    const val ERROR_SERVER_DISCONNECTED = 11
  }

  private val appContext = context.applicationContext
  private var speechRecognizer: SpeechRecognizer? = null
  private var provider: RecognitionProvider = RecognitionProvider.ONLINE
  private var isListening = false
  private var forceOnline = false
  private var hasFallbackToOnline = false

  fun discoverSupport(
      routeKind: VoiceRouteKind?,
      onReadyToListen: (RecognitionProvider) -> Unit,
  ) {
    if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
      provider = RecognitionProvider.UNAVAILABLE
      onReadyToListen(provider)
      return
    }

    // On-device recognition frequently disconnects (error 11) when input comes from
    // Bluetooth glasses via SCO/BLE. Prefer the system online recognizer for those routes.
    if (routeKind == VoiceRouteKind.GLASSES_SCO || routeKind == VoiceRouteKind.GLASSES_BLE) {
      Log.i(TAG, "Using online recognizer for glasses audio route: $routeKind")
      provider = RecognitionProvider.ONLINE
      onReadyToListen(provider)
      return
    }

    if (forceOnline) {
      provider = RecognitionProvider.ONLINE
      onReadyToListen(provider)
      return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val probeRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
      probeRecognizer.checkRecognitionSupport(
          buildIntent(forDictation = true),
          mainExecutor,
          object : RecognitionSupportCallback {
            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
              probeRecognizer.destroy()
              val languageInstalled =
                  recognitionSupport.installedOnDeviceLanguages.any {
                    it.equals(VoiceWakeConfig.LANGUAGE_TAG, ignoreCase = true)
                  }
              provider =
                  if (
                      languageInstalled &&
                          SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
                  ) {
                    RecognitionProvider.ON_DEVICE
                  } else {
                    RecognitionProvider.ONLINE
                  }
              Log.i(TAG, "Recognition provider selected: $provider")
              onReadyToListen(provider)
            }

            override fun onError(error: Int) {
              probeRecognizer.destroy()
              Log.w(TAG, "Recognition support check failed: $error; using online")
              provider = RecognitionProvider.ONLINE
              onReadyToListen(provider)
            }
          },
      )
    } else {
      provider = RecognitionProvider.ONLINE
      onReadyToListen(provider)
    }
  }

  fun fallbackToOnline(onReady: () -> Unit) {
    if (hasFallbackToOnline) {
      onReady()
      return
    }
    hasFallbackToOnline = true
    forceOnline = true
    provider = RecognitionProvider.ONLINE
    Log.w(TAG, "Falling back to online speech recognizer after service disconnect")
    destroy()
    onReady()
  }

  fun startListening(forDictation: Boolean) {
    isListening = true
    recreateRecognizer()
    val intent = buildIntent(forDictation)
    try {
      speechRecognizer?.startListening(intent)
    } catch (e: RuntimeException) {
      Log.e(TAG, "Failed to start listening", e)
      onError(SpeechRecognizer.ERROR_CLIENT, e.message ?: "Unable to start listening")
    }
  }

  fun stopListening(cancel: Boolean) {
    isListening = false
    if (cancel) {
      runCatching { speechRecognizer?.cancel() }
    } else {
      runCatching { speechRecognizer?.stopListening() }
    }
  }

  fun destroy() {
    isListening = false
    runCatching { speechRecognizer?.cancel() }
    runCatching { speechRecognizer?.destroy() }
    speechRecognizer = null
  }

  fun currentProvider(): RecognitionProvider = provider

  private fun recreateRecognizer() {
    destroy()
    speechRecognizer =
        when (provider) {
          RecognitionProvider.ON_DEVICE ->
              SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
          RecognitionProvider.ONLINE,
          RecognitionProvider.UNAVAILABLE,
          -> SpeechRecognizer.createSpeechRecognizer(appContext)
        }.apply {
          setRecognitionListener(recognitionListener)
        }
  }

  private fun buildIntent(forDictation: Boolean): Intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, VoiceWakeConfig.LANGUAGE_TAG)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        if (provider == RecognitionProvider.ON_DEVICE) {
          putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        if (!forDictation) {
          putExtra(
              RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
              1_400L,
          )
          putExtra(
              RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
              900L,
          )
        } else {
          putExtra(
              RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
              1_800L,
          )
          putExtra(
              RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
              1_200L,
          )
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
        }
        // Biasing strings are only safe with the online recognizer on recent API levels.
        if (provider == RecognitionProvider.ONLINE && Build.VERSION.SDK_INT >= 34) {
          putExtra(
              RecognizerIntent.EXTRA_BIASING_STRINGS,
              ArrayList(VoiceCommandRegistry.biasPhrases()),
          )
        }
      }

  private val recognitionListener =
      object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
          onReadyForSpeech()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
          if (!isListening) return
          onError(error, errorDescription(error))
        }

        override fun onResults(results: Bundle?) {
          if (!isListening) return
          onFinal(parseCandidates(results))
        }

        override fun onPartialResults(partialResults: Bundle?) {
          if (!isListening) return
          onPartial(parseCandidates(partialResults))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
      }

  private fun parseCandidates(results: Bundle?): List<RecognizedCandidate> {
    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
    if (texts.isEmpty()) return emptyList()
    return texts.mapIndexed { index, text ->
      RecognizedCandidate(
          text = text.trim(),
          confidence = scores?.getOrNull(index) ?: DEFAULT_CONFIDENCE,
      )
    }
  }

  private fun errorDescription(errorCode: Int): String =
      when (errorCode) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognizer client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing"
        SpeechRecognizer.ERROR_NETWORK -> "Speech recognizer network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognizer network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
        SpeechRecognizer.ERROR_SERVER -> "Speech recognizer server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        ERROR_SERVER_DISCONNECTED -> "Speech recognizer disconnected"
        12 -> "Language not supported by recognizer"
        13 -> "On-device language model not downloaded"
        else -> "Speech recognition error $errorCode"
      }
}

class VoiceEarconPlayer {
  private var startTone: ToneGenerator? = null
  private var endTone: ToneGenerator? = null

  fun playStart() {
    runCatching {
      startTone?.release()
      startTone = ToneGenerator(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, 40)
      startTone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
    }
  }

  fun playEnd() {
    runCatching {
      endTone?.release()
      endTone = ToneGenerator(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, 35)
      endTone?.startTone(ToneGenerator.TONE_PROP_ACK, 70)
    }
  }

  fun release() {
    startTone?.release()
    endTone?.release()
    startTone = null
    endTone = null
  }
}
