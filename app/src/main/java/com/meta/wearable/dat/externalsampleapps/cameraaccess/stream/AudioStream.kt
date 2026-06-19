/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import kotlinx.coroutines.flow.Flow

/**
 * AudioStream - Capability for receiving audio from Meta glasses.
 * This mimics the SDK's Stream interface for video.
 */
interface AudioStream {
  /**
   * Flow of incoming audio frames.
   */
  val audioStream: Flow<AudioFrame>

  /**
   * Starts audio streaming.
   */
  fun start()

  /**
   * Stops audio streaming.
   */
  fun stop()
}
