/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.microwakeword.MicroWakeWord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.microwakeword.MicroWakeWordModelConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.microwakeword.MicroWakeWordModelLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Continuous on-device wake word detection using [MicroWakeWord].
 *
 * Uses the phone microphone at 16 kHz (required by the model). This avoids Bluetooth HFP/SCO
 * contention with the DAT camera stream on Meta glasses.
 */
class MicroWakeWordWakeListener(
    context: Context,
    private val modelConfig: MicroWakeWordModelConfig,
    private val onWakeWordDetected: (MicroWakeWordModelConfig) -> Unit,
    private val onStatusChanged: (String) -> Unit = {},
) {
  companion object {
    private const val TAG = "CameraAccess:MicroWakeWordListener"
    private const val CHUNK_DURATION_MS = 10L
    private const val POST_DETECTION_COOLDOWN_CHUNKS = 200
  }

  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private var detectionJob: Job? = null
  private var isRunning = false

  fun start(scope: CoroutineScope) {
    if (isRunning) return
    isRunning = true
    onStatusChanged("Listening for \"${modelConfig.wakeWord}\"")
    detectionJob =
        scope.launch(Dispatchers.Default) {
          try {
            runDetectionLoop()
          } catch (e: Exception) {
            Log.e(TAG, "Wake word detection loop failed", e)
            withContext(Dispatchers.Main) {
              onStatusChanged("Wake word error: ${e.message ?: "unavailable"}")
            }
          }
        }
    Log.i(TAG, "Started microWakeWord listener for \"${modelConfig.wakeWord}\"")
  }

  fun stop() {
    isRunning = false
    detectionJob?.cancel()
    detectionJob = null
    Log.i(TAG, "Stopped microWakeWord listener")
  }

  private suspend fun runDetectionLoop() {
    val sampleRate = MicroWakeWord.DEFAULT_SAMPLE_RATE
    val samplesPerChunk = ((sampleRate * CHUNK_DURATION_MS) / 1000).toInt().coerceAtLeast(1)
    val minBuffer =
        AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
    if (minBuffer <= 0) {
      withContext(Dispatchers.Main) { onStatusChanged("Microphone unavailable") }
      return
    }

    MicroWakeWordModelLoader.createDetector(appContext, modelConfig).use { detector ->
      val audioRecord =
          AudioRecord(
              MediaRecorder.AudioSource.VOICE_RECOGNITION,
              sampleRate,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              maxOf(minBuffer, samplesPerChunk * 4),
          )
      if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
        withContext(Dispatchers.Main) { onStatusChanged("Failed to open microphone") }
        return
      }

      val readBuffer = ShortArray(samplesPerChunk)
      var cooldownChunks = 0
      try {
        audioRecord.startRecording()
        while (coroutineContext.isActive && isRunning) {
          val read = audioRecord.read(readBuffer, 0, readBuffer.size)
          if (read <= 0) continue
          val chunk = if (read == readBuffer.size) readBuffer else readBuffer.copyOf(read)
          if (cooldownChunks > 0) {
            cooldownChunks--
            continue
          }
          if (detector.processAudio(chunk)) {
            Log.i(TAG, "Wake word detected: ${modelConfig.wakeWord}")
            detector.reset()
            cooldownChunks = POST_DETECTION_COOLDOWN_CHUNKS
            mainHandler.post { onWakeWordDetected(modelConfig) }
          }
        }
      } finally {
        runCatching { audioRecord.stop() }
        audioRecord.release()
      }
    }
  }
}
