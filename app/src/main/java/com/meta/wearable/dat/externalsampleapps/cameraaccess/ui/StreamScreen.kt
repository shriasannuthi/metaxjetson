/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamScreen - DAT Camera Streaming UI
//
// This composable demonstrates the main streaming UI for DAT camera functionality. It shows how to
// display live video from wearable devices and handle photo capture.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ai.DocumentAnalysisResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
) {
  val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) { streamViewModel.startStream() }

  Box(modifier = modifier.fillMaxSize()) {
    streamUiState.videoFrame?.let { videoFrame ->
      // Use key() to force recomposition when frame counter changes,
      // even if the bitmap reference is the same (due to caching optimization)
      key(streamUiState.videoFrameCount) {
        Image(
            bitmap = videoFrame.asImageBitmap(),
            contentDescription = stringResource(R.string.live_stream),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
      }
    }
    if (streamUiState.streamState == StreamState.STARTING) {
      CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
      )
    }

    CustomerRecognitionOverlay(
        customer = streamUiState.matchedCustomer,
        isScanning = streamUiState.isFaceRecognitionRunning,
        status = streamUiState.faceRecognitionStatus,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 24.dp),
    )

    VoiceCommandOverlay(
        isListening = streamUiState.isVoiceCommandListening,
        isAnalyzing = streamUiState.isDocumentAnalyzing,
        status = streamUiState.voiceCommandStatus,
        modifier =
            Modifier.align(Alignment.TopStart)
                .padding(
                    top = if (streamUiState.audioFrameCount > 0) 128.dp else 80.dp,
                    start = 24.dp,
                ),
    )

    DocumentAnalysisOverlay(
        analysis = streamUiState.documentAnalysis,
        isAnalyzing = streamUiState.isDocumentAnalyzing,
        onDismiss = { streamViewModel.dismissDocumentAnalysis() },
        modifier =
            Modifier.align(Alignment.BottomCenter)
                .padding(start = 24.dp, end = 24.dp, bottom = 104.dp),
    )

    // Audio status overlay
    if (streamUiState.audioFrameCount > 0) {
      Box(
          modifier =
              Modifier.align(Alignment.TopStart)
                  .padding(top = 80.dp, start = 24.dp)
                  .background(Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp))
                  .padding(all = 8.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
              imageVector = Icons.Default.Mic,
              contentDescription = "Audio streaming",
              tint = AppColor.Green,
              modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
              text = "Audio Streaming (${streamUiState.audioFrameCount})",
              color = Color.White,
              style = MaterialTheme.typography.labelSmall,
          )
        }
      }
    }

    Box(modifier = Modifier.fillMaxSize().padding(all = 24.dp)) {
      Row(
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .navigationBarsPadding()
                  .fillMaxWidth()
                  .height(56.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        SwitchButton(
            label = stringResource(R.string.stop_stream_button_title),
            onClick = {
              streamViewModel.stopStream { showingDialog ->
                if (!showingDialog) {
                  wearablesViewModel.navigateToDeviceSelection()
                }
              }
            },
            isDestructive = true,
            modifier = Modifier.weight(1f),
        )

        // Photo capture button
        CaptureButton(
            onClick = { streamViewModel.capturePhoto() },
        )

        ScanDocumentButton(
            onClick = { streamViewModel.scanDocument() },
        )
      }
    }
  }

  streamUiState.capturedPhoto?.let { photo ->
    if (streamUiState.isShareDialogVisible) {
      SharePhotoDialog(
          photo = photo,
          onDismiss = { streamViewModel.hideShareDialog() },
          onShare = { bitmap ->
            streamViewModel.sharePhoto(bitmap)
            streamViewModel.hideShareDialog()
          },
      )
    }
  }

    if (streamUiState.isAudioPlaybackVisible) {
        val recordedAudio = streamViewModel.getRecordedAudio()
        AudioPlaybackDialog(
            audioData = recordedAudio.data,
            sampleRateHz = recordedAudio.sampleRateHz,
            channelCount = recordedAudio.channelCount,
            onDismiss = {
                streamViewModel.hideAudioPlayback()
                wearablesViewModel.navigateToDeviceSelection()
            }
        )
    }
}

