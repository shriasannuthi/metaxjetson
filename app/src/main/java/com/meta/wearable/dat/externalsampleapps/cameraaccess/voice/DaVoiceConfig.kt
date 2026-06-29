/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig

object DaVoiceConfig {
  const val INSTANCE_ID = "wf_meta_wake"
  const val DEFAULT_MODEL = "hey_meta.dm"
  const val DEFAULT_THRESHOLD = 0.99f
  const val BUFFER_COUNT = 4
  const val MS_BETWEEN_CALLBACKS = 1_500L
  const val COMMAND_LISTEN_TIMEOUT_MS = 12_000L

  val licenseKey: String = BuildConfig.DAVOICE_LICENSE_KEY
  val wakeModelAsset: String = BuildConfig.DAVOICE_WAKE_MODEL
  val detectionThreshold: Float = BuildConfig.DAVOICE_WAKE_THRESHOLD

  fun isConfigured(): Boolean = licenseKey.isNotBlank() && wakeModelAsset.isNotBlank()
}
