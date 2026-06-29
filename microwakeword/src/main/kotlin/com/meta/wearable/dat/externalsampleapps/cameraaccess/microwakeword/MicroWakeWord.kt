/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.microwakeword

import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Wake word detector combining audio feature extraction, TFLite Micro inference,
 * and sliding window detection — all in a single C++ engine.
 *
 * Audio must be 16-bit PCM mono at 16 kHz. Other sample rates will produce
 * incorrect feature extraction and unreliable detection.
 *
 * **Thread Safety**: This class is NOT thread-safe. Each instance maintains
 * internal state, so each thread should use its own instance.
 */
class MicroWakeWord(
    modelBuffer: ByteBuffer,
    featureStepSizeMs: Int,
    probabilityCutoff: Float,
    slidingWindowSize: Int,
) : Closeable {

  private var nativeHandle: Long = 0

  init {
    require(featureStepSizeMs > 0) { "featureStepSizeMs must be positive, was $featureStepSizeMs" }
    require(slidingWindowSize > 0) { "slidingWindowSize must be positive, was $slidingWindowSize" }
    require(probabilityCutoff in 0f..1f) {
      "probabilityCutoff must be in [0.0, 1.0], was $probabilityCutoff"
    }
    require(modelBuffer.isDirect) { "modelBuffer must be a direct ByteBuffer for JNI access" }
    ensureLibraryLoaded()
    nativeHandle =
        nativeCreate(
            modelBuffer,
            DEFAULT_SAMPLE_RATE,
            featureStepSizeMs,
            probabilityCutoff,
            slidingWindowSize,
        )
    if (nativeHandle == 0L) {
      throw IllegalStateException("Failed to create native MicroWakeWord engine")
    }
    Log.d(TAG, "MicroWakeWord engine created with handle: $nativeHandle")
  }

  /**
   * Feed audio samples and check for wake word detection.
   *
   * @param samples 16-bit PCM mono audio samples at 16 kHz
   * @return true if wake word was detected in this or recent frames
   */
  fun processAudio(samples: ShortArray): Boolean {
    check(nativeHandle != 0L) { "MicroWakeWord has been closed" }
    return nativeProcessAudio(nativeHandle, samples)
  }

  /** Reset all internal state (frontend, feature buffer, detection state). */
  fun reset() {
    check(nativeHandle != 0L) { "MicroWakeWord has been closed" }
    nativeReset(nativeHandle)
    Log.d(TAG, "MicroWakeWord reset")
  }

  /** Release native resources. After this call, the instance cannot be used. */
  override fun close() {
    if (nativeHandle != 0L) {
      Log.d(TAG, "Closing MicroWakeWord engine with handle: $nativeHandle")
      nativeDestroy(nativeHandle)
      nativeHandle = 0
    }
  }

  protected fun finalize() {
    close()
  }

  companion object {
    private const val TAG = "MicroWakeWord"
    const val DEFAULT_SAMPLE_RATE = 16000

    private var libraryLoaded = false

    fun ensureLibraryLoaded() {
      if (!libraryLoaded) {
        System.loadLibrary("microwakeword")
        libraryLoaded = true
        Log.d(TAG, "Loaded microwakeword native library")
      }
    }

    @JvmStatic
    external fun nativeCreate(
        modelBuffer: ByteBuffer,
        sampleRate: Int,
        featureStepSizeMs: Int,
        probabilityCutoff: Float,
        slidingWindowSize: Int,
    ): Long

    @JvmStatic external fun nativeProcessAudio(handle: Long, samples: ShortArray): Boolean

    @JvmStatic external fun nativeReset(handle: Long)

    @JvmStatic external fun nativeDestroy(handle: Long)
  }
}
