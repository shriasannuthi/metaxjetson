/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class VoiceTextToSpeechEngine(
    context: Context,
    private val onInitialized: (Boolean) -> Unit,
) {
  companion object {
    private const val TAG = "CameraAccess:VoiceTts"
  }

  private val appContext = context.applicationContext
  private var textToSpeech: TextToSpeech? = null
  private var isReady = false
  private var completionCallback: (() -> Unit)? = null
  private val hasCompletion = AtomicBoolean(false)

  init {
    textToSpeech =
        TextToSpeech(appContext) { status ->
          isReady = status == TextToSpeech.SUCCESS
          if (isReady) {
            textToSpeech?.language = Locale.forLanguageTag(VoiceWakeConfig.LANGUAGE_TAG)
          } else {
            Log.w(TAG, "TextToSpeech initialization failed: $status")
          }
          onInitialized(isReady)
        }
    textToSpeech?.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) = Unit

          override fun onDone(utteranceId: String?) {
            if (hasCompletion.compareAndSet(true, false)) {
              completionCallback?.invoke()
              completionCallback = null
            }
          }

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) {
            onDone(utteranceId)
          }

          override fun onError(utteranceId: String?, errorCode: Int) {
            onDone(utteranceId)
          }
        },
    )
  }

  fun speak(text: String, onComplete: (() -> Unit)? = null) {
    if (!isReady || text.isBlank()) {
      onComplete?.invoke()
      return
    }
    completionCallback = onComplete
    hasCompletion.set(onComplete != null)
    val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    textToSpeech?.setAudioAttributes(attributes)
    textToSpeech?.speak(
        text,
        TextToSpeech.QUEUE_FLUSH,
        Bundle.EMPTY,
        "voice-session-${System.currentTimeMillis()}",
    )
  }

  fun stop() {
    textToSpeech?.stop()
    completionCallback = null
    hasCompletion.set(false)
  }

  fun shutdown() {
    stop()
    textToSpeech?.shutdown()
    textToSpeech = null
    isReady = false
  }
}
