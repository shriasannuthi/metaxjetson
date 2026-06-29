/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

interface VoiceCommandHandler {
  fun onScanDocument()

  fun onCapturePhoto()

  fun onCancelVoiceCommand()

  fun getLastSpokenResponse(): String?
}

interface VoiceDictationCallbacks {
  fun onReady()

  fun onPartialTranscript(text: String)

  fun onFinalTranscript(text: String)

  fun onError(message: String)
}

interface VoiceDictationPort {
  fun startDictation(callbacks: VoiceDictationCallbacks, resumeWakeAfter: Boolean = false)

  fun stopDictation(cancel: Boolean)
}
