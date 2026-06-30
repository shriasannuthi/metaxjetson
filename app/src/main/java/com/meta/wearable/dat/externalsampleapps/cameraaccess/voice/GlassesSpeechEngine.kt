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
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class GlassesSpeechEngine(context: Context) {
  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private var textToSpeech: TextToSpeech? = null
  private val isReady = AtomicBoolean(false)

  init {
    textToSpeech =
        TextToSpeech(appContext) { status ->
          if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.US
            textToSpeech?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            isReady.set(true)
            Log.i(TAG, "TextToSpeech initialized")
          } else {
            Log.w(TAG, "TextToSpeech init failed: $status")
          }
        }
  }

  suspend fun speak(lines: String): Boolean {
    val cleaned = SpeakableText.toPlainSpeech(lines.trim())
    if (cleaned.isBlank()) return false
    if (!isReady.get()) return false

    val routedToGlasses = VoiceAudioEnvironment.routeSpeechOutput(appContext, audioManager)
    // #region agent log
    com.meta.wearable.dat.externalsampleapps.cameraaccess.debug.DebugTrace.log(
        location = "GlassesSpeechEngine:speak",
        message = "tts speak",
        hypothesisId = "H3-H4",
        data =
            mapOf(
                "routedToGlasses" to routedToGlasses.toString(),
                "chars" to cleaned.length.toString(),
                "preview" to cleaned.take(80),
            ),
    )
    // #endregion

    return suspendCancellableCoroutine { continuation ->
      val utteranceId = "wf-meta-${System.nanoTime()}"
      val tts = textToSpeech
      if (tts == null) {
        releaseCommunicationDevice()
        continuation.resume(false)
        return@suspendCancellableCoroutine
      }

      tts.setOnUtteranceProgressListener(
          object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
              releaseCommunicationDevice()
              if (continuation.isActive) continuation.resume(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
              releaseCommunicationDevice()
              if (continuation.isActive) continuation.resume(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
              releaseCommunicationDevice()
              if (continuation.isActive) continuation.resume(false)
            }
          },
      )

      val result =
          tts.speak(
              cleaned,
              TextToSpeech.QUEUE_FLUSH,
              Bundle.EMPTY,
              utteranceId,
          )
      if (result == TextToSpeech.ERROR && continuation.isActive) {
        releaseCommunicationDevice()
        continuation.resume(false)
      }

      continuation.invokeOnCancellation {
        runCatching { tts.stop() }
        releaseCommunicationDevice()
      }

      if (routedToGlasses) {
        Log.i(TAG, "TTS routed to glasses speaker")
      }
    }
  }

  fun releaseCommunicationDevice() {
    VoiceAudioEnvironment.releaseCommunicationDevice(audioManager)
  }

  fun shutdown() {
    runCatching { textToSpeech?.stop() }
    runCatching { textToSpeech?.shutdown() }
    textToSpeech = null
    isReady.set(false)
  }

  companion object {
    private const val TAG = "CameraAccess:GlassesSpeech"
  }
}
