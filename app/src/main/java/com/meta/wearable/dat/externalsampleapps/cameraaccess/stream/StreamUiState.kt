/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamUiState - DAT Camera Streaming UI State
//
// This data class manages UI state for camera streaming operations using the DAT API.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ai.DocumentAnalysisResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.ConversationTurn
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer

data class StreamUiState(
    val streamState: StreamState = StreamState.STOPPED,
    val videoFrame: Bitmap? = null,
    val videoFrameCount: Int = 0,
    val audioFrameCount: Int = 0,
    val capturedPhoto: Bitmap? = null,
    val isShareDialogVisible: Boolean = false,
    val isAudioPlaybackVisible: Boolean = false,
    val isCapturing: Boolean = false,
    val matchedCustomer: Customer? = null,
    val isFaceRecognitionRunning: Boolean = false,
    val faceRecognitionStatus: String? = null,
    val isVoiceCommandListening: Boolean = false,
    val voiceCommandStatus: String? = null,
    val voiceTranscript: String? = null,
    val isDocumentAnalyzing: Boolean = false,
    val documentAnalysisPartial: String? = null,
    val documentAnalysis: DocumentAnalysisResult? = null,
    val isDocumentSessionActive: Boolean = false,
    val isDocumentQuestionListening: Boolean = false,
    val documentQuestionStatus: String? = null,
    val documentQuestionPartial: String? = null,
    val documentLastQuestion: String? = null,
    val documentAnswer: String? = null,
    val isDocumentAnswering: Boolean = false,
    val documentQuestionError: String? = null,
    val documentConversation: List<ConversationTurn> = emptyList(),
)