@Composable
private fun VoiceCommandOverlay(
    isListening: Boolean,
    isAnalyzing: Boolean,
    status: String?,
    modifier: Modifier = Modifier,
) {
  if (!isListening && status.isNullOrBlank() && !isAnalyzing) return

  Box(
      modifier =
          modifier
              .fillMaxWidth(0.62f)
              .background(Color.Black.copy(alpha = 0.58f), shape = RoundedCornerShape(8.dp))
              .padding(8.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
          imageVector = Icons.Default.Mic,
          contentDescription = "Voice command",
          tint = if (isAnalyzing) AppColor.Yellow else AppColor.Green,
          modifier = Modifier.size(16.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
          text = status ?: "Listening",
          color = Color.White,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun DocumentAnalysisOverlay(
    analysis: DocumentAnalysisResult?,
    isAnalyzing: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
  if (analysis == null && !isAnalyzing) return

  Box(
      modifier =
          modifier
              .fillMaxWidth()
              .background(Color.Black.copy(alpha = 0.76f), shape = RoundedCornerShape(8.dp))
              .padding(12.dp)
  ) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .height(220.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            text = analysis?.json?.stringValue("documentType") ?: "Document scan",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
          Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Dismiss document analysis",
              tint = Color.White,
          )
        }
      }

      if (isAnalyzing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = Color.White,
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
              text = "Analyzing document",
              color = Color.White.copy(alpha = 0.84f),
              style = MaterialTheme.typography.bodySmall,
          )
        }
      } else {
        if (analysis?.json == null) {
          AnalysisText(label = "Raw", value = analysis?.rawJson.orEmpty())
        } else {
          analysis.json.stringValue("summary")?.let {
            AnalysisText(label = "Summary", value = it)
          }
          analysis.json.stringValue("customerRelevance")?.let {
            AnalysisText(label = "Customer", value = it)
          }
          analysis.json.objectValue("extractedFields")?.takeIf { it.size() > 0 }?.let {
            AnalysisText(label = "Fields", value = it.toCompactDisplay())
          }
          analysis.json.arrayValue("riskFlags")?.takeIf { it.size() > 0 }?.let {
            AnalysisText(label = "Flags", value = it.toCompactDisplay())
          }
          analysis.json.arrayValue("recommendedActions")?.takeIf { it.size() > 0 }?.let {
            AnalysisText(label = "Actions", value = it.toCompactDisplay())
          }
        }
      }
    }
  }
}

@Composable
private fun AnalysisText(label: String, value: String) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(
        text = label,
        color = AppColor.Yellow,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = value,
        color = Color.White.copy(alpha = 0.88f),
        style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun CustomerRecognitionOverlay(
    customer: Customer?,
    isScanning: Boolean,
    status: String?,
    modifier: Modifier = Modifier,
) {
  if (customer == null && status.isNullOrBlank() && !isScanning) return

  Box(
      modifier =
          modifier
              .fillMaxWidth(0.78f)
              .background(Color.Black.copy(alpha = 0.68f), shape = RoundedCornerShape(8.dp))
              .padding(12.dp)
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.PersonSearch,
            contentDescription = "Customer recognition",
            tint = if (customer != null) AppColor.Green else AppColor.Yellow,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = customer?.name ?: status ?: "Scanning customer",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      }

      if (customer != null) {
        Text(
            text = customer.profile,
            color = Color.White.copy(alpha = 0.88f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "Last visit: ${customer.lastVisit}",
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      } else if (isScanning) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(
              modifier = Modifier.size(14.dp),
              strokeWidth = 2.dp,
              color = Color.White,
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
              text = "Checking customer database",
              color = Color.White.copy(alpha = 0.82f),
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

private fun JsonObject.stringValue(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

private fun JsonObject.objectValue(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arrayValue(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.toCompactDisplay(): String =
    entrySet().joinToString(separator = "\n") { (key, value) ->
      "$key: ${value.toDisplayValue()}"
    }

private fun JsonArray.toCompactDisplay(): String =
    mapIndexed { index, value -> "${index + 1}. ${value.toDisplayValue()}" }
        .joinToString(separator = "\n")

private fun JsonElement.toDisplayValue(): String =
    when {
      isJsonPrimitive -> asString
      isJsonArray -> asJsonArray.joinToString { it.toDisplayValue() }
      isJsonObject -> asJsonObject.toCompactDisplay()
      else -> toString()
    }
