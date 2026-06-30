/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamViewModel - DAT Camera Streaming API Demo
//
// This ViewModel demonstrates the DAT Camera Streaming APIs for:
// - Creating and managing stream sessions with wearable devices
// - Receiving video frames from device cameras
// - Capturing photos during streaming sessions
// - Handling different video qualities and formats
// - Processing raw video data (I420 -> ARGB conversion)

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.removeStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ai.DocumentAnalysisResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ai.FaceRecognitionResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ai.FaceRecognitionService
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ai.GeminiService
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.AssistantSpeechListener
import com.meta.wearable.dat.externalsampleapps.cameraaccess.debug.DebugTrace
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.ConversationRole
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.ConversationTurn
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.microwakeword.MicroWakeWord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.microwakeword.MicroWakeWordModelConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.microwakeword.MicroWakeWordModelLoader
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.GlassesSpeechEngine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.MicroWakeWordWakeListener
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.PostWakeCommandListener
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.SpeechWakeWordListener
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.SpeakableText
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceCommandIntent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceCommandResolver
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceCommandContext
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceAudioEnvironment
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceSessionController
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceWakeConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("AutoCloseableUse")
class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "CameraAccess:StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
    private val SESSION_TERMINAL_STATES = setOf(StreamState.CLOSED)
    private const val FACE_MATCH_CONSECUTIVE_REQUIRED = 2
    private const val FACE_STATUS_DEBOUNCE_MS = 1_200L
    private const val FACE_RECOGNITION_INTERVAL_MS = 1_500L
    private const val MATCHED_FACE_RECHECK_INTERVAL_MS = 6_000L
    private const val ENABLE_RAW_AUDIO_RECORDING = false
    private const val MAX_PARTIAL_RESPONSE_CHARS = 900
    private const val DOCUMENT_QA_MIC_HANDOFF_DELAY_MS = 400L
    private const val CAMERA_STREAMING_TIMEOUT_MS = 8_000L
  }

  private enum class DocumentScanTrigger(
      val requestedStatus: String,
      val busyLogMessage: String,
  ) {
    VOICE(
        requestedStatus = "Scan command detected",
        busyLogMessage = "Document scan already in progress, ignoring voice command",
    ),
    BUTTON(
        requestedStatus = "Manual scan requested",
        busyLogMessage = "Document scan already in progress, ignoring manual request",
    ),
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private val geminiService: GeminiService = GeminiService(application)
  private val faceRecognitionService: FaceRecognitionService = FaceRecognitionService(application)
  private var session: DeviceSession? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var audioJob: Job? = null
  private var stateJob: Job? = null
  private var errorJob: Job? = null
  @SuppressLint("MissingGuardedByAnnotation") private var sessionErrorJob: Job? = null
  private var sessionStateJob: Job? = null
  private var faceRecognitionJob: Job? = null
  private var lastFaceRecognitionAtMs = 0L
  private var microWakeWordListener: MicroWakeWordWakeListener? = null
  private var speechWakeWordListener: SpeechWakeWordListener? = null
  private var preferSpeechWakeFallback = false
  private var postWakeCommandListener: PostWakeCommandListener? = null
  private var wakeModelConfig: MicroWakeWordModelConfig? = null
  private val glassesSpeechEngine = GlassesSpeechEngine(application)
  private val voiceSessionController = VoiceSessionController(application)
  private val cameraBurstController = CameraBurstController(deviceSelector, voiceSessionController)
  private var customerVoiceSessionStarted = false
  private var consecutiveFaceMatches = 0
  private var lastFaceStatusUpdateAtMs = 0L
  private var documentQuestionSpeechListener: AssistantSpeechListener? = null
  private var documentQuestionStartJob: Job? = null
  private var documentSessionText: String? = null
  private var stream: Stream? = null
  private var audioStream: AudioStream? = null
  private var previousDeviceSessionState: DeviceSessionState? = null

  // Buffer for recording audio frames
  // Buffer for recording audio frames
  private var audioRecordingBuffer = ByteArrayOutputStream()
  private var recordedAudioSampleRate = 0
  private var recordedAudioChannelCount = 0

  // Presentation queue for buffering frames after color conversion
  private var presentationQueue: PresentationQueue? = null

  fun startStream() {
    Log.i(TAG, "Starting stream with document scan diagnostics enabled")
    videoJob?.cancel()
    audioJob?.cancel()
    stateJob?.cancel()
    errorJob?.cancel()
    sessionErrorJob?.cancel()
    sessionStateJob?.cancel()
    presentationQueue?.stop()
    presentationQueue = null
    previousDeviceSessionState = null
    faceRecognitionJob?.cancel()
    faceRecognitionJob = null
    stopWakeWordListener()
    customerVoiceSessionStarted = false
    consecutiveFaceMatches = 0
    lastFaceStatusUpdateAtMs = 0L
    preferSpeechWakeFallback = false
    wearablesViewModel.setVoiceSessionActive(false)
    clearDocumentSession()
    lastFaceRecognitionAtMs = 0L

    // Reset audio recording buffer
    audioRecordingBuffer = ByteArrayOutputStream()
    recordedAudioSampleRate = 0
    recordedAudioChannelCount = 0

    // Initialize presentation queue - frames are presented based on timestamp, not arrival time
    // Uses IntArray pooling for efficiency - cheaper than Bitmap.copy()
    val queue =
        PresentationQueue(
            bufferDelayMs = 100L,
            maxQueueSize = 15,
            onFrameReady = { frame ->
              // This is called from the presentation thread at regular intervals
              // when a frame's presentation time has arrived
              viewModelScope.launch(Dispatchers.Main) {
                _uiState.update {
                  it.copy(videoFrame = frame.bitmap, videoFrameCount = it.videoFrameCount + 1)
                }
              }
              scheduleFaceRecognition(frame.bitmap)
            },
        )
    presentationQueue = queue
    queue.start()
    if (session == null) {
      previousDeviceSessionState = null
      Wearables.createSession(deviceSelector)
          .onSuccess { createdSession ->
            session = createdSession
            sessionErrorJob = viewModelScope.launch {
              createdSession.errors.collect { error -> handleSessionError(error) }
            }
            session?.start()
          }
          .onFailure { error, _ ->
            Log.e(TAG, "Failed to create session: ${error.description}")
            handleSessionError(error)
          }
      if (session == null) return
    }
    startStreamInternal()
  }

  private fun startStreamInternal() {
    Log.d(TAG, "startStreamInternal() - collecting session state")
    sessionStateJob = viewModelScope.launch {
      session?.state?.collect { currentState ->
        val prevState = previousDeviceSessionState
        previousDeviceSessionState = currentState

        if (currentState == DeviceSessionState.STARTED) {
          wearablesViewModel.setDatAppUpdateRequired(false)
          if (prevState == DeviceSessionState.PAUSED && stream != null) {
            // PAUSED → STARTED: device-initiated resume (tap gesture).
            // The SDK handles resume internally via requestCameraOn() → resumeStreaming().
            // Do NOT recreate the stream — just let the SDK resume it.
            Log.d(TAG, "Session resumed from PAUSED — stream stays alive")
            return@collect
          }

          if (_uiState.value.operatingMode == StreamOperatingMode.VOICE_SESSION) {
            Log.d(TAG, "Voice session active — skipping automatic stream re-attach")
            return@collect
          }

          videoJob?.cancel()
          stateJob?.cancel()
          errorJob?.cancel()
          stream?.stop()
          stream = null
          session
              ?.addStream(cameraBurstController.identificationStreamConfig())
              ?.onSuccess { addedStream ->
                stream = addedStream
                videoJob = viewModelScope.launch {
                  Log.d(TAG, "Collecting video frames from stream")
                  stream?.videoStream?.collect { handleVideoFrame(it) }
                  Log.d(TAG, "Video stream collection ended")
                }

                if (ENABLE_RAW_AUDIO_RECORDING) {
                  // The DAT SDK doesn't expose a dedicated audio API — glasses mic/speaker audio is
                  // accessed via the system Bluetooth HFP profile. See:
                  // https://wearables.developer.meta.com/docs/microphones-and-speakers/
                  val glassesAudio = BluetoothMicAudioStream(getApplication())
                  audioStream = glassesAudio
                  audioJob = viewModelScope.launch {
                    Log.d(TAG, "Collecting audio frames from glasses microphone")
                    glassesAudio.audioStream.collect { handleAudioFrame(it) }
                  }
                  glassesAudio.start()
                }
                // Wake word starts after customer identification (voice session).

                stateJob = viewModelScope.launch {
                  stream?.state?.collect { streamState ->
                    val prevStreamState = _uiState.value.streamState
                    Log.d(TAG, "Stream state changed: $prevStreamState -> $streamState")
                    _uiState.update { it.copy(streamState = streamState) }

                    val wasActive = prevStreamState !in SESSION_TERMINAL_STATES
                    val isTerminated = streamState in SESSION_TERMINAL_STATES
                    if (wasActive && isTerminated) {
                      if (
                          _uiState.value.operatingMode == StreamOperatingMode.VOICE_SESSION ||
                              _uiState.value.isDocumentAnalyzing ||
                              customerVoiceSessionStarted
                      ) {
                        Log.d(
                            TAG,
                            "Stream closed during voice session or document capture — keeping session alive",
                        )
                        return@collect
                      }
                      Log.d(TAG, "Terminal state reached, navigating back")
                      stopStream()
                      wearablesViewModel.navigateToDeviceSelection()
                    }
                  }
                }
                errorJob = viewModelScope.launch {
                  stream?.errorStream?.collect { error ->
                    Log.d(TAG, "Stream error received: $error (description: ${error.description})")
                    if (error == StreamError.STREAM_ERROR) {
                      Log.d(TAG, "Non-critical error, stream continues")
                      return@collect
                    }
                    if (
                        _uiState.value.operatingMode == StreamOperatingMode.VOICE_SESSION ||
                            customerVoiceSessionStarted ||
                            wearablesViewModel.isVoiceSessionActive
                    ) {
                      Log.w(TAG, "Ignoring stream error during voice session: ${error.description}")
                      return@collect
                    }
                    stopStream()
                    wearablesViewModel.navigateToDeviceSelection()
                    wearablesViewModel.setRecentError(error.description)
                  }
                }
                stream?.start()
              }
              ?.onFailure { error, _ ->
                Log.e(TAG, "Failed to add stream to session: ${error.description}")
              }
        } else if (currentState == DeviceSessionState.PAUSED) {
          // Tap gesture paused the session — keep the stream alive.
          // The SDK transitions StreamState to PAUSED internally.
          Log.d(TAG, "Session paused (tap gesture) — keeping stream alive for resume")
        }
      }
    }
  }

  fun stopStream(onComplete: (Boolean) -> Unit = {}) {
    videoJob?.cancel()
    videoJob = null
    audioJob?.cancel()
    audioJob = null
    stateJob?.cancel()
    stateJob = null
    errorJob?.cancel()
    errorJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    sessionStateJob?.cancel()
    sessionStateJob = null
    faceRecognitionJob?.cancel()
    faceRecognitionJob = null
    clearDocumentSession()
    stopWakeWordListener()
    customerVoiceSessionStarted = false
    consecutiveFaceMatches = 0
    lastFaceStatusUpdateAtMs = 0L
    preferSpeechWakeFallback = false
    wearablesViewModel.setVoiceSessionActive(false)
    lastFaceRecognitionAtMs = 0L
    presentationQueue?.stop()
    presentationQueue = null
    _uiState.update {
      it.copy(
          streamState = StreamState.STOPPED,
          operatingMode = StreamOperatingMode.CAMERA_IDENTIFYING,
          isCameraActive = true,
          pendingCustomerQna = false,
          isFaceRecognitionRunning = false,
          isVoiceCommandListening = false,
          isDocumentAnalyzing = false,
          documentScanPhase = DocumentScanPhase.IDLE,
          documentAnalysisPartial = null,
      )
    }
    stream?.stop()
    stream = null
    audioStream?.stop()
    audioStream = null
    session?.stop()
    session = null

    // If we have recorded audio, show the playback dialog
    val hasAudio = audioRecordingBuffer.size() > 0
    if (hasAudio) {
      _uiState.update { it.copy(isAudioPlaybackVisible = true) }
    }
    glassesSpeechEngine.shutdown()
    onComplete(hasAudio)
  }

  fun hideAudioPlayback() {
    _uiState.update { it.copy(isAudioPlaybackVisible = false) }
    // Clear buffer after closing dialog to prepare for next session
    audioRecordingBuffer = ByteArrayOutputStream()
  }

  fun getRecordedAudio(): RecordedAudio {
    return RecordedAudio(
      data = audioRecordingBuffer.toByteArray(),
      sampleRateHz = recordedAudioSampleRate.takeIf { it > 0 } ?: 8000,
      channelCount = recordedAudioChannelCount.takeIf { it > 0 } ?: 1,
    )
  }

  private fun handleSessionError(error: DeviceSessionError) {
    Log.e(TAG, "Session error: ${error.description}")

    if (
        _uiState.value.operatingMode == StreamOperatingMode.VOICE_SESSION ||
            customerVoiceSessionStarted ||
            wearablesViewModel.isVoiceSessionActive
    ) {
      Log.w(TAG, "Ignoring session error during voice session: ${error.description}")
      return
    }

    val alreadyShowingUpdateRequired =
        wearablesViewModel.uiState.value.isFirmwareUpdateRequired ||
            wearablesViewModel.uiState.value.isDatAppUpdateRequired

    if (
        error == DeviceSessionError.SESSION_ENDED_BY_DEVICE &&
            shouldTreatSessionEndedAsDatAppUpdateRequired()
    ) {
      wearablesViewModel.setDatAppUpdateRequired(true)
      wearablesViewModel.setRecentError(
          getApplication<Application>().getString(R.string.update_required_dat_app_message)
      )
      stopStream()
      wearablesViewModel.navigateToDeviceSelection()
      return
    }

    if (alreadyShowingUpdateRequired && error == DeviceSessionError.SESSION_ENDED_BY_DEVICE) {
      stopStream()
      wearablesViewModel.navigateToDeviceSelection()
      return
    }

    if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
      wearablesViewModel.setDatAppUpdateRequired(true)
    }
    wearablesViewModel.setRecentError(error.description)
    stopStream()
    wearablesViewModel.navigateToDeviceSelection()
  }

  private fun shouldTreatSessionEndedAsDatAppUpdateRequired(): Boolean {
    val sessionNeverStarted =
        previousDeviceSessionState != DeviceSessionState.STARTED &&
            previousDeviceSessionState != DeviceSessionState.PAUSED
    return sessionNeverStarted
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }

    viewModelScope.launch {
      if (uiState.value.streamState != StreamState.STREAMING || stream == null) {
        if (!ensureCameraForCapture()) {
          Log.w(TAG, "Cannot capture photo: camera not ready")
          return@launch
        }
      }
      Log.d(TAG, "Starting photo capture")
      _uiState.update { it.copy(isCapturing = true) }
      stream
          ?.capturePhoto()
          ?.onSuccess { photoData ->
            Log.d(TAG, "Photo capture successful")
            handlePhotoData(photoData)
            _uiState.update { it.copy(isCapturing = false) }
            if (_uiState.value.operatingMode == StreamOperatingMode.VOICE_SESSION) {
              stopCameraStreamOnly()
              delay(VoiceWakeConfig.POST_TTS_COOLDOWN_MS)
              startWakeWordListener()
            }
          }
          ?.onFailure { error, _ ->
            Log.e(TAG, "Photo capture failed: ${error.description}")
            _uiState.update {
              it.copy(
                  isCapturing = false,
                  voiceCommandStatus =
                      if (_uiState.value.operatingMode == StreamOperatingMode.VOICE_SESSION) {
                        appString(R.string.voice_photo_capture_failed, error.description)
                      } else {
                        it.voiceCommandStatus
                      },
              )
            }
            if (_uiState.value.operatingMode == StreamOperatingMode.VOICE_SESSION) {
              stopCameraStreamOnly()
              delay(VoiceWakeConfig.POST_TTS_COOLDOWN_MS)
              startWakeWordListener()
            }
          }
    }
  }

  private fun startWakeWordListener() {
    if (microWakeWordListener != null || speechWakeWordListener != null) return
    if (_uiState.value.operatingMode != StreamOperatingMode.VOICE_SESSION) return
    if (_uiState.value.isDocumentAnalyzing || _uiState.value.isDocumentQuestionListening) return

    refreshAudioEnvironmentState()
    val config =
        wakeModelConfig
            ?: runCatching {
                  MicroWakeWordModelConfig.loadFromAsset(
                      getApplication(),
                      VoiceWakeConfig.MODEL_CONFIG_ASSET,
                  )
                }
                .getOrNull()
                ?.also { wakeModelConfig = it }
    if (config == null) {
      _uiState.update { it.copy(voiceCommandStatus = "Wake word model unavailable") }
      return
    }

    viewModelScope.launch {
      if (preferSpeechWakeFallback) {
        startSpeechWakeWordListener(config)
        return@launch
      }

      // Start speech wake immediately so the session stays usable if native init fails.
      startSpeechWakeWordListener(config)

      val microReady =
          withContext(Dispatchers.Default) {
            try {
              MicroWakeWord.ensureLibraryLoaded()
              MicroWakeWordModelLoader.createDetector(getApplication(), config).use {}
              true
            } catch (e: Throwable) {
              Log.e(TAG, "Micro wake word init failed; keeping speech fallback", e)
              preferSpeechWakeFallback = true
              false
            }
          }

      if (!microReady) return@launch

      speechWakeWordListener?.stop()
      speechWakeWordListener = null
      microWakeWordListener =
          MicroWakeWordWakeListener(
              context = getApplication(),
              modelConfig = config,
              onWakeWordDetected = { onWakeWordDetected() },
              onStatusChanged = { status ->
                val audioManager =
                    getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val displayStatus =
                    if (!VoiceAudioEnvironment.prefersPhoneAudio(getApplication(), audioManager)) {
                      getApplication<Application>().getString(R.string.voice_wake_phone_hint)
                    } else {
                      status
                    }
                _uiState.update {
                  it.copy(isVoiceCommandListening = true, voiceCommandStatus = displayStatus)
                }
              },
          )
      microWakeWordListener?.start(viewModelScope)
      Log.i(TAG, "Upgraded to micro wake word listener")
    }
  }

  private fun startSpeechWakeWordListener(config: MicroWakeWordModelConfig) {
    val phrase = config.wakeWord.replace('_', ' ')
    speechWakeWordListener =
        SpeechWakeWordListener(
            context = getApplication(),
            wakePhrase = phrase,
            onWakeWordDetected = { onWakeWordDetected() },
            onStatusChanged = { status ->
              _uiState.update {
                it.copy(isVoiceCommandListening = true, voiceCommandStatus = status)
              }
            },
        )
    speechWakeWordListener?.start()
    Log.i(TAG, "Speech wake word listener started")
  }

  private fun stopWakeWordListener() {
    microWakeWordListener?.stop()
    microWakeWordListener = null
    speechWakeWordListener?.stop()
    speechWakeWordListener = null
    postWakeCommandListener?.stop()
    postWakeCommandListener = null
    _uiState.update { it.copy(isVoiceCommandListening = false, voiceTranscript = null) }
  }

  private fun voiceCommandContext(): VoiceCommandContext {
    val state = _uiState.value
    return VoiceCommandContext(
        isDocumentSessionActive = state.isDocumentSessionActive,
        documentScanPhase = state.documentScanPhase,
        isDocumentAnalyzing = state.isDocumentAnalyzing,
    )
  }

  private fun refreshAudioEnvironmentState() {
    _uiState.update {
      it.copy(
          isGlassesScoConnected = voiceSessionController.isGlassesScoConnected(),
          isMockDeviceMode = voiceSessionController.isMockDeviceMode(),
      )
    }
  }

  private fun updateHandoffPhase(phase: HandoffPhase) {
    _uiState.update { it.copy(handoffPhase = phase) }
    refreshAudioEnvironmentState()
  }

  private fun appString(resId: Int, vararg args: Any): String =
      getApplication<Application>().getString(resId, *args)

  private fun onWakeWordDetected() {
    if (_uiState.value.isDocumentAnalyzing || _uiState.value.isDocumentQuestionListening) return
    stopWakeWordListener()
    val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val usesGlassesMic =
        !VoiceAudioEnvironment.prefersPhoneAudio(getApplication(), audioManager)
    val commandPrompt =
        if (usesGlassesMic) {
          getApplication<Application>().getString(R.string.voice_post_wake_glasses_prompt)
        } else {
          getApplication<Application>().getString(R.string.voice_post_wake_prompt)
        }
    _uiState.update {
      it.copy(voiceCommandStatus = commandPrompt, voiceTranscript = null)
    }
    postWakeCommandListener =
        PostWakeCommandListener(
            context = getApplication(),
            commandContext = { voiceCommandContext() },
            onTranscript = { transcript ->
              _uiState.update {
                it.copy(
                    voiceTranscript = transcript,
                    voiceCommandStatus = appString(R.string.voice_command_heard, transcript),
                )
              }
            },
            onSessionEnded = { transcript ->
              dispatchPostWakeIntent(VoiceCommandResolver.resolve(transcript, voiceCommandContext()))
            },
            onError = { message -> _uiState.update { it.copy(voiceCommandStatus = message) } },
        )
    postWakeCommandListener?.start()
  }

  private fun dispatchPostWakeIntent(intent: VoiceCommandIntent) {
    postWakeCommandListener = null
    val context = voiceCommandContext()
    // #region agent log
    DebugTrace.log(
        location = "StreamViewModel:dispatchPostWakeIntent",
        message = "dispatching intent",
        hypothesisId = "H7",
        data =
            mapOf(
                "intent" to intent.name,
                "transcript" to (_uiState.value.voiceTranscript ?: "null"),
            ),
    )
    // #endregion
    when (intent) {
      VoiceCommandIntent.OPEN_QNA -> handleVoiceOpenQna()
      VoiceCommandIntent.DOCUMENT_QNA -> handleVoiceDocumentQna()
      VoiceCommandIntent.SCAN_DOCUMENT -> handleVoiceScanCommand()
      VoiceCommandIntent.CAPTURE_PHOTO -> handleVoiceCapturePhoto()
      VoiceCommandIntent.CANCEL -> {
        _uiState.update { it.copy(voiceCommandStatus = appString(R.string.voice_command_cancelled)) }
        startWakeWordListener()
      }
      VoiceCommandIntent.NO_COMMAND -> {
        val status =
            if (context.isDocumentSessionActive && context.documentScanPhase.isScanning()) {
              appString(R.string.voice_command_please_wait)
            } else {
              appString(R.string.voice_command_retry)
            }
        _uiState.update { it.copy(voiceCommandStatus = status) }
        startWakeWordListener()
      }
    }
  }

  private fun DocumentScanPhase.isScanning(): Boolean =
      this == DocumentScanPhase.CAPTURING ||
          this == DocumentScanPhase.GROUNDING ||
          this == DocumentScanPhase.ANALYZING

  fun clearPendingCustomerQna() {
    _uiState.update { it.copy(pendingCustomerQna = false) }
  }

  fun pauseWakeForAssistant() {
    stopWakeWordListener()
    _uiState.update { it.copy(voiceCommandStatus = appString(R.string.voice_rm_assistant_listening)) }
  }

  fun resumeWakeAfterAssistant() {
    if (_uiState.value.operatingMode == StreamOperatingMode.VOICE_SESSION) {
      viewModelScope.launch {
        delay(VoiceWakeConfig.POST_TTS_COOLDOWN_MS)
        startWakeWordListener()
      }
    }
  }

  fun pauseVoiceCommandsForAssistant() = pauseWakeForAssistant()

  fun resumeVoiceCommandsAfterAssistant() = resumeWakeAfterAssistant()

  private fun handleVoiceOpenQna() {
    if (_uiState.value.matchedCustomer == null) {
      _uiState.update { it.copy(voiceCommandStatus = appString(R.string.voice_recognize_customer_first)) }
      startWakeWordListener()
      return
    }
    stopWakeWordListener()
    _uiState.update {
      it.copy(pendingCustomerQna = true, voiceCommandStatus = appString(R.string.voice_opening_customer_qna))
    }
  }

  private fun handleVoiceDocumentQna() {
    val state = _uiState.value
    if (!state.isDocumentSessionActive || state.documentScanPhase != DocumentScanPhase.READY) {
      _uiState.update { it.copy(voiceCommandStatus = appString(R.string.voice_command_please_wait)) }
      startWakeWordListener()
      return
    }
    stopWakeWordListener()
    _uiState.update { it.copy(voiceCommandStatus = appString(R.string.voice_opening_document_qna)) }
    startDocumentQuestionListening()
  }

  private fun handleVoiceScanCommand() {
    viewModelScope.launch {
      if (_uiState.value.isDocumentSessionActive && _uiState.value.documentAnalysis != null) {
        clearDocumentSession()
      }
      val cameraReady = ensureCameraForCapture()
      if (!cameraReady) {
        _uiState.update { it.copy(voiceCommandStatus = appString(R.string.voice_camera_not_ready_scan)) }
        startWakeWordListener()
        return@launch
      }
      requestDocumentScan(DocumentScanTrigger.VOICE)
    }
  }

  private fun handleVoiceCapturePhoto() {
    viewModelScope.launch {
      val cameraReady = ensureCameraForCapture()
      if (!cameraReady) {
        _uiState.update { it.copy(voiceCommandStatus = appString(R.string.voice_camera_not_ready_photo)) }
        startWakeWordListener()
        return@launch
      }
      capturePhoto()
    }
  }

  fun listenForVoiceTest() {
    viewModelScope.launch {
      if (_uiState.value.isCameraActive) {
        stopCameraStreamOnly()
      }
      stopWakeWordListener()
      _uiState.update {
        it.copy(
            operatingMode = StreamOperatingMode.VOICE_SESSION,
            isVoiceCommandListening = true,
            voiceCommandStatus = appString(R.string.voice_listening_hey_charly),
            voiceTranscript = null,
        )
      }
      startWakeWordListener()
    }
  }

  suspend fun speakQnaResponse(answer: String) {
    pauseWakeForAssistant()
    val spoken = SpeakableText.truncateToLines(answer)
    if (spoken.isNotBlank()) {
      glassesSpeechEngine.speak(spoken)
      delay(VoiceWakeConfig.POST_TTS_COOLDOWN_MS)
    }
    resumeWakeAfterAssistant()
  }

  private suspend fun stopCameraForVoiceSession(customer: Customer) {
    if (customerVoiceSessionStarted) {
      // #region agent log
      DebugTrace.log(
          location = "StreamViewModel:stopCameraForVoiceSession",
          message = "skipped already started",
          hypothesisId = "H1",
          data = mapOf("customerId" to customer.id),
      )
      // #endregion
      return
    }
    customerVoiceSessionStarted = true
    wearablesViewModel.setVoiceSessionActive(true)
    // #region agent log
    DebugTrace.log(
        location = "StreamViewModel:stopCameraForVoiceSession",
        message = "starting voice session",
        hypothesisId = "H1-H5",
        data =
            mapOf(
                "customerId" to customer.id,
                "hasSnapshot" to (_uiState.value.videoFrame != null).toString(),
            ),
    )
    // #endregion

    val frame = _uiState.value.videoFrame
    val snapshot =
        frame?.let {
          try {
            it.copy(it.config ?: Bitmap.Config.ARGB_8888, false)
          } catch (e: Exception) {
            Log.w(TAG, "Unable to snapshot identification frame", e)
            null
          }
        }

    faceRecognitionJob = null
    _uiState.update {
      it.copy(
          operatingMode = StreamOperatingMode.VOICE_SESSION,
          isCameraActive = false,
          identificationSnapshot = snapshot,
          isFaceRecognitionRunning = false,
          faceRecognitionStatus = appString(R.string.voice_customer_matched),
      )
    }
    detachCameraStream(stopPresentationQueue = true, releaseStreamFromSession = false)

    _uiState.update {
      it.copy(streamState = StreamState.STOPPED)
    }

    try {
      updateHandoffPhase(HandoffPhase.SWITCHING_TO_GLASSES)
      voiceSessionController.prewarmGlassesSco()
      voiceSessionController.awaitGlassesAudio(::updateHandoffPhase)
      glassesSpeechEngine.speak(appString(R.string.voice_welcome_back, customer.name))
      delay(VoiceWakeConfig.POST_TTS_COOLDOWN_MS)
      updateHandoffPhase(HandoffPhase.IDLE)
      refreshAudioEnvironmentState()
      startWakeWordListener()
    } catch (e: kotlinx.coroutines.CancellationException) {
      // #region agent log
      DebugTrace.log(
          location = "StreamViewModel:stopCameraForVoiceSession",
          message = "handoff cancelled",
          hypothesisId = "H6",
          data = mapOf("reason" to (e.message ?: "cancelled")),
      )
      // #endregion
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Voice session handoff failed after face match", e)
      _uiState.update {
        it.copy(voiceCommandStatus = "Voice session error: ${e.message ?: "unknown"}")
      }
      updateHandoffPhase(HandoffPhase.IDLE)
    }
  }

  private suspend fun stopCameraStreamOnly() {
    detachCameraStream(stopPresentationQueue = false, releaseStreamFromSession = false)
    _uiState.update {
      it.copy(isCameraActive = false, streamState = StreamState.STOPPED)
    }
    voiceSessionController.awaitGlassesAudio(::updateHandoffPhase)
  }

  private fun detachCameraStream(
      stopPresentationQueue: Boolean,
      releaseStreamFromSession: Boolean = true,
  ) {
    videoJob?.cancel()
    videoJob = null
    audioJob?.cancel()
    audioJob = null
    stateJob?.cancel()
    stateJob = null
    errorJob?.cancel()
    errorJob = null
    stream?.stop()
    if (releaseStreamFromSession) {
      stream = null
      session
          ?.removeStream()
          ?.onFailure { error, _ ->
            Log.w(TAG, "removeStream during camera detach failed: ${error.description}")
          }
    }
    audioStream?.stop()
    audioStream = null
    if (stopPresentationQueue) {
      presentationQueue?.stop()
      presentationQueue = null
    }
  }

  private fun bluetoothHandoffDelayMs(): Long {
    val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return VoiceAudioEnvironment.bluetoothHandoffDelayMs(getApplication(), audioManager)
  }

  private suspend fun ensureCameraForCapture(): Boolean {
    if (_uiState.value.streamState == StreamState.STREAMING && stream != null) return true

    return cameraBurstController.ensureCameraForCapture(
        CameraBurstController.CaptureEnvironment(
            session = session,
            stream = stream,
            onSessionRecreated = { recreated ->
              session = recreated
              sessionErrorJob?.cancel()
              sessionErrorJob =
                  viewModelScope.launch { recreated.errors.collect { error -> handleSessionError(error) } }
            },
            onStreamAttached = { attached -> stream = attached },
            onPresentationQueueNeeded = { ensurePresentationQueueForCapture() },
            onStreamCollectorsNeeded = { attachStreamCollectorsForCapture() },
            getStreamState = { _uiState.value.streamState },
            onHandoffProgress = ::updateHandoffPhase,
            onCameraActive = { active -> _uiState.update { it.copy(isCameraActive = active) } },
            onStreamState = { streamState ->
              _uiState.update { it.copy(streamState = streamState, isCameraActive = true) }
            },
            logFailure = ::logEnsureCameraFailure,
        ),
    )
  }

  private suspend fun awaitSessionStarted(timeoutMs: Long = 4_000L): Boolean {
    val activeSession = session ?: return false
    if (activeSession.state.value == DeviceSessionState.STARTED) return true
    val ready =
        withTimeoutOrNull(timeoutMs) {
          while (activeSession.state.value != DeviceSessionState.STARTED) {
            delay(100)
          }
        }
    return ready != null
  }

  private suspend fun recreateSessionForCapture(): Boolean {
    Log.i(TAG, "Recreating DAT session for voice capture")
    session?.stop()
    session = null
    stream = null
    val createdSession =
        Wearables.createSession(deviceSelector).fold(
            onSuccess = { it },
            onFailure = { error, _ ->
              logEnsureCameraFailure("create_session_failed", error.description)
              null
            },
        )
        ?: return false
    session = createdSession
    sessionErrorJob?.cancel()
    sessionErrorJob =
        viewModelScope.launch { createdSession.errors.collect { error -> handleSessionError(error) } }
    createdSession.start()
    return awaitSessionStarted(8_000L)
  }

  private fun ensurePresentationQueueForCapture() {
    if (presentationQueue != null) return
    val queue =
        PresentationQueue(
            bufferDelayMs = 100L,
            maxQueueSize = 15,
            onFrameReady = { frame ->
              viewModelScope.launch(Dispatchers.Main) {
                _uiState.update {
                  it.copy(videoFrame = frame.bitmap, videoFrameCount = it.videoFrameCount + 1)
                }
              }
            },
        )
    presentationQueue = queue
    queue.start()
  }

  private fun attachStreamCollectorsForCapture() {
    videoJob?.cancel()
    videoJob = viewModelScope.launch { stream?.videoStream?.collect { handleVideoFrame(it) } }
    stateJob?.cancel()
    errorJob?.cancel()
    stateJob =
        viewModelScope.launch {
          stream?.state?.collect { streamState ->
            _uiState.update { it.copy(streamState = streamState, isCameraActive = true) }
          }
        }
    errorJob =
        viewModelScope.launch {
          stream?.errorStream?.collect { error ->
            Log.d(TAG, "Temp capture stream error: $error")
            if (error != StreamError.STREAM_ERROR) {
              _uiState.update { it.copy(voiceCommandStatus = error.description) }
            }
          }
        }
  }

  private suspend fun waitForStreamingState(): Boolean {
    val reachedStreaming =
        withTimeoutOrNull(CAMERA_STREAMING_TIMEOUT_MS) {
          while (_uiState.value.streamState != StreamState.STREAMING) {
            delay(100)
          }
        }
    return reachedStreaming != null
  }

  private fun logEnsureCameraFailure(reason: String, detail: String? = null) {
    Log.w(TAG, "ensureCameraForCapture failed: $reason${detail?.let { " ($it)" } ?: ""}")
  }

  fun scanDocument() {
    Log.i(TAG, "Manual document scan requested")
    requestDocumentScan(DocumentScanTrigger.BUTTON)
  }

  fun retryDocumentScan() {
    Log.i(TAG, "Retry document scan requested")
    requestDocumentScan(DocumentScanTrigger.BUTTON)
  }

  private fun requestDocumentScan(trigger: DocumentScanTrigger) {
    Log.i(
        TAG,
        "Document scan trigger state: stream=${_uiState.value.streamState}, isCapturing=${_uiState.value.isCapturing}, isAnalyzing=${_uiState.value.isDocumentAnalyzing}, hasFrame=${_uiState.value.videoFrame != null}",
    )
    if (_uiState.value.isDocumentAnalyzing || _uiState.value.isCapturing) {
      Log.d(TAG, trigger.busyLogMessage)
      return
    }
    if (_uiState.value.isDocumentSessionActive && _uiState.value.documentAnalysis != null) {
      Log.d(TAG, "Document session already active, ignoring scan request")
      _uiState.update { it.copy(voiceCommandStatus = "End current document session before scanning") }
      return
    }
    stopWakeWordListener()
    Log.i(TAG, "Document session mic policy: wake listener stopped")
    _uiState.update { it.copy(voiceCommandStatus = trigger.requestedStatus) }
    clearDocumentSession()
    viewModelScope.launch {
      if (_uiState.value.streamState != StreamState.STREAMING || stream == null) {
        if (!ensureCameraForCapture()) {
          _uiState.update { it.copy(voiceCommandStatus = "Camera not ready for scan") }
          if (_uiState.value.operatingMode == StreamOperatingMode.VOICE_SESSION) {
            startWakeWordListener()
          }
          return@launch
        }
      }
      captureAndAnalyzeDocument(trigger)
    }
  }

  private suspend fun captureAndAnalyzeDocument(trigger: DocumentScanTrigger = DocumentScanTrigger.BUTTON) {
    Log.i(TAG, "Document scan flow starting")
    if (uiState.value.streamState != StreamState.STREAMING) {
      Log.w(TAG, "Cannot scan document: stream not active (state=${uiState.value.streamState})")
      _uiState.update { it.copy(voiceCommandStatus = "Start stream before scanning") }
      return
    }

    if (!geminiService.isConfigured()) {
      Log.w(TAG, "Skipping document scan because GEMINI_API_KEY is not configured")
      _uiState.update {
        it.copy(voiceCommandStatus = "Add GEMINI_API_KEY to scan documents")
      }
      return
    }

    val activeStream = stream
    if (activeStream == null) {
      Log.w(TAG, "Cannot scan document: stream object is not available")
      _uiState.update { it.copy(voiceCommandStatus = "Stream not ready") }
      return
    }

    val scanStartedAtMs = SystemClock.elapsedRealtime()
    _uiState.update {
      it.copy(
          isDocumentAnalyzing = true,
          documentScanPhase = DocumentScanPhase.CAPTURING,
          isCapturing = true,
          isDocumentSessionActive = true,
          documentAnalysisPartial = null,
          documentGroundingText = null,
          documentAnalysis = null,
          documentQuestionStatus = "Scan in progress",
          voiceCommandStatus = "Capturing document",
      )
    }

    glassesSpeechEngine.speak(appString(R.string.voice_scan_capturing))

    viewModelScope.launch(Dispatchers.IO) {
      var documentBitmap: Bitmap? = null
      try {
        val captureStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "Capturing document photo for analysis")
        val photoData =
            activeStream
                .capturePhoto()
                .onFailure { error, _ ->
                  throw IOException("Document capture failed: ${error.description}")
                }
                .getOrNull()
                ?: throw IOException("Document capture returned no data")
        val captureDurationMs = SystemClock.elapsedRealtime() - captureStartedAtMs
        documentBitmap = decodePhotoData(photoData)
        Log.i(
            TAG,
            "Document photo captured: bitmap=${documentBitmap.width}x${documentBitmap.height}, captureDurationMs=$captureDurationMs",
        )
        _uiState.update {
          it.copy(
              documentScanPhase = DocumentScanPhase.GROUNDING,
              voiceCommandStatus = appString(R.string.voice_scan_reading),
              documentQuestionStatus = appString(R.string.voice_scan_reading),
          )
        }
        glassesSpeechEngine.speak(appString(R.string.voice_scan_reading))
        glassesSpeechEngine.speak(appString(R.string.voice_scan_reading))

        val groundingStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "Starting document grounding from captured photo: bitmap=${documentBitmap.width}x${documentBitmap.height}",
        )
        val groundedText =
            geminiService.transcribeDocumentImage(documentBitmap) { partialText ->
              _uiState.update { state ->
                state.copy(
                    documentAnalysisPartial = partialText.take(MAX_PARTIAL_RESPONSE_CHARS),
                    documentGroundingText = partialText,
                )
              }
            }
        val groundingDurationMs = SystemClock.elapsedRealtime() - groundingStartedAtMs
        documentSessionText = groundedText
        _uiState.update {
          it.copy(
              documentScanPhase = DocumentScanPhase.ANALYZING,
              voiceCommandStatus = appString(R.string.voice_scan_analyzing),
              documentQuestionStatus = appString(R.string.voice_scan_analyzing),
              documentAnalysisPartial = null,
              documentGroundingText = groundedText,
          )
        }
        glassesSpeechEngine.speak(appString(R.string.voice_scan_analyzing))
        glassesSpeechEngine.speak(appString(R.string.voice_scan_analyzing))

        val analysisStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "Starting document analysis from grounded text: chars=${groundedText.length}",
        )
        val analysis =
            geminiService.analyzeDocument(groundedText) { partialText ->
              _uiState.update { state ->
                state.copy(documentAnalysisPartial = partialText.take(MAX_PARTIAL_RESPONSE_CHARS))
              }
            }
        val analysisDurationMs = SystemClock.elapsedRealtime() - analysisStartedAtMs
        val totalDurationMs = SystemClock.elapsedRealtime() - scanStartedAtMs
        Log.i(
            TAG,
            "Document scan completed: groundingDurationMs=$groundingDurationMs, analysisDurationMs=$analysisDurationMs, totalScanDurationMs=$totalDurationMs, parsed=${analysis.json != null}, groundedChars=${groundedText.length}, rawChars=${analysis.rawJson.length}",
        )
        _uiState.update {
          it.copy(
              isDocumentAnalyzing = false,
              isCapturing = false,
              documentScanPhase = DocumentScanPhase.READY,
              documentAnalysisPartial = null,
              documentAnalysis = analysis,
              documentQuestionStatus = "Ask a question about this document",
              voiceCommandStatus = "Document analyzed",
          )
        }
        val summaryForSpeech = extractDocumentSummaryForSpeech(analysis)
        glassesSpeechEngine.speak(appString(R.string.voice_scan_ready))
        if (summaryForSpeech.isNotBlank()) {
          glassesSpeechEngine.speak(SpeakableText.truncateToLines(summaryForSpeech))
        }
        if (_uiState.value.operatingMode == StreamOperatingMode.VOICE_SESSION) {
          stopCameraStreamOnly()
          delay(VoiceWakeConfig.POST_TTS_COOLDOWN_MS)
          startWakeWordListener()
        }
      } catch (e: Exception) {
        val totalDurationMs = SystemClock.elapsedRealtime() - scanStartedAtMs
        Log.w(
            TAG,
            "Document analysis failed after ${totalDurationMs}ms: ${e.javaClass.simpleName}: ${e.message}",
            e,
        )
        documentSessionText = null
        _uiState.update {
          it.copy(
              isDocumentAnalyzing = false,
              isCapturing = false,
              isDocumentSessionActive = true,
              documentScanPhase = DocumentScanPhase.FAILED,
              documentAnalysisPartial = null,
              documentGroundingText = null,
              documentQuestionStatus = "Scan failed",
              documentQuestionError = e.message ?: "Document analysis failed",
              voiceCommandStatus = e.message ?: "Document analysis failed",
          )
        }
        if (_uiState.value.operatingMode == StreamOperatingMode.VOICE_SESSION) {
          stopCameraStreamOnly()
          delay(VoiceWakeConfig.POST_TTS_COOLDOWN_MS)
          startWakeWordListener()
        }
      } finally {
        documentBitmap?.recycle()
      }
    }
  }

  fun dismissDocumentAnalysis() {
    endDocumentSession()
  }

  fun startDocumentQuestionListening() {
    val state = _uiState.value
    if (!state.isDocumentSessionActive || state.documentAnalysis == null) {
      _uiState.update { it.copy(documentQuestionError = "Scan a document before asking") }
      return
    }
    if (state.isDocumentQuestionListening || state.isDocumentAnswering) {
      Log.d(TAG, "Document Q&A already busy")
      return
    }
    if (documentSessionText.isNullOrBlank()) {
      _uiState.update { it.copy(documentQuestionError = "Document text is no longer available") }
      return
    }

    stopWakeWordListener()
    Log.i(TAG, "Document session mic policy: wake listener stopped")
    documentQuestionStartJob?.cancel()
    documentQuestionSpeechListener?.stop(cancel = true)
    _uiState.update {
      it.copy(
          isDocumentQuestionListening = true,
          documentQuestionStatus = "Preparing microphone",
          documentQuestionPartial = null,
          documentQuestionError = null,
      )
    }
    documentQuestionStartJob =
        viewModelScope.launch {
          voiceSessionController.awaitGlassesAudio(::updateHandoffPhase)
          val currentState = _uiState.value
          if (
              !currentState.isDocumentSessionActive ||
                  currentState.documentAnalysis == null ||
                  !currentState.isDocumentQuestionListening ||
                  documentQuestionSpeechListener != null
          ) {
            Log.d(TAG, "Document Q&A mic start skipped after handoff delay")
            return@launch
          }
          documentQuestionSpeechListener = createDocumentQuestionSpeechListener()
          Log.i(TAG, "Document Q&A mic started")
          documentQuestionSpeechListener?.start()
        }
  }

  fun stopDocumentQuestionListeningAndUsePartial() {
    documentQuestionSpeechListener?.stop(cancel = false)
  }

  fun cancelDocumentQuestionListening() {
    documentQuestionStartJob?.cancel()
    documentQuestionStartJob = null
    documentQuestionSpeechListener?.stop(cancel = true)
    documentQuestionSpeechListener = null
    Log.i(TAG, "Document Q&A mic stopped after cancel")
    _uiState.update {
      it.copy(
          isDocumentQuestionListening = false,
          documentQuestionStatus = "Question cancelled",
          documentQuestionPartial = null,
      )
    }
    resumeWakeAfterAssistant()
  }

  fun endDocumentSession() {
    clearDocumentSession()
    resumeDocumentScanVoiceListener()
    Log.i(TAG, "Document session ended: wake listener resumed")
  }

  private fun createDocumentQuestionSpeechListener(): AssistantSpeechListener =
      AssistantSpeechListener(
          context = getApplication<Application>(),
          onReady = {
            _uiState.update {
              it.copy(
                  isDocumentQuestionListening = true,
                  documentQuestionStatus = "Listening for your question",
                  documentQuestionPartial = null,
                  documentQuestionError = null,
              )
            }
          },
          onPartialTranscript = { transcript ->
            _uiState.update {
              it.copy(
                  isDocumentQuestionListening = true,
                  documentQuestionStatus = "Capturing question",
                  documentQuestionPartial = transcript,
                  documentQuestionError = null,
              )
            }
          },
          onFinalTranscript = { transcript ->
            documentQuestionStartJob = null
            documentQuestionSpeechListener = null
            Log.i(TAG, "Document Q&A mic stopped after transcript")
            val cleanedTranscript = transcript.trim()
            _uiState.update {
              it.copy(
                  isDocumentQuestionListening = false,
                  isDocumentAnswering = cleanedTranscript.isNotBlank(),
                  documentQuestionStatus = "Question captured",
                  documentQuestionPartial = cleanedTranscript,
                  documentLastQuestion = cleanedTranscript,
                  documentQuestionError = null,
              )
            }
            submitDocumentQuestion(cleanedTranscript)
          },
          onError = { message ->
            documentQuestionStartJob = null
            documentQuestionSpeechListener = null
            Log.i(TAG, "Document Q&A mic stopped after error: $message")
            _uiState.update {
              it.copy(
                  isDocumentQuestionListening = false,
                  documentQuestionStatus = "Question not captured",
                  documentQuestionError = message,
              )
            }
          },
      )

  private fun submitDocumentQuestion(question: String) {
    val cleanedQuestion = question.trim()
    if (cleanedQuestion.isBlank()) {
      return
    }
    val documentText = documentSessionText
    val analysis = _uiState.value.documentAnalysis
    if (documentText.isNullOrBlank() || analysis == null) {
      _uiState.update { it.copy(documentQuestionError = "Document session is not available") }
      return
    }
    val conversation = _uiState.value.documentConversation
    _uiState.update {
      it.copy(
          isDocumentAnswering = true,
          documentQuestionError = null,
          documentAnswer = null,
          documentLastQuestion = cleanedQuestion,
          documentConversation =
              it.documentConversation + ConversationTurn(ConversationRole.CUSTOMER, cleanedQuestion),
      )
    }

    viewModelScope.launch {
      try {
        val answer =
            geminiService.answerDocumentQuestion(
                documentText = documentText,
                question = cleanedQuestion,
                conversation = conversation,
            )
        _uiState.update {
          if (!it.isDocumentSessionActive) return@update it
          it.copy(
              isDocumentAnswering = false,
              documentAnswer = answer,
              documentQuestionStatus = "Answered",
              documentConversation =
                  it.documentConversation + ConversationTurn(ConversationRole.ASSISTANT, answer),
          )
        }
        speakQnaResponse(answer)
      } catch (e: Exception) {
        Log.w(TAG, "Document question failed", e)
        _uiState.update {
          if (!it.isDocumentSessionActive) return@update it
          it.copy(
              isDocumentAnswering = false,
              documentQuestionError = e.message ?: "Document question failed",
          )
        }
      }
    }
  }

  private fun clearDocumentSession() {
    documentQuestionStartJob?.cancel()
    documentQuestionStartJob = null
    documentQuestionSpeechListener?.stop(cancel = true)
    documentQuestionSpeechListener = null
    documentSessionText = null
    _uiState.update {
      it.copy(
          isDocumentSessionActive = false,
          isDocumentAnalyzing = false,
          documentScanPhase = DocumentScanPhase.IDLE,
          documentAnalysisPartial = null,
          documentGroundingText = null,
          documentAnalysis = null,
          isDocumentQuestionListening = false,
          documentQuestionStatus = null,
          documentQuestionPartial = null,
          documentLastQuestion = null,
          documentAnswer = null,
          isDocumentAnswering = false,
          documentQuestionError = null,
          documentConversation = emptyList(),
      )
    }
  }

  private fun resumeDocumentScanVoiceListener() {
    if (_uiState.value.operatingMode == StreamOperatingMode.VOICE_SESSION) {
      viewModelScope.launch {
        if (_uiState.value.isCameraActive) {
          stopCameraStreamOnly()
        }
        delay(VoiceWakeConfig.POST_TTS_COOLDOWN_MS)
        startWakeWordListener()
      }
    }
  }

  private fun extractDocumentSummaryForSpeech(analysis: DocumentAnalysisResult): String {
    val parsed = analysis.json ?: return ""
    val summary = parsed.get("summary")?.asString?.trim().orEmpty()
    if (summary.isNotBlank()) return summary
    val explanation = parsed.get("explanation")?.asString?.trim().orEmpty()
    if (explanation.isBlank()) return ""
    return explanation.substringBefore('.').ifBlank { explanation }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    // VideoFrame contains raw I420 video data in a ByteBuffer
    // Use optimized YuvToBitmapConverter for direct I420 to ARGB conversion
    val bitmap =
        YuvToBitmapConverter.convert(
            videoFrame.buffer,
            videoFrame.width,
            videoFrame.height,
        )
    if (bitmap != null) {
      presentationQueue?.enqueue(
          bitmap,
          videoFrame.presentationTimeUs,
      )
    } else {
      Log.e(TAG, "Failed to convert YUV to bitmap")
    }
  }

  private fun scheduleFaceRecognition(frameBitmap: Bitmap) {
    if (_uiState.value.operatingMode == StreamOperatingMode.VOICE_SESSION) return
    if (_uiState.value.isCapturing) return

    val now = SystemClock.elapsedRealtime()
    val recognitionIntervalMs =
        if (_uiState.value.matchedCustomer != null) {
          MATCHED_FACE_RECHECK_INTERVAL_MS
        } else {
          FACE_RECOGNITION_INTERVAL_MS
        }
    if (now - lastFaceRecognitionAtMs < recognitionIntervalMs) return
    if (faceRecognitionJob?.isActive == true) return

    val recognitionBitmap =
        try {
          frameBitmap.copy(frameBitmap.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
          Log.w(TAG, "Unable to copy frame for face recognition", e)
          return
        }

    lastFaceRecognitionAtMs = now
    Log.i(TAG, "Starting face recognition from stream frame")
    _uiState.update { state ->
      if (state.matchedCustomer != null) {
        state.copy(isFaceRecognitionRunning = true)
      } else {
        state.copy(
            isFaceRecognitionRunning = true,
            faceRecognitionStatus = appString(R.string.voice_scanning_customer),
        )
      }
    }

    faceRecognitionJob =
        viewModelScope.launch(Dispatchers.IO) {
          try {
            when (val result = faceRecognitionService.detectAndMatchFace(recognitionBitmap)) {
              is FaceRecognitionResult.Match -> {
                Log.i(
                    TAG,
                    "Face recognition result: ${result.customer.id}, similarity=${result.similarity}",
                )
                if (_uiState.value.operatingMode == StreamOperatingMode.VOICE_SESSION) {
                  _uiState.update { it.copy(isFaceRecognitionRunning = false) }
                  return@launch
                }
                consecutiveFaceMatches += 1
                // #region agent log
                DebugTrace.log(
                    location = "StreamViewModel:faceMatch",
                    message = "match detected",
                    hypothesisId = "H1",
                    data =
                        mapOf(
                            "customerId" to result.customer.id,
                            "consecutive" to consecutiveFaceMatches.toString(),
                            "required" to FACE_MATCH_CONSECUTIVE_REQUIRED.toString(),
                        ),
                )
                // #endregion
                if (consecutiveFaceMatches < FACE_MATCH_CONSECUTIVE_REQUIRED) {
                  _uiState.update {
                    it.copy(
                        isFaceRecognitionRunning = false,
                        faceRecognitionStatus = appString(R.string.voice_confirming_customer),
                    )
                  }
                  return@launch
                }
                consecutiveFaceMatches = 0
                val customer = result.customer
                _uiState.update {
                  it.copy(
                      matchedCustomer = customer,
                      isFaceRecognitionRunning = false,
                      faceRecognitionStatus = appString(R.string.voice_customer_matched),
                  )
                }
                faceRecognitionJob = null
                viewModelScope.launch {
                  stopCameraForVoiceSession(customer)
                }
              }
              is FaceRecognitionResult.NoMatch -> {
                Log.i(TAG, "Face recognition result: no match, best=${result.bestSimilarity}")
                consecutiveFaceMatches = 0
                updateFaceRecognitionStatusIfNeeded(appString(R.string.voice_face_not_in_db)) {
                  if (it.matchedCustomer == null) {
                    it.copy(matchedCustomer = null, isFaceRecognitionRunning = false, faceRecognitionStatus = appString(R.string.voice_face_not_in_db))
                  } else {
                    it.copy(isFaceRecognitionRunning = false)
                  }
                }
              }
              FaceRecognitionResult.NoFaceDetected -> {
                Log.d(TAG, "Face recognition result: no face detected")
                consecutiveFaceMatches = 0
                updateFaceRecognitionStatusIfNeeded(appString(R.string.voice_no_face_detected)) {
                  if (it.matchedCustomer == null) {
                    it.copy(matchedCustomer = null, isFaceRecognitionRunning = false, faceRecognitionStatus = appString(R.string.voice_no_face_detected))
                  } else {
                    it.copy(isFaceRecognitionRunning = false)
                  }
                }
              }
              is FaceRecognitionResult.Unavailable -> {
                Log.w(TAG, "Face recognition unavailable: ${result.reason}")
                consecutiveFaceMatches = 0
                updateFaceRecognitionStatusIfNeeded(result.reason) {
                  if (it.matchedCustomer == null) {
                    it.copy(matchedCustomer = null, isFaceRecognitionRunning = false, faceRecognitionStatus = result.reason)
                  } else {
                    it.copy(isFaceRecognitionRunning = false)
                  }
                }
              }
            }
          } catch (e: Exception) {
            Log.w(TAG, "Face recognition failed", e)
            consecutiveFaceMatches = 0
            _uiState.update {
              if (it.isCapturing) {
                return@update it.copy(isFaceRecognitionRunning = false)
              }
              if (it.matchedCustomer == null) {
                it.copy(
                    isFaceRecognitionRunning = false,
                    faceRecognitionStatus = e.message ?: "Face recognition failed",
                )
              } else {
                it.copy(isFaceRecognitionRunning = false)
              }
            }
          } finally {
            recognitionBitmap.recycle()
          }
        }
  }

  private fun updateFaceRecognitionStatusIfNeeded(
      status: String,
      transform: (StreamUiState) -> StreamUiState,
  ) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastFaceStatusUpdateAtMs < FACE_STATUS_DEBOUNCE_MS && _uiState.value.matchedCustomer != null) {
      _uiState.update { it.copy(isFaceRecognitionRunning = false) }
      return
    }
    lastFaceStatusUpdateAtMs = now
    _uiState.update(transform)
  }

  private fun handleAudioFrame(audioFrame: AudioFrame) {
    // Audio frame received from the glasses microphone (via Bluetooth HFP).
    viewModelScope.launch(Dispatchers.Main) {
      _uiState.update { it.copy(audioFrameCount = it.audioFrameCount + 1) }
    }

    recordedAudioSampleRate = audioFrame.sampleRate
    recordedAudioChannelCount = audioFrame.channelCount

    // Accumulate audio data for playback after stream stops
    try {
      val bytes = ByteArray(audioFrame.buffer.remaining())
      audioFrame.buffer.get(bytes)
      audioRecordingBuffer.write(bytes)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to record audio frame", e)
    }

    // TODO: Forward audioFrame.buffer to downstream AI analysis
  }

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto = decodePhotoData(photo)
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  private fun decodePhotoData(photo: PhotoData): Bitmap =
      when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
          val byteArray = ByteArray(photo.data.remaining())
          photo.data.get(byteArray)

          // Extract EXIF transformation matrix and apply to bitmap
          val exifInfo = getExifInfo(byteArray)
          val transform = getTransform(exifInfo)
          decodeHeic(byteArray, transform)
        }
      }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
    session?.stop()
    session = null
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
