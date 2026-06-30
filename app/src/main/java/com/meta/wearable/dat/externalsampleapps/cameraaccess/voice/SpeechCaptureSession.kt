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
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Shared speech-capture helpers: SCO routing, recognizer intent configuration, and transcript
 * extraction. Used by post-wake command listening and document Q&A capture.
 */
object SpeechCaptureSession {
  private const val RETRY_DELAY_MS = 350L

  fun routeInput(context: Context, audioManager: AudioManager): Boolean {
    return VoiceAudioEnvironment.routeSpeechInput(context, audioManager)
  }

  fun releaseRoute(audioManager: AudioManager) {
    VoiceAudioEnvironment.releaseCommunicationDevice(audioManager)
  }

  fun createRecognizerIntent(languageTag: String = Locale.US.toLanguageTag()): Intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_800L)
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            1_200L,
        )
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
      }

  fun shouldRetry(error: Int): Boolean =
      when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> true
        else -> false
      }

  fun retryDelayMs(): Long = RETRY_DELAY_MS

  fun extractBestTranscript(results: Bundle?): String {
    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    return matches
        .map { cleanTranscript(it) }
        .filter { it.isNotBlank() }
        .maxByOrNull { it.length }
        .orEmpty()
  }

  fun cleanTranscript(text: String): String = text.trim().replace(Regex("\\s+"), " ")
}
