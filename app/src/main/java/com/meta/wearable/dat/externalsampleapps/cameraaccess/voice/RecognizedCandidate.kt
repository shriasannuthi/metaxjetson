/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

data class RecognizedCandidate(
    val text: String,
    val confidence: Float,
)

data class VoiceCommandMatch(
    val intent: VoiceAppIntent,
    val matchedPhrase: String,
    val commandText: String,
    val confidence: Float,
)

sealed class VoiceResolveResult {
  data class Matched(val match: VoiceCommandMatch) : VoiceResolveResult()

  data object NoWakePhrase : VoiceResolveResult()

  data object NoCommand : VoiceResolveResult()

  data object Ambiguous : VoiceResolveResult()

  data object LowConfidence : VoiceResolveResult()
}
