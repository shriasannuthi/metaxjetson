/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.microwakeword

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads microWakeWord TFLite models from assets and creates [MicroWakeWord] detectors.
 */
object MicroWakeWordModelLoader {
  private const val TAG = "MicroWakeWordModelLoader"

  /**
   * Load a TFLite model from assets into a direct [ByteBuffer] suitable for JNI.
   *
   * Copies bytes into a fresh direct buffer so native code never reads a memory-mapped asset
   * mapping that can be invalid on some Android devices during JNI access.
   */
  fun loadTfliteToDirectByteBuffer(context: Context, assetPath: String): ByteBuffer {
    val bytes = context.assets.open(assetPath).use { it.readBytes() }
    val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
    buffer.put(bytes)
    buffer.flip()
    Log.d(TAG, "Loaded model from assets: $assetPath (${bytes.size} bytes)")
    return buffer
  }

  /** Create a [MicroWakeWord] detector from a model configuration. */
  fun createDetector(context: Context, config: MicroWakeWordModelConfig): MicroWakeWord {
    val modelBuffer = loadTfliteToDirectByteBuffer(context, config.modelAssetPath)
    return MicroWakeWord(
        modelBuffer = modelBuffer,
        featureStepSizeMs = config.micro.featureStepSize,
        probabilityCutoff = config.micro.probabilityCutoff,
        slidingWindowSize = config.micro.slidingWindowSize,
    )
  }
}
