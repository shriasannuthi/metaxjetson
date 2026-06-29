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
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.os.Build
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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ai.FaceRecognitionResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ai.FaceRecognitionService
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ai.GeminiService
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.ConversationRole
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.ConversationTurn
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceCommandHandler
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceDictationCallbacks
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceDictationPort
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceSessionController
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceSessionSnapshot
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceWakeConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private const val FACE_RECOGNITION_INTERVAL_MS = 1_500L
    private const val MATCHED_FACE_RECHECK_INTERVAL_MS = 6_000L
    private const val ENABLE_RAW_AUDIO_RECORDING = false
    private const val MAX_PARTIAL_RESPONSE_CHARS = 900
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
  private var documentQuestionStartJob: Job? = null
  private var documentSessionText: String? = null
  private var lastSpokenForRepeat: String? = null
  private val voiceSessionController: VoiceSessionController =
      VoiceSessionController(
          context = application,
          commandHandler =
              object : VoiceCommandHandler {
                override fun onScanDocument() {
                  requestDocumentScan(DocumentScanTrigger.VOICE)
                }

                override fun onCapturePhoto() {
                  capturePhoto()
                }

                override fun onCancelVoiceCommand() {
                  cancelDocumentQuestionListening()
                  if (_uiState.value.isDocumentSessionActive) {
                    dismissDocumentAnalysis()
                  }
                }

                override fun getLastSpokenResponse(): String? = lastSpokenForRepeat
              },
          onSnapshotChanged = ::applyVoiceSnapshot,
      )
  private var stream: Stream? = null
  private var audioStream: AudioStream? = null
  private var previousDeviceSessionState: DeviceSessionState? = null
  private var operatingMode: StreamOperatingMode = StreamOperatingMode.CUSTOMER_IDENTIFICATION
  private var hasEnteredVoiceAssistance = false
  private var cameraToVoiceTransitionJob: Job? = null
  @Volatile private var isCameraToVoiceTransitioning = false
  private var hasEstablishedDatSession = false

  // Buffer for recording audio frames
  // Buffer for recording audio frames
  private var audioRecordingBuffer = ByteArrayOutputStream()
  private var recordedAudioSampleRate = 0
  private var recordedAudioChannelCount = 0

  // Presentation queue for buffering frames after color conversion
  private var presentationQueue: PresentationQueue? = null

  fun startStream() {
    Log.i(TAG, "Starting session in customer identification mode (camera only)")
    operatingMode = StreamOperatingMode.CUSTOMER_IDENTIFICATION
    hasEnteredVoiceAssistance = false
    hasEstablishedDatSession = false
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
    stopVoiceSession()
    clearDocumentSession()
    lastFaceRecognitionAtMs = 0L
    _uiState.update {
      it.copy(
          operatingMode = StreamOperatingMode.CUSTOMER_IDENTIFICATION,
          faceRecognitionStatus = "Scanning customer",
      )
    }
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

  private fun observeSessionState(targetSession: DeviceSession) {
    sessionStateJob?.cancel()
    sessionStateJob =
        viewModelScope.launch {
          targetSession.state.collect { currentState ->
            val prevState = previousDeviceSessionState
            previousDeviceSessionState = currentState

            if (currentState == DeviceSessionState.STARTED) {
              hasEstablishedDatSession = true
              wearablesViewModel.setDatAppUpdateRequired(false)
              if (prevState == DeviceSessionState.PAUSED && stream != null) {
                Log.d(TAG, "Session resumed from PAUSED — stream stays alive")
                return@collect
              }

              if (shouldRunCameraStream()) {
                launchCameraStream()
              }
            } else if (currentState == DeviceSessionState.PAUSED) {
              Log.d(TAG, "Session paused (tap gesture) — keeping stream alive for resume")
            }
          }
        }
  }

  private fun startStreamInternal() {
    Log.d(TAG, "startStreamInternal() - collecting session state")
    session?.let { observeSessionState(it) }
  }

  private suspend fun ensureVoiceReadySession(): Boolean {
    val currentSession = session
    val currentState = currentSession?.state?.value
    if (
        currentSession != null &&
            stream == null &&
            (currentState == DeviceSessionState.STARTED ||
                currentState == DeviceSessionState.PAUSED)
    ) {
      Log.i(TAG, "Voice-only session already active ($currentState)")
      return true
    }

    Log.i(
        TAG,
        "Preparing voice-only DAT session (sessionState=$currentState, hasStream=${stream != null})",
    )
    if (currentSession != null) {
      runCatching { currentSession.stop() }
      session = null
      delay(VoiceWakeConfig.POST_CAMERA_TO_VOICE_DELAY_MS)
    }

    var createdSession: DeviceSession? = null
    Wearables.createSession(deviceSelector)
        .onSuccess { createdSession = it }
        .onFailure { error, _ ->
          Log.e(TAG, "Failed to create voice-only session: ${error.description}")
        }
    if (createdSession == null) {
      return false
    }
    session = createdSession
    sessionErrorJob?.cancel()
    sessionErrorJob =
        viewModelScope.launch {
          createdSession!!.errors.collect { error -> handleSessionError(error) }
        }
    observeSessionState(createdSession!!)
    createdSession!!.start()

    val ready =
        withTimeoutOrNull(VoiceWakeConfig.VOICE_SESSION_READY_TIMEOUT_MS) {
          createdSession!!.state.first {
            it == DeviceSessionState.STARTED || it == DeviceSessionState.PAUSED
          }
        } != null
    if (ready) {
      hasEstablishedDatSession = true
      Log.i(TAG, "Voice-only session ready")
    } else {
      Log.e(TAG, "Voice-only session did not reach STARTED in time")
    }
    return ready
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
    stopVoiceSession()
    lastFaceRecognitionAtMs = 0L
    presentationQueue?.stop()
    presentationQueue = null
    operatingMode = StreamOperatingMode.CUSTOMER_IDENTIFICATION
    hasEnteredVoiceAssistance = false
    _uiState.update {
      it.copy(
          operatingMode = StreamOperatingMode.CUSTOMER_IDENTIFICATION,
          streamState = StreamState.STOPPED,
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
    if (isCameraToVoiceTransitioning) {
      if (error == DeviceSessionError.SESSION_ENDED_BY_DEVICE) {
        Log.w(
            TAG,
            "SESSION_ENDED_BY_DEVICE during camera→voice handoff; transition job will recover",
        )
        return
      }
    }
    handleSessionErrorNow(error)
  }

  private fun handleSessionErrorNow(error: DeviceSessionError) {
    Log.e(TAG, "Session error: ${error.description}")
    if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
      wearablesViewModel.setDatAppUpdateRequired(true)
      wearablesViewModel.setRecentError(
          getApplication<Application>().getString(R.string.update_required_dat_app_message),
      )
      stopStream()
      wearablesViewModel.navigateToDeviceSelection()
      return
    }

    if (error == DeviceSessionError.SESSION_ENDED_BY_DEVICE) {
      val message =
          if (hasEstablishedDatSession || _uiState.value.videoFrameCount > 0) {
            getApplication<Application>().getString(R.string.session_lost_reconnect_message)
          } else {
            error.description
          }
      wearablesViewModel.setRecentError(message)
      stopStream()
      wearablesViewModel.navigateToDeviceSelection()
      return
    }

    wearablesViewModel.setRecentError(error.description)
    stopStream()
    wearablesViewModel.navigateToDeviceSelection()
  }

  private fun shouldRunCameraStream(): Boolean =
      operatingMode == StreamOperatingMode.CUSTOMER_IDENTIFICATION ||
          operatingMode == StreamOperatingMode.DOCUMENT_CAPTURE

  private fun setOperatingMode(mode: StreamOperatingMode) {
    operatingMode = mode
    _uiState.update { it.copy(operatingMode = mode) }
  }

  private fun launchCameraStream() {
    if (stream != null) {
      Log.d(TAG, "Camera stream already active")
      return
    }
    val activeSession = session ?: return
    Log.i(TAG, "Launching camera stream for mode=$operatingMode")
    videoJob?.cancel()
    stateJob?.cancel()
    errorJob?.cancel()

    activeSession
        .addStream(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 15))
        ?.onSuccess { addedStream ->
          stream = addedStream
          videoJob = viewModelScope.launch {
            Log.d(TAG, "Collecting video frames from stream")
            addedStream.videoStream.collect { handleVideoFrame(it) }
            Log.d(TAG, "Video stream collection ended")
          }

          if (ENABLE_RAW_AUDIO_RECORDING) {
            val glassesAudio = BluetoothMicAudioStream(getApplication())
            audioStream = glassesAudio
            audioJob = viewModelScope.launch {
              glassesAudio.audioStream.collect { handleAudioFrame(it) }
            }
            glassesAudio.start()
          }

          stateJob = viewModelScope.launch {
            addedStream.state.collect { streamState ->
              val prevStreamState = _uiState.value.streamState
              Log.d(TAG, "Stream state changed: $prevStreamState -> $streamState")
              _uiState.update { it.copy(streamState = streamState) }

              if (streamState == StreamState.STREAMING) {
                hasEstablishedDatSession = true
              }

              val wasActive = prevStreamState !in SESSION_TERMINAL_STATES
              val isTerminated = streamState in SESSION_TERMINAL_STATES
              if (wasActive && isTerminated) {
                Log.d(TAG, "Terminal stream state reached")
                if (operatingMode == StreamOperatingMode.CUSTOMER_IDENTIFICATION) {
                  stopStream()
                  wearablesViewModel.navigateToDeviceSelection()
                } else {
                  detachCameraStream()
                }
              }
            }
          }
          errorJob = viewModelScope.launch {
            addedStream.errorStream.collect { error ->
              Log.d(TAG, "Stream error received: $error (description: ${error.description})")
              if (error == StreamError.STREAM_ERROR) {
                return@collect
              }
              if (operatingMode == StreamOperatingMode.CUSTOMER_IDENTIFICATION) {
                stopStream()
                wearablesViewModel.navigateToDeviceSelection()
              } else {
                detachCameraStream()
              }
              wearablesViewModel.setRecentError(error.description)
            }
          }
          addedStream.start()
        }
        ?.onFailure { error, _ ->
          Log.e(TAG, "Failed to add stream to session: ${error.description}")
        }
  }

  private fun detachCameraStream() {
    detachCameraStreamInternal()
  }

  private suspend fun detachCameraStreamAndSettle() {
    val activeStream = detachCameraStreamInternal()
    if (activeStream != null) {
      withTimeoutOrNull(VoiceWakeConfig.STREAMING_READY_TIMEOUT_MS) {
        activeStream.state.first {
          it == StreamState.STOPPED || it == StreamState.CLOSED
        }
      }
    }
    resetPhoneAudioForBluetoothHandoff()
    Log.i(
        TAG,
        "Waiting ${VoiceWakeConfig.POST_CAMERA_TO_VOICE_DELAY_MS}ms for Bluetooth handoff after camera release",
    )
    delay(VoiceWakeConfig.POST_CAMERA_TO_VOICE_DELAY_MS)
  }

  private fun detachCameraStreamInternal(): Stream? {
    Log.i(TAG, "Detaching camera stream via session.removeStream()")
    val activeStream = stream
    val activeSession = session
    videoJob?.cancel()
    videoJob = null
    audioJob?.cancel()
    audioJob = null
    stateJob?.cancel()
    stateJob = null
    errorJob?.cancel()
    errorJob = null
    faceRecognitionJob?.cancel()
    faceRecognitionJob = null
    audioStream?.stop()
    audioStream = null
    stream = null
    if (activeSession != null && activeStream != null) {
      activeSession
          .removeStream()
          .onSuccess { Log.i(TAG, "Camera stream removed from DAT session") }
          .onFailure { error, _ ->
            Log.w(TAG, "removeStream failed (${error.description}); stopping stream directly")
            activeStream.stop()
          }
    } else {
      activeStream?.stop()
    }
    _uiState.update { it.copy(streamState = StreamState.STOPPED, videoFrame = null) }
    return activeStream
  }

  private fun resetPhoneAudioForBluetoothHandoff() {
    val audioManager =
        getApplication<Application>().getSystemService(AudioManager::class.java) ?: return
    runCatching { audioManager.stopBluetoothSco() }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      runCatching { audioManager.clearCommunicationDevice() }
    }
    audioManager.mode = AudioManager.MODE_NORMAL
    Log.i(TAG, "Reset phone audio mode before voice route acquisition")
  }

  private suspend fun waitForStreaming(): Boolean {
    val activeStream = stream ?: return false
    if (activeStream.state.value == StreamState.STREAMING) return true
    return withTimeoutOrNull(VoiceWakeConfig.STREAMING_READY_TIMEOUT_MS) {
      activeStream.state.first { it == StreamState.STREAMING }
      true
    } == true
  }

  private suspend fun ensureCameraForCapture(): Boolean {
    if (stream?.state?.value == StreamState.STREAMING) return true
    launchCameraStream()
    return waitForStreaming()
  }

  private suspend fun releaseCameraAfterCapture() {
    detachCameraStreamAndSettle()
  }

  private fun transitionToVoiceAssistanceMode(fromCustomerIdentification: Boolean = false) {
    if (fromCustomerIdentification) {
      if (hasEnteredVoiceAssistance) return
      hasEnteredVoiceAssistance = true
      Log.i(TAG, "Customer identified — switching to voice assistance (camera off)")
      _uiState.update {
        it.copy(faceRecognitionStatus = "Customer identified — preparing voice")
      }
    }
    cameraToVoiceTransitionJob?.cancel()
    cameraToVoiceTransitionJob =
        viewModelScope.launch {
          isCameraToVoiceTransitioning = true
          try {
            setOperatingMode(StreamOperatingMode.VOICE_ASSISTANCE)
            presentationQueue?.stop()
            faceRecognitionJob?.cancel()
            faceRecognitionJob = null
            detachCameraStreamAndSettle()
            if (!ensureVoiceReadySession()) {
              Log.e(TAG, "Could not establish voice-only session after customer match")
              _uiState.update {
                it.copy(
                    voiceCommandStatus = "Reconnecting to glasses…",
                    faceRecognitionStatus = "Voice handoff failed — tap Stop and retry",
                )
              }
              wearablesViewModel.setRecentError(
                  getApplication<Application>().getString(R.string.session_lost_reconnect_message),
              )
              return@launch
            }
            _uiState.update {
              it.copy(faceRecognitionStatus = "Customer identified — voice active")
            }
            delay(VoiceWakeConfig.POST_ROUTE_STABILIZATION_MS)
            voiceSessionController.startVoicePhase(preferPhoneMic = false)
            Log.i(TAG, "Voice phase started on glasses after customer match")
          } finally {
            isCameraToVoiceTransitioning = false
          }
        }
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }

    viewModelScope.launch {
      val wasVoiceMode = operatingMode == StreamOperatingMode.VOICE_ASSISTANCE
      if (wasVoiceMode) {
        stopVoiceSession()
        setOperatingMode(StreamOperatingMode.DOCUMENT_CAPTURE)
      }
      if (!ensureCameraForCapture()) {
        Log.w(TAG, "Cannot capture photo: camera stream not ready")
        if (wasVoiceMode) {
          releaseCameraAfterCapture()
          transitionToVoiceAssistanceMode()
        }
        return@launch
      }

      Log.d(TAG, "Starting photo capture")
      _uiState.update { it.copy(isCapturing = true) }
      stream
          ?.capturePhoto()
          ?.onSuccess { photoData ->
            Log.d(TAG, "Photo capture successful")
            handlePhotoData(photoData)
            _uiState.update { it.copy(isCapturing = false) }
          }
          ?.onFailure { error, _ ->
            Log.e(TAG, "Photo capture failed: ${error.description}")
            _uiState.update { it.copy(isCapturing = false) }
          }

      if (wasVoiceMode) {
        releaseCameraAfterCapture()
        transitionToVoiceAssistanceMode()
      }
    }
  }

  private fun shouldPreferPhoneMicForVoice(): Boolean =
      stream != null ||
          operatingMode == StreamOperatingMode.CUSTOMER_IDENTIFICATION ||
          operatingMode == StreamOperatingMode.DOCUMENT_CAPTURE

  private fun startVoiceSession() {
    voiceSessionController.startVoicePhase(preferPhoneMic = shouldPreferPhoneMicForVoice())
    Log.i(
        TAG,
        "Voice phase resumed (preferPhoneMic=${shouldPreferPhoneMicForVoice()})",
    )
  }

  fun listenForVoiceTest() {
    if (isCameraToVoiceTransitioning) {
      Log.i(TAG, "Ignoring manual voice test during camera→voice handoff")
      _uiState.update { it.copy(voiceCommandStatus = "Preparing voice after customer match…") }
      return
    }
    if (operatingMode == StreamOperatingMode.CUSTOMER_IDENTIFICATION && stream != null) {
      Log.w(TAG, "Manual voice test blocked while camera stream is active")
      _uiState.update {
        it.copy(voiceCommandStatus = "Camera active — wait for customer match")
      }
      return
    }
    val state = _uiState.value
    if (state.isDocumentSessionActive && state.documentAnalysis != null) {
      Log.i(TAG, "Mic button routed to document Q&A")
      startDocumentQuestionListening()
      return
    }
    val preferPhoneMic = shouldPreferPhoneMicForVoice()
    Log.i(TAG, "Manual voice test requested (preferPhoneMic=$preferPhoneMic)")
    _uiState.update {
      it.copy(
          isVoiceCommandListening = true,
          voiceCommandStatus =
              if (preferPhoneMic) {
                "Listening on phone mic…"
              } else {
                "Listening… say \"${VoiceWakeConfig.WAKE_PHRASE}\""
              },
          voiceTranscript = null,
      )
    }
    voiceSessionController.restartWakeListening(preferPhoneMic = preferPhoneMic)
  }

  fun pauseVoiceCommandsForAssistant() {
    Log.i(TAG, "Pausing wake listening for RM assistant capture")
    voiceSessionController.stopVoicePhase()
    _uiState.update { it.copy(voiceCommandStatus = "RM assistant listening") }
  }

  fun resumeVoiceCommandsAfterAssistant() {
    if (operatingMode == StreamOperatingMode.VOICE_ASSISTANCE) {
      Log.i(TAG, "Resuming wake listening after RM assistant capture")
      startVoiceSession()
    }
  }

  private fun stopVoiceSession() {
    voiceSessionController.stopVoicePhase()
    _uiState.update {
      it.copy(
          isVoiceCommandListening = false,
          voiceCommandStatus = null,
          voiceTranscript = null,
          voiceSessionState = VoiceSessionState.Idle,
          voiceMatchedIntent = null,
      )
    }
  }

  val dictationPort: VoiceDictationPort
    get() = voiceSessionController

  private fun applyVoiceSnapshot(snapshot: VoiceSessionSnapshot) {
    _uiState.update {
      it.copy(
          isVoiceCommandListening = snapshot.isWakeListening,
          voiceCommandStatus = snapshot.statusMessage,
          voiceTranscript = snapshot.finalTranscript ?: snapshot.partialTranscript,
          voiceSessionState = snapshot.state,
          voiceMatchedIntent = snapshot.matchedIntent,
          voiceRecognitionProvider = snapshot.diagnostics.recognitionProvider,
          voicePhoneAudioFallback = snapshot.diagnostics.phoneAudioFallback,
          voiceDiagnosticsError = snapshot.diagnostics.lastError,
      )
    }
  }

  private fun speakVoiceResponse(text: String, onComplete: (() -> Unit)? = null) {
    lastSpokenForRepeat = text
    voiceSessionController.speak(text) { onComplete?.invoke() }
  }

  private fun speakVoiceSummary(text: String, onComplete: (() -> Unit)? = null) {
    lastSpokenForRepeat = text
    voiceSessionController.speakSummary(text) { onComplete?.invoke() }
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
    if (trigger == DocumentScanTrigger.BUTTON || trigger == DocumentScanTrigger.VOICE) {
      stopVoiceSession()
    }
    Log.i(TAG, "Document scan: pausing voice and opening camera briefly")
    clearDocumentSession()
    setOperatingMode(StreamOperatingMode.DOCUMENT_CAPTURE)
    _uiState.update { it.copy(voiceCommandStatus = trigger.requestedStatus) }
    captureAndAnalyzeDocument()
  }

  private fun captureAndAnalyzeDocument() {
    Log.i(TAG, "Document scan flow starting")
    if (!geminiService.isConfigured()) {
      Log.w(TAG, "Skipping document scan because GEMINI_API_KEY is not configured")
      _uiState.update {
        it.copy(voiceCommandStatus = "Add GEMINI_API_KEY to scan documents")
      }
      transitionToVoiceAssistanceMode()
      return
    }

    viewModelScope.launch {
      if (!ensureCameraForCapture()) {
        Log.w(TAG, "Cannot scan document: camera stream not ready")
        _uiState.update { it.copy(voiceCommandStatus = "Camera not ready for document scan") }
        transitionToVoiceAssistanceMode()
        return@launch
      }

      val activeStream = stream
      if (activeStream == null) {
        _uiState.update { it.copy(voiceCommandStatus = "Camera not ready") }
        transitionToVoiceAssistanceMode()
        return@launch
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

        releaseCameraAfterCapture()

        _uiState.update {
          it.copy(
              documentScanPhase = DocumentScanPhase.GROUNDING,
              isCapturing = false,
              voiceCommandStatus = "Reading document",
              documentQuestionStatus = "Reading document",
          )
        }

        val groundingStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "Starting document grounding from captured photo: bitmap=${documentBitmap.width}x${documentBitmap.height}",
        )
        val groundedText =
            withContext(Dispatchers.IO) {
              geminiService.transcribeDocumentImage(documentBitmap) { partialText ->
                _uiState.update { state ->
                  state.copy(
                      documentAnalysisPartial = partialText.take(MAX_PARTIAL_RESPONSE_CHARS),
                      documentGroundingText = partialText,
                  )
                }
              }
            }
        val groundingDurationMs = SystemClock.elapsedRealtime() - groundingStartedAtMs
        documentSessionText = groundedText
        _uiState.update {
          it.copy(
              documentScanPhase = DocumentScanPhase.ANALYZING,
              voiceCommandStatus = "Generating explanation",
              documentQuestionStatus = "Generating document explanation",
              documentAnalysisPartial = null,
              documentGroundingText = groundedText,
          )
        }

        val analysisStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "Starting document analysis from grounded text: chars=${groundedText.length}",
        )
        val analysis =
            withContext(Dispatchers.IO) {
              geminiService.analyzeDocument(groundedText) { partialText ->
                _uiState.update { state ->
                  state.copy(documentAnalysisPartial = partialText.take(MAX_PARTIAL_RESPONSE_CHARS))
                }
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
        transitionToVoiceAssistanceMode()
      } catch (e: Exception) {
        val totalDurationMs = SystemClock.elapsedRealtime() - scanStartedAtMs
        Log.w(
            TAG,
            "Document analysis failed after ${totalDurationMs}ms: ${e.javaClass.simpleName}: ${e.message}",
            e,
        )
        releaseCameraAfterCapture()
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
        speakVoiceResponse("Document scan failed.") { transitionToVoiceAssistanceMode() }
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
    if (stream != null || operatingMode == StreamOperatingMode.DOCUMENT_CAPTURE) {
      _uiState.update { it.copy(documentQuestionError = "Camera is active — finish capture first") }
      return
    }

    Log.i(TAG, "Document Q&A: switching to dictation on pinned voice route")
    documentQuestionStartJob?.cancel()
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
          delay(VoiceWakeConfig.MIC_ROUTE_STABILIZATION_MS)
          if (!_uiState.value.isDocumentSessionActive) return@launch
          voiceSessionController.startDictation(
              callbacks =
                  object : VoiceDictationCallbacks {
                    override fun onReady() {
                      _uiState.update {
                        it.copy(
                            isDocumentQuestionListening = true,
                            documentQuestionStatus = "Listening for your question",
                            documentQuestionPartial = null,
                            documentQuestionError = null,
                        )
                      }
                    }

                    override fun onPartialTranscript(text: String) {
                      _uiState.update {
                        it.copy(
                            isDocumentQuestionListening = true,
                            documentQuestionStatus = "Capturing question",
                            documentQuestionPartial = text,
                            documentQuestionError = null,
                        )
                      }
                    }

                    override fun onFinalTranscript(text: String) {
                      Log.i(TAG, "Document Q&A mic stopped after transcript")
                      val cleanedTranscript = text.trim()
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
                    }

                    override fun onError(message: String) {
                      Log.i(TAG, "Document Q&A mic stopped after error: $message")
                      _uiState.update {
                        it.copy(
                            isDocumentQuestionListening = false,
                            documentQuestionStatus = "Question not captured",
                            documentQuestionError = message,
                        )
                      }
                      startVoiceSession()
                    }
                  },
              resumeWakeAfter = true,
              preferPhoneMic = false,
          )
        }
  }

  fun stopDocumentQuestionListeningAndUsePartial() {
    voiceSessionController.stopDictation(cancel = false)
  }

  fun cancelDocumentQuestionListening() {
    documentQuestionStartJob?.cancel()
    documentQuestionStartJob = null
    voiceSessionController.stopDictation(cancel = true)
    Log.i(TAG, "Document Q&A mic stopped after cancel")
    _uiState.update {
      it.copy(
          isDocumentQuestionListening = false,
          documentQuestionStatus = "Question cancelled",
          documentQuestionPartial = null,
      )
    }
    startVoiceSession()
  }

  fun endDocumentSession() {
    clearDocumentSession()
    startVoiceSession()
    Log.i(TAG, "Document session ended: wake listening resumed")
  }

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
        speakVoiceSummary(answer) { startVoiceSession() }
      } catch (e: Exception) {
        Log.w(TAG, "Document question failed", e)
        _uiState.update {
          if (!it.isDocumentSessionActive) return@update it
          it.copy(
              isDocumentAnswering = false,
              documentQuestionError = e.message ?: "Document question failed",
          )
        }
        speakVoiceResponse("I couldn't answer that question.") { startVoiceSession() }
      }
    }
  }

  private fun clearDocumentSession() {
    documentQuestionStartJob?.cancel()
    documentQuestionStartJob = null
    voiceSessionController.stopDictation(cancel = false)
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
    if (operatingMode != StreamOperatingMode.CUSTOMER_IDENTIFICATION) return
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
        state.copy(isFaceRecognitionRunning = true, faceRecognitionStatus = "Scanning customer")
      }
    }

    // TODO: Replace with Meta DAT Glasses camera stream once glasses hardware is swapped in.
    faceRecognitionJob =
        viewModelScope.launch(Dispatchers.IO) {
          try {
            when (val result = faceRecognitionService.detectAndMatchFace(recognitionBitmap)) {
              is FaceRecognitionResult.Match -> {
                Log.i(
                    TAG,
                    "Face recognition result: ${result.customer.id}, similarity=${result.similarity}",
                )
                val isNewMatch =
                    !_uiState.value.matchedCustomer?.id.equals(result.customer.id, ignoreCase = true)
                _uiState.update { state ->
                  state.copy(
                      matchedCustomer = result.customer,
                      isFaceRecognitionRunning = false,
                      faceRecognitionStatus = "Customer matched",
                  )
                }
                if (isNewMatch) {
                  withContext(Dispatchers.Main) {
                    transitionToVoiceAssistanceMode(fromCustomerIdentification = true)
                  }
                }
              }
              is FaceRecognitionResult.NoMatch -> {
                Log.i(TAG, "Face recognition result: no match, best=${result.bestSimilarity}")
                _uiState.update {
                  it.copy(
                      matchedCustomer = null,
                      isFaceRecognitionRunning = false,
                      faceRecognitionStatus = "Face not in customer DB",
                  )
                }
              }
              FaceRecognitionResult.NoFaceDetected -> {
                Log.d(TAG, "Face recognition result: no face detected")
                _uiState.update {
                  it.copy(
                      matchedCustomer = null,
                      isFaceRecognitionRunning = false,
                      faceRecognitionStatus = "No face detected",
                  )
                }
              }
              is FaceRecognitionResult.Unavailable -> {
                Log.w(TAG, "Face recognition unavailable: ${result.reason}")
                _uiState.update {
                  it.copy(
                      matchedCustomer = null,
                      isFaceRecognitionRunning = false,
                      faceRecognitionStatus = result.reason,
                  )
                }
              }
            }
          } catch (e: Exception) {
            Log.w(TAG, "Face recognition failed", e)
            _uiState.update {
              if (it.isCapturing) {
                return@update it.copy(isFaceRecognitionRunning = false)
              }
              it.copy(
                  isFaceRecognitionRunning = false,
                  faceRecognitionStatus = e.message ?: "Face recognition failed",
              )
            }
          } finally {
            recognitionBitmap.recycle()
          }
        }
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
    voiceSessionController.shutdown()
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
