/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import java.nio.ByteBuffer

/**
 * AudioFrame - Representation of a single audio frame from the wearable device.
 * Mimics the pattern of VideoFrame but for audio data.
 */
data class AudioFrame(
    val buffer: ByteBuffer,
    val presentationTimeUs: Long,
    val sampleRate: Int = 48000,
    val channelCount: Int = 2
)
