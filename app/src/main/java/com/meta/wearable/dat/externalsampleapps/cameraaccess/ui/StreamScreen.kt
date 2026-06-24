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

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ai.DocumentAnalysisResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.AssistantUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.AssistantViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.ConversationTurn
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.DocumentScanPhase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import kotlinx.coroutines.delay

private enum class StreamCanvasMode {
  IMMERSIVE,
  FLOATING,
  SPLIT,
}

private enum class AssistantDock {
  BOTTOM,
  LEFT,
  RIGHT,
  FLOATING,
}

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
  val assistantViewModel: AssistantViewModel =
      viewModel(
          factory =
              AssistantViewModel.Factory(
                  application = (LocalActivity.current as ComponentActivity).application,
              ),
      )
  val assistantUiState by assistantViewModel.uiState.collectAsStateWithLifecycle()
  var isAssistantVisible by remember { mutableStateOf(false) }
  var didPauseVoiceCommandsForAssistant by remember { mutableStateOf(false) }
  var lastAutoListenedCustomerId by remember { mutableStateOf<String?>(null) }
  var canvasMode by remember { mutableStateOf(StreamCanvasMode.IMMERSIVE) }
  var assistantDock by remember { mutableStateOf(AssistantDock.BOTTOM) }
  var isStreamHudExpanded by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) { streamViewModel.startStream() }
  LaunchedEffect(streamUiState.matchedCustomer?.id) {
    streamUiState.matchedCustomer?.let { customer ->
      assistantViewModel.selectCustomer(customer)
      if (lastAutoListenedCustomerId != customer.id) {
        lastAutoListenedCustomerId = customer.id
        isAssistantVisible = true
        streamViewModel.pauseVoiceCommandsForAssistant()
        didPauseVoiceCommandsForAssistant = true
        delay(250)
        assistantViewModel.startListening()
      }
    }
  }
  LaunchedEffect(assistantUiState.isListening, assistantUiState.isAnswering) {
    val isCustomerQnaOngoing = assistantUiState.isListening || assistantUiState.isAnswering
    if (isCustomerQnaOngoing && !didPauseVoiceCommandsForAssistant) {
      streamViewModel.pauseVoiceCommandsForAssistant()
      didPauseVoiceCommandsForAssistant = true
    } else if (!isCustomerQnaOngoing && didPauseVoiceCommandsForAssistant) {
      streamViewModel.resumeVoiceCommandsAfterAssistant()
      didPauseVoiceCommandsForAssistant = false
    }
  }

  Box(
      modifier =
          modifier
              .fillMaxSize()
              .background(
                  Brush.verticalGradient(
                      listOf(Color(0xFF2A0808), AppColor.WfDeepRed, Color(0xFF1F1F1F)),
                  ),
              )
  ) {
    StreamCanvas(
        videoFrame = streamUiState.videoFrame,
        frameCount = streamUiState.videoFrameCount,
        canvasMode = canvasMode,
        modifier = Modifier.fillMaxSize(),
    )
    if (streamUiState.streamState == StreamState.STARTING) {
      CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
      )
    }

    StreamHud(
        customer = streamUiState.matchedCustomer,
        isScanning = streamUiState.isFaceRecognitionRunning,
        recognitionStatus = streamUiState.faceRecognitionStatus,
        audioFrameCount = streamUiState.audioFrameCount,
        isListening = streamUiState.isVoiceCommandListening,
        isAnalyzing = streamUiState.isDocumentAnalyzing,
        voiceStatus = streamUiState.voiceCommandStatus,
        transcript = streamUiState.voiceTranscript,
        isExpanded = isStreamHudExpanded,
        onToggleExpanded = { isStreamHudExpanded = !isStreamHudExpanded },
        modifier = Modifier.align(Alignment.TopStart).padding(top = 54.dp, start = 16.dp),
    )

    WfBrandMark(
        size = 44.dp,
        showProductName = false,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 54.dp, end = 16.dp),
    )

    DocumentSessionOverlay(
        analysis = streamUiState.documentAnalysis,
        isAnalyzing = streamUiState.isDocumentAnalyzing,
        scanPhase = streamUiState.documentScanPhase,
        partialText = streamUiState.documentAnalysisPartial,
        scanStatus = streamUiState.documentQuestionStatus,
        isSessionActive = streamUiState.isDocumentSessionActive,
        isQuestionListening = streamUiState.isDocumentQuestionListening,
        questionStatus = streamUiState.documentQuestionStatus,
        partialQuestion = streamUiState.documentQuestionPartial,
        lastQuestion = streamUiState.documentLastQuestion,
        answer = streamUiState.documentAnswer,
        isAnswering = streamUiState.isDocumentAnswering,
        error = streamUiState.documentQuestionError,
        conversation = streamUiState.documentConversation,
        onStartListening = { streamViewModel.startDocumentQuestionListening() },
        onStopListeningAndUsePartial = { streamViewModel.stopDocumentQuestionListeningAndUsePartial() },
        onCancelListening = { streamViewModel.cancelDocumentQuestionListening() },
        onRetryScan = { streamViewModel.retryDocumentScan() },
        onEndSession = { streamViewModel.endDocumentSession() },
        modifier =
            Modifier.align(Alignment.Center)
                .padding(start = 16.dp, end = 16.dp, top = 52.dp, bottom = 104.dp),
    )

    Box(modifier = Modifier.fillMaxSize().padding(all = 24.dp)) {
      StreamWindowControls(
          canvasMode = canvasMode,
          assistantDock = assistantDock,
          onCanvasModeChanged = { canvasMode = it },
          onAssistantDockChanged = { assistantDock = it },
          onOpenAssistant = { isAssistantVisible = true },
          modifier =
              Modifier.align(Alignment.BottomEnd)
                  .navigationBarsPadding()
                  .padding(bottom = 78.dp),
      )

      Row(
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .navigationBarsPadding()
                  .fillMaxWidth()
                  .height(64.dp)
                  .background(AppColor.StreamGlass, RoundedCornerShape(28.dp))
                  .padding(6.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
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

        VoiceTestButton(
            onClick = { streamViewModel.listenForVoiceTest() },
        )

        ScanDocumentButton(
            onClick = { streamViewModel.scanDocument() },
        )
      }
    }

    if (isAssistantVisible) {
      RmAssistantOverlay(
          state = assistantUiState,
          onDismiss = {
            assistantViewModel.cancelListening()
            if (didPauseVoiceCommandsForAssistant && !assistantUiState.isAnswering) {
              streamViewModel.resumeVoiceCommandsAfterAssistant()
              didPauseVoiceCommandsForAssistant = false
            }
            isAssistantVisible = false
          },
          onCustomerOffset = assistantViewModel::selectCustomerOffset,
          onStartListening = {
            streamViewModel.pauseVoiceCommandsForAssistant()
            didPauseVoiceCommandsForAssistant = true
            assistantViewModel.startListening()
          },
          onStopListeningAndUsePartial = assistantViewModel::stopListeningAndUsePartial,
          onCancelListening = {
            assistantViewModel.cancelListening()
            if (didPauseVoiceCommandsForAssistant) {
              streamViewModel.resumeVoiceCommandsAfterAssistant()
              didPauseVoiceCommandsForAssistant = false
            }
          },
          onEndSession = {
            assistantViewModel.endSession()
            if (didPauseVoiceCommandsForAssistant) {
              streamViewModel.resumeVoiceCommandsAfterAssistant()
              didPauseVoiceCommandsForAssistant = false
            }
          },
          modifier = Modifier.align(assistantDock.alignment()).then(assistantDock.overlayModifier()),
      )
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
private fun StreamCanvas(
    videoFrame: Bitmap?,
    frameCount: Int,
    canvasMode: StreamCanvasMode,
    modifier: Modifier = Modifier,
) {
  Box(modifier = modifier) {
    when (canvasMode) {
      StreamCanvasMode.IMMERSIVE -> {
        videoFrame?.let { frame ->
          key(frameCount) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = stringResource(R.string.live_stream),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
          }
        }
      }
      StreamCanvasMode.FLOATING -> {
        StreamBackdropLabel(
            title = "Floating camera window",
            subtitle = "Drag the live window while RM tools stay visible",
            modifier = Modifier.align(Alignment.Center),
        )
        MovableVideoWindow(
            videoFrame = videoFrame,
            frameCount = frameCount,
            modifier = Modifier.align(Alignment.TopStart),
        )
      }
      StreamCanvasMode.SPLIT -> {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Surface(
              modifier = Modifier.fillMaxWidth().weight(1f),
              shape = RoundedCornerShape(22.dp),
              color = Color.Black.copy(alpha = 0.56f),
          ) {
            Box {
              videoFrame?.let { frame ->
                key(frameCount) {
                  Image(
                      bitmap = frame.asImageBitmap(),
                      contentDescription = stringResource(R.string.live_stream),
                      modifier = Modifier.fillMaxSize(),
                      contentScale = ContentScale.Crop,
                  )
                }
              }
              Text(
                  text = "Live wearable view",
                  color = Color.White,
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.SemiBold,
                  modifier =
                      Modifier.align(Alignment.TopStart)
                          .padding(14.dp)
                          .background(AppColor.Glass, RoundedCornerShape(16.dp))
                          .padding(horizontal = 12.dp, vertical = 7.dp),
              )
            }
          }
          Surface(
              modifier = Modifier.fillMaxWidth().height(86.dp),
              shape = RoundedCornerShape(20.dp),
              color = Color.Black.copy(alpha = 0.42f),
          ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              MiniMetric("Scene", "Live")
              MiniMetric("Window", "Split")
              MiniMetric("Focus", "RM assist")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MovableVideoWindow(
    videoFrame: Bitmap?,
    frameCount: Int,
    modifier: Modifier = Modifier,
) {
  var dragOffset by remember { mutableStateOf(Offset(22f, 88f)) }

  Surface(
      modifier =
          modifier
              .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
              .size(width = 252.dp, height = 184.dp)
              .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                  change.consume()
                  dragOffset += dragAmount
                }
              }
              .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(22.dp)),
      shape = RoundedCornerShape(22.dp),
      color = Color.Black.copy(alpha = 0.70f),
  ) {
    Box {
      videoFrame?.let { frame ->
        key(frameCount) {
          Image(
              bitmap = frame.asImageBitmap(),
              contentDescription = stringResource(R.string.live_stream),
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop,
          )
        }
      }
      Row(
          modifier =
              Modifier.align(Alignment.TopStart)
                  .padding(10.dp)
                  .background(AppColor.Glass, RoundedCornerShape(14.dp))
                  .padding(horizontal = 9.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Default.OpenInFull, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(
            text = "Drag",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

@Composable
private fun StreamBackdropLabel(title: String, subtitle: String, modifier: Modifier = Modifier) {
  Column(
      modifier = modifier.padding(horizontal = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
        text = title,
        color = Color.White.copy(alpha = 0.88f),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = subtitle,
        color = Color.White.copy(alpha = 0.58f),
        style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun MiniMetric(label: String, value: String, modifier: Modifier = Modifier) {
  Surface(
      modifier = modifier,
      shape = RoundedCornerShape(16.dp),
      color = Color.White.copy(alpha = 0.08f),
  ) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
      Text(text = label, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.labelSmall)
      Text(text = value, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun StreamWindowControls(
    canvasMode: StreamCanvasMode,
    assistantDock: AssistantDock,
    onCanvasModeChanged: (StreamCanvasMode) -> Unit,
    onAssistantDockChanged: (AssistantDock) -> Unit,
    onOpenAssistant: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier = modifier,
      horizontalAlignment = Alignment.End,
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    FloatingActionButton(
        onClick = onOpenAssistant,
        containerColor = AppColor.WfRed,
        contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
    ) {
      Icon(
          imageVector = Icons.Default.SupportAgent,
          contentDescription = "Open RM voice assistant",
      )
    }
    Surface(shape = RoundedCornerShape(28.dp), color = AppColor.StreamGlass) {
      Row(modifier = Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { onCanvasModeChanged(canvasMode.next()) }, modifier = Modifier.size(42.dp)) {
          Icon(
              imageVector =
                  when (canvasMode) {
                    StreamCanvasMode.IMMERSIVE -> Icons.Default.Fullscreen
                    StreamCanvasMode.FLOATING -> Icons.Default.PictureInPictureAlt
                    StreamCanvasMode.SPLIT -> Icons.Default.GridView
                  },
              contentDescription = "Change stream window layout",
              tint = Color.White,
          )
        }
        IconButton(onClick = { onAssistantDockChanged(assistantDock.next()) }, modifier = Modifier.size(42.dp)) {
          Icon(
              imageVector = Icons.Default.PushPin,
              contentDescription = "Move RM assistant panel",
              tint = Color.White,
          )
        }
      }
    }
  }
}

private fun StreamCanvasMode.next(): StreamCanvasMode =
    when (this) {
      StreamCanvasMode.IMMERSIVE -> StreamCanvasMode.FLOATING
      StreamCanvasMode.FLOATING -> StreamCanvasMode.SPLIT
      StreamCanvasMode.SPLIT -> StreamCanvasMode.IMMERSIVE
    }

private fun AssistantDock.next(): AssistantDock =
    when (this) {
      AssistantDock.BOTTOM -> AssistantDock.RIGHT
      AssistantDock.RIGHT -> AssistantDock.LEFT
      AssistantDock.LEFT -> AssistantDock.FLOATING
      AssistantDock.FLOATING -> AssistantDock.BOTTOM
    }

private fun AssistantDock.overlayModifier(): Modifier =
    when (this) {
      AssistantDock.BOTTOM ->
          Modifier.fillMaxWidth()
              .padding(start = 16.dp, end = 16.dp, bottom = 112.dp)
              .navigationBarsPadding()
      AssistantDock.LEFT ->
          Modifier.fillMaxWidth(0.88f)
              .padding(start = 14.dp, top = 92.dp, bottom = 104.dp)
      AssistantDock.RIGHT ->
          Modifier.fillMaxWidth(0.88f)
              .padding(end = 14.dp, top = 92.dp, bottom = 104.dp)
      AssistantDock.FLOATING ->
          Modifier.fillMaxWidth(0.86f)
              .padding(top = 118.dp, start = 24.dp)
    }

private fun AssistantDock.alignment(): Alignment =
    when (this) {
      AssistantDock.BOTTOM -> Alignment.BottomCenter
      AssistantDock.LEFT -> Alignment.CenterStart
      AssistantDock.RIGHT -> Alignment.CenterEnd
      AssistantDock.FLOATING -> Alignment.TopStart
    }

@Composable
private fun StreamHud(
    customer: Customer?,
    isScanning: Boolean,
    recognitionStatus: String?,
    audioFrameCount: Int,
    isListening: Boolean,
    isAnalyzing: Boolean,
    voiceStatus: String?,
    transcript: String?,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val hasVoice = isListening || !voiceStatus.isNullOrBlank() || !transcript.isNullOrBlank() || isAnalyzing
  val hasRecognition = customer != null || !recognitionStatus.isNullOrBlank() || isScanning
  if (audioFrameCount <= 0 && !hasVoice && !hasRecognition) return

  Box(modifier = modifier) {
    if (!isExpanded) {
      Surface(
          modifier = Modifier.size(42.dp).clickable(onClick = onToggleExpanded),
          shape = RoundedCornerShape(21.dp),
          color = AppColor.Glass,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
              imageVector = if (customer != null) Icons.Default.PersonSearch else Icons.Default.Mic,
              contentDescription = "Expand stream status",
              tint = if (customer != null) AppColor.Green else AppColor.Yellow,
              modifier = Modifier.size(18.dp),
          )
        }
      }
    } else {
      Surface(
          modifier = Modifier.width(280.dp).clickable(onClick = onToggleExpanded),
          shape = RoundedCornerShape(18.dp),
          color = AppColor.Glass,
      ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (hasRecognition) {
            CustomerRecognitionOverlay(
                customer = customer,
                isScanning = isScanning,
                status = recognitionStatus,
                modifier = Modifier.fillMaxWidth(),
            )
          }

          if (audioFrameCount > 0 || hasVoice) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              if (audioFrameCount > 0) {
                StatusRow(
                    icon = Icons.Default.Mic,
                    title = "Audio",
                    value = "$audioFrameCount frames",
                    tint = AppColor.Green,
                )
              }
              if (hasVoice) {
                StatusRow(
                    icon = Icons.Default.Mic,
                    title = voiceStatus ?: "Hey Meta",
                    value = transcript?.takeIf { it.isNotBlank() } ?: "Listening",
                    tint = if (isAnalyzing) AppColor.Yellow else AppColor.Green,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun StatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
  Surface(
      modifier = modifier,
      shape = RoundedCornerShape(16.dp),
      color = Color.White.copy(alpha = 0.08f),
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
          imageVector = icon,
          contentDescription = null,
          tint = tint,
          modifier = Modifier.size(16.dp),
      )
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            color = Color.White.copy(alpha = 0.68f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun RmAssistantOverlay(
    state: AssistantUiState,
    onDismiss: () -> Unit,
    onCustomerOffset: (Int) -> Unit,
    onStartListening: () -> Unit,
    onStopListeningAndUsePartial: () -> Unit,
    onCancelListening: () -> Unit,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val customer = state.customer

  Surface(
      modifier =
          modifier
              .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(26.dp)),
      shape = RoundedCornerShape(26.dp),
      color = Color(0xF214171B),
      tonalElevation = 8.dp,
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Surface(shape = RoundedCornerShape(16.dp), color = AppColor.Green.copy(alpha = 0.18f)) {
            Icon(
                imageVector = Icons.Default.SupportAgent,
                contentDescription = "RM assistant",
                tint = AppColor.Green,
                modifier = Modifier.padding(7.dp).size(14.dp),
            )
          }
          Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = "RM Assistant",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
          }
        }
        ListeningWave(isListening = state.isListening)
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
          Icon(Icons.Default.Close, contentDescription = "Close RM assistant", tint = Color.White, modifier = Modifier.size(18.dp))
        }
      }

      CustomerSnapshot(customer = customer, onCustomerOffset = onCustomerOffset)

      AssistantActionBar(
          state = state,
          hasCustomer = customer != null,
          onStartListening = onStartListening,
          onStopListeningAndUsePartial = onStopListeningAndUsePartial,
          onCancelListening = onCancelListening,
          onEndSession = onEndSession,
      )

      AssistantResponsePanel(answer = state.answer, isAnswering = state.isAnswering)

      if (state.conversation.isNotEmpty()) {
        ConversationStrip(turns = state.conversation.takeLast(2))
      }

      state.error?.takeIf { it.isNotBlank() }?.let {
        Text(
            text = it,
            color = AppColor.Yellow,
            style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

@Composable
private fun CustomerSnapshot(customer: Customer?, onCustomerOffset: (Int) -> Unit) {
  var swipeDelta by remember { mutableStateOf(0f) }
  Surface(
      modifier =
          Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = {
                  when {
                    swipeDelta > 80f -> onCustomerOffset(-1)
                    swipeDelta < -80f -> onCustomerOffset(1)
                  }
                  swipeDelta = 0f
                },
                onDragCancel = { swipeDelta = 0f },
            ) { change, dragAmount ->
              change.consume()
              swipeDelta += dragAmount.x
            }
          },
      shape = RoundedCornerShape(16.dp),
      color = Color.White.copy(alpha = 0.07f),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.Top,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(
              text = customer?.name ?: "No customer locked",
              color = Color.White,
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
          Text(
              text = customer?.let { "${it.id} - ${it.phone}" } ?: "Use face match or swipe customer card",
              color = Color.White.copy(alpha = 0.66f),
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
        Surface(shape = RoundedCornerShape(14.dp), color = AppColor.Green.copy(alpha = 0.16f)) {
          Text(
              text = if (customer != null) "Matched" else "Standby",
              color = if (customer != null) AppColor.Green else AppColor.Yellow,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      }

      if (customer != null) {
        Text(
            text = customer.profile,
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "Swipe customer card to switch",
            color = Color.White.copy(alpha = 0.46f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          customer.accounts.firstOrNull()?.let { account ->
            InsightTile(
                icon = Icons.Default.AccountBalance,
                label = account.type.ifBlank { "Account" },
                value = listOf(account.balance, account.status).filter { it.isNotBlank() }.joinToString(" - "),
                modifier = Modifier.weight(1f),
            )
          }
          InsightTile(
              icon = Icons.Default.History,
              label = "Last visit",
              value = customer.lastVisit,
              modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

@Composable
private fun AssistantActionBar(
    state: AssistantUiState,
    hasCustomer: Boolean,
    onStartListening: () -> Unit,
    onStopListeningAndUsePartial: () -> Unit,
    onCancelListening: () -> Unit,
    onEndSession: () -> Unit,
) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Button(
        onClick = onStartListening,
        enabled = !state.isListening && !state.isAnswering && hasCustomer,
        modifier = Modifier.weight(1f),
    ) {
      Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(14.dp))
      Spacer(modifier = Modifier.width(4.dp))
      Text("Ask")
    }
    if (state.isListening) {
      TextButton(onClick = onStopListeningAndUsePartial) {
        Text("Use", maxLines = 1, style = MaterialTheme.typography.labelSmall)
      }
      TextButton(onClick = onCancelListening) {
        Text("Cancel", maxLines = 1, style = MaterialTheme.typography.labelSmall)
      }
    } else {
      OutlinedButton(onClick = onEndSession) {
        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Clear")
      }
    }
  }
}

@Composable
private fun AssistantResponsePanel(answer: String?, isAnswering: Boolean) {
  Surface(
      modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 360.dp),
      shape = RoundedCornerShape(22.dp),
      color = Color.White.copy(alpha = 0.11f),
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
          text = "Recommended response",
          color = AppColor.Yellow,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
      )
      when {
        isAnswering -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Preparing RM guidance...",
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
        !answer.isNullOrBlank() -> {
          Text(
              text = answer.toMarkdownAnnotatedString(),
              color = Color.White.copy(alpha = 0.94f),
              style = MaterialTheme.typography.bodyMedium,
          )
        }
        else -> {
          Text(
              text = "Ask a customer question to generate the response here.",
              color = Color.White.copy(alpha = 0.64f),
              style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }
  }
}

@Composable
private fun ListeningWave(isListening: Boolean) {
  if (!isListening) return

  val transition = rememberInfiniteTransition(label = "listening_wave")
  val phase by transition.animateFloat(
      initialValue = 0f,
      targetValue = (2f * PI).toFloat(),
      animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1100, easing = LinearEasing)),
      label = "wave_phase",
  )

  Canvas(modifier = Modifier.size(width = 58.dp, height = 24.dp)) {
    val centerY = size.height / 2f
    val step = size.width / 22f
    var previousX = 0f
    var previousY = centerY
    for (index in 1..22) {
      val x = index * step
      val amplitude = size.height * 0.24f
      val y = centerY + sin((index * 0.75f + phase).toDouble()).toFloat() * amplitude
      drawLine(
          color = AppColor.Green.copy(alpha = 0.85f),
          start = Offset(previousX, previousY),
          end = Offset(x, y),
          strokeWidth = 3f,
      )
      previousX = x
      previousY = y
    }
  }
}

@Composable
private fun InsightTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.18f)) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(icon, contentDescription = null, tint = AppColor.Yellow, modifier = Modifier.size(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(text = label, color = Color.White.copy(alpha = 0.58f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Text(text = value.ifBlank { "Review" }, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
private fun ConversationStrip(turns: List<ConversationTurn>) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
        text = "Session history",
        color = Color.White.copy(alpha = 0.62f),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
    turns.forEach { turn ->
      Text(
          text = "${turn.role.name.lowercase().replaceFirstChar { it.titlecase() }}: ${turn.text}",
          color = Color.White.copy(alpha = 0.74f),
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun VoiceCommandOverlay(
    isListening: Boolean,
    isAnalyzing: Boolean,
    status: String?,
    transcript: String?,
    modifier: Modifier = Modifier,
) {
  if (!isListening && status.isNullOrBlank() && transcript.isNullOrBlank() && !isAnalyzing) return

  Box(
      modifier =
          modifier
              .fillMaxWidth(0.78f)
              .background(Color.Black.copy(alpha = 0.58f), shape = RoundedCornerShape(8.dp))
              .padding(8.dp)
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
      }
      transcript?.takeIf { it.isNotBlank() }?.let {
        Text(
            text = "Transcript: $it",
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun DocumentSessionOverlay(
    analysis: DocumentAnalysisResult?,
    isAnalyzing: Boolean,
    scanPhase: DocumentScanPhase,
    partialText: String?,
    scanStatus: String?,
    isSessionActive: Boolean,
    isQuestionListening: Boolean,
    questionStatus: String?,
    partialQuestion: String?,
    lastQuestion: String?,
    answer: String?,
    isAnswering: Boolean,
    error: String?,
    conversation: List<ConversationTurn>,
    onStartListening: () -> Unit,
    onStopListeningAndUsePartial: () -> Unit,
    onCancelListening: () -> Unit,
    onRetryScan: () -> Unit,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
  if (!isSessionActive && analysis == null && !isAnalyzing) return

  Column(
      modifier = modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    DocumentAnalysisPanel(
        analysis = analysis,
        isAnalyzing = isAnalyzing,
        scanPhase = scanPhase,
        partialText = partialText,
        scanStatus = scanStatus,
        modifier = Modifier.fillMaxWidth().weight(1f),
    )
    DocumentQuestionPanel(
        analysisReady = analysis != null && !isAnalyzing,
        scanFailed = scanPhase == DocumentScanPhase.FAILED,
        isListening = isQuestionListening,
        status = questionStatus,
        partialQuestion = partialQuestion,
        lastQuestion = lastQuestion,
        answer = answer,
        isAnswering = isAnswering,
        error = error,
        conversation = conversation,
        onStartListening = onStartListening,
        onStopListeningAndUsePartial = onStopListeningAndUsePartial,
        onCancelListening = onCancelListening,
        onRetryScan = onRetryScan,
        onEndSession = onEndSession,
        modifier = Modifier.fillMaxWidth().weight(1f),
    )
  }
}

@Composable
private fun DocumentAnalysisPanel(
    analysis: DocumentAnalysisResult?,
    isAnalyzing: Boolean,
    scanPhase: DocumentScanPhase,
    partialText: String?,
    scanStatus: String?,
    modifier: Modifier = Modifier,
) {
  Surface(
      modifier = modifier,
      shape = RoundedCornerShape(10.dp),
      color = Color.Black.copy(alpha = 0.76f),
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
          text = analysis?.json?.stringValue("documentType") ?: "Document scan",
          color = Color.White,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )

      if (scanPhase == DocumentScanPhase.FAILED) {
        AnalysisText(label = "Status", value = scanStatus ?: "Scan failed")
      } else if (isAnalyzing) {
        partialText?.toLiveAnalysisText()?.takeIf { it.isNotBlank() }?.let {
          AnalysisText(label = scanStatus ?: "Live", value = it)
        } ?: run {
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = scanStatus ?: "Analyzing document",
                color = Color.White.copy(alpha = 0.84f),
                style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      } else if (analysis?.json == null) {
        AnalysisText(label = "Raw", value = analysis?.rawJson.orEmpty())
      } else {
        analysis.json.stringValue("summary")?.let { AnalysisText(label = "Summary", value = it) }
        val explanation =
            analysis.json.stringValue("explanation") ?: analysis.json.stringValue("customerRelevance")
        explanation?.let { AnalysisText(label = "Explanation", value = it) }
        analysis.json.get("extractedFields")
            ?.takeIf { !it.isJsonNull }
            ?.toDisplayValue()
            ?.takeIf { it.isNotBlank() }
            ?.let { AnalysisText(label = "Fields", value = it) }
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

@Composable
private fun DocumentQuestionPanel(
    analysisReady: Boolean,
    scanFailed: Boolean,
    isListening: Boolean,
    status: String?,
    partialQuestion: String?,
    lastQuestion: String?,
    answer: String?,
    isAnswering: Boolean,
    error: String?,
    conversation: List<ConversationTurn>,
    onStartListening: () -> Unit,
    onStopListeningAndUsePartial: () -> Unit,
    onCancelListening: () -> Unit,
    onRetryScan: () -> Unit,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Surface(
      modifier = modifier,
      shape = RoundedCornerShape(10.dp),
      color = Color.Black.copy(alpha = 0.78f),
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            text = "Ask about this document",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = onEndSession) {
          Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("End Session", maxLines = 1)
        }
      }

      DocumentQuestionActions(
          analysisReady = analysisReady,
          scanFailed = scanFailed,
          isListening = isListening,
          isAnswering = isAnswering,
          onStartListening = onStartListening,
          onStopListeningAndUsePartial = onStopListeningAndUsePartial,
          onCancelListening = onCancelListening,
          onRetryScan = onRetryScan,
      )

      Column(
          modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        when {
          scanFailed -> {
            Text(
                text = error ?: "Could not read document. Retry the scan or end this session.",
                color = AppColor.Red,
                style = MaterialTheme.typography.bodySmall,
            )
          }
          !analysisReady -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(
                  modifier = Modifier.size(16.dp),
                  strokeWidth = 2.dp,
                  color = Color.White,
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                  text = "Q&A will be ready after the scan finishes.",
                  color = Color.White.copy(alpha = 0.74f),
                  style = MaterialTheme.typography.bodySmall,
              )
            }
          }
          isAnswering -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(
                  modifier = Modifier.size(16.dp),
                  strokeWidth = 2.dp,
                  color = Color.White,
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                  text = "Answering from this document...",
                  color = Color.White.copy(alpha = 0.84f),
                  style = MaterialTheme.typography.bodySmall,
              )
            }
          }
          !answer.isNullOrBlank() -> {
            AnalysisText(label = "Answer", value = answer)
          }
          else -> {
            Text(
                text = status ?: "Tap Ask and speak a question about the scanned document.",
                color = Color.White.copy(alpha = 0.70f),
                style = MaterialTheme.typography.bodySmall,
            )
          }
        }

        partialQuestion?.takeIf { it.isNotBlank() }?.let {
          AnalysisText(label = if (isListening) "Listening" else "Question", value = it)
        }
        lastQuestion?.takeIf { it.isNotBlank() && it != partialQuestion }?.let {
          AnalysisText(label = "Last question", value = it)
        }
        error?.takeIf { it.isNotBlank() && !scanFailed }?.let {
          Text(text = it, color = AppColor.Red, style = MaterialTheme.typography.bodySmall)
        }
        DocumentConversationStrip(turns = conversation.takeLast(4))
      }
    }
  }
}

@Composable
private fun DocumentQuestionActions(
    analysisReady: Boolean,
    scanFailed: Boolean,
    isListening: Boolean,
    isAnswering: Boolean,
    onStartListening: () -> Unit,
    onStopListeningAndUsePartial: () -> Unit,
    onCancelListening: () -> Unit,
    onRetryScan: () -> Unit,
) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (scanFailed) {
      Button(
          onClick = onRetryScan,
          modifier = Modifier.weight(1f),
      ) {
        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Retry scan")
      }
    } else {
      Button(
          onClick = onStartListening,
          enabled = analysisReady && !isListening && !isAnswering,
          modifier = Modifier.weight(1f),
      ) {
        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Ask")
      }
    }
    if (isListening) {
      TextButton(onClick = onStopListeningAndUsePartial) {
        Text("Use", maxLines = 1, style = MaterialTheme.typography.labelSmall)
      }
      TextButton(onClick = onCancelListening) {
        Text("Cancel", maxLines = 1, style = MaterialTheme.typography.labelSmall)
      }
    }
  }
}

@Composable
private fun DocumentConversationStrip(turns: List<ConversationTurn>) {
  if (turns.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
        text = "Document Q&A history",
        color = Color.White.copy(alpha = 0.58f),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
    turns.forEach { turn ->
      val label = if (turn.role.name == "CUSTOMER") "Question" else "Answer"
      Text(
          text = "$label: ${turn.text}",
          color = Color.White.copy(alpha = 0.72f),
          style = MaterialTheme.typography.labelSmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
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
              .fillMaxWidth()
              .background(Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(16.dp))
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
            text = "${customer.id} - ${customer.phone}",
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = customer.profile,
            color = Color.White.copy(alpha = 0.88f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        customer.accounts.firstOrNull()?.let { account ->
          CustomerInfoRow(
              label = "Account",
              value =
                  listOf(account.type, account.accountNumber)
                      .filter { it.isNotBlank() }
                      .joinToString(" - "),
          )
          CustomerInfoRow(
              label = "Balance",
              value =
                  listOf(account.balance, account.status)
                      .filter { it.isNotBlank() }
                      .joinToString(" - "),
          )
        }
        CustomerInfoRow(label = "Last visit", value = customer.lastVisit)
        customer.history.firstOrNull()?.let { history ->
          CustomerInfoRow(
              label = history.type.ifBlank { "History" },
              value =
                  listOf(history.date, history.notes)
                      .filter { it.isNotBlank() }
                      .joinToString(" - "),
              maxLines = 2,
          )
        }
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

@Composable
private fun CustomerInfoRow(
    label: String,
    value: String,
    maxLines: Int = 1,
) {
  if (value.isBlank()) return

  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
        text = "$label:",
        color = AppColor.Yellow,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = value,
        color = Color.White.copy(alpha = 0.82f),
        style = MaterialTheme.typography.labelSmall,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
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

private fun String.toLiveAnalysisText(): String {
  val trimmed = trim()
  return extractPartialJsonString("explanation")
      ?: extractPartialJsonString("summary")
      ?: trimmed
          .removePrefix("{")
          .replace(Regex("[{}\"]"), "")
          .replace(",", "\n")
          .trim()
}

private fun String.extractPartialJsonString(key: String): String? {
  val start = indexOf("\"$key\"")
  if (start < 0) return null
  val colon = indexOf(':', start)
  if (colon < 0) return null
  val firstQuote = indexOf('"', colon + 1)
  if (firstQuote < 0) return null

  val builder = StringBuilder()
  var index = firstQuote + 1
  var escaping = false
  while (index < length) {
    val char = this[index]
    if (escaping) {
      builder.append(char)
      escaping = false
    } else if (char == '\\') {
      escaping = true
    } else if (char == '"') {
      break
    } else {
      builder.append(char)
    }
    index++
  }
  return builder.toString().takeIf { it.isNotBlank() }
}

private fun String.toMarkdownAnnotatedString(): AnnotatedString =
    buildAnnotatedString {
      var index = 0
      var bold = false
      while (index < this@toMarkdownAnnotatedString.length) {
        val markerIndex = this@toMarkdownAnnotatedString.indexOf("**", startIndex = index)
        if (markerIndex < 0) {
          appendMarkdownSegment(this@toMarkdownAnnotatedString.substring(index), bold)
          break
        }
        appendMarkdownSegment(this@toMarkdownAnnotatedString.substring(index, markerIndex), bold)
        bold = !bold
        index = markerIndex + 2
      }
    }

private fun AnnotatedString.Builder.appendMarkdownSegment(text: String, bold: Boolean) {
  if (text.isEmpty()) return
  val normalized = text.replace(Regex("(?m)^[-*]\\s+"), "- ")
  if (bold) {
    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
    append(normalized)
    pop()
  } else {
    append(normalized)
  }
}
