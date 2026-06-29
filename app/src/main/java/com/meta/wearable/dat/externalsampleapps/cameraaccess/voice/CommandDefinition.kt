/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

data class CommandDefinition(
    val intent: VoiceAppIntent,
    val phrases: List<String>,
    val spokenResponse: String?,
    val allowedStates: Set<VoiceSessionState> = VoiceSessionState.WAKE_ELIGIBLE_STATES,
)
