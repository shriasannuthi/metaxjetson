/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import java.nio.ByteBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * MockAudioStream - A simulated audio stream for testing purposes.
 * Emits dummy AudioFrames at a regular interval.
 */
class MockAudioStream : AudioStream {
  private var isStreaming = false

  override val audioStream: Flow<AudioFrame> = flow {
    var timestamp = 0L
    val sampleRate = 48000
    val frequency = 440.0 // A4 note
    var phase = 0.0
    val frameDurationMs = 20
    val samplesPerFrame = (sampleRate * frameDurationMs) / 1000
    val numChannels = 2

    while (coroutineContext.isActive) {
      if (isStreaming) {
        // Generate a dummy audio buffer with a sine wave
        val buffer = ByteBuffer.allocateDirect(samplesPerFrame * numChannels * 2) // 2 bytes per sample (Short)
        for (i in 0 until samplesPerFrame) {
          val value = (kotlin.math.sin(phase) * Short.MAX_VALUE).toInt().toShort()
          buffer.putShort(value) // Left channel
          buffer.putShort(value) // Right channel
          phase += 2.0 * kotlin.math.PI * frequency / sampleRate
        }
        buffer.flip()
        emit(AudioFrame(buffer, timestamp, sampleRate, numChannels))
        timestamp += frameDurationMs * 1000L
      }
      delay(frameDurationMs.toLong())
    }
  }

  override fun start() {
    isStreaming = true
  }

  override fun stop() {
    isStreaming = false
  }
}
