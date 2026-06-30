/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.util.Log
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.removeStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceSessionController
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages short camera-on bursts for document scan and photo capture during voice sessions.
 */
class CameraBurstController(
    private val deviceSelector: DeviceSelector,
    private val voiceSessionController: VoiceSessionController,
) {
  companion object {
    private const val TAG = "CameraAccess:CameraBurst"
    private const val STREAMING_TIMEOUT_MS = 8_000L
    private const val SESSION_START_TIMEOUT_MS = 4_000L
    private const val BURST_FRAME_RATE = 15
    private const val IDENTIFICATION_FRAME_RATE = 7
  }

  data class CaptureEnvironment(
      var session: DeviceSession?,
      var stream: Stream?,
      val onSessionRecreated: (DeviceSession) -> Unit,
      val onStreamAttached: (Stream) -> Unit,
      val onPresentationQueueNeeded: () -> Unit,
      val onStreamCollectorsNeeded: () -> Unit,
      val getStreamState: () -> StreamState,
      val onHandoffProgress: (HandoffPhase) -> Unit,
      val onCameraActive: (Boolean) -> Unit,
      val onStreamState: (StreamState) -> Unit,
      val logFailure: (String, String?) -> Unit,
  )

  fun identificationStreamConfig(): StreamConfiguration =
      StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = IDENTIFICATION_FRAME_RATE)

  fun burstStreamConfig(): StreamConfiguration =
      StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = BURST_FRAME_RATE)

  suspend fun ensureCameraForCapture(env: CaptureEnvironment, retryOnFailure: Boolean = true): Boolean {
    if (env.getStreamState() == StreamState.STREAMING && env.stream != null) return true

    env.onHandoffProgress(HandoffPhase.PREPARING_CAPTURE)
    voiceSessionController.awaitCameraHandoff(env.onHandoffProgress)

    if (env.session == null && !recreateSession(env)) {
      env.logFailure("no_session", null)
      env.onHandoffProgress(HandoffPhase.IDLE)
      return false
    }

    if (!awaitSessionStarted(env)) {
      if (!recreateSession(env) || !awaitSessionStarted(env, 8_000L)) {
        env.logFailure("session_not_started", env.session?.state?.value?.name)
        env.onHandoffProgress(HandoffPhase.IDLE)
        return false
      }
    }

    val captureStream = attachOrReuseStream(env) ?: run {
      env.onHandoffProgress(HandoffPhase.IDLE)
      return false
    }

    env.onPresentationQueueNeeded()
    env.onStreamCollectorsNeeded()
    env.onCameraActive(true)

    val started =
        captureStream.start().fold(
            onSuccess = { true },
            onFailure = { error, _ ->
              env.logFailure("stream_start_failed", error.description)
              false
            },
        )
    if (!started) {
      env.onHandoffProgress(HandoffPhase.IDLE)
      if (retryOnFailure) {
        Log.i(TAG, "Retrying camera burst attach after start failure")
        return ensureCameraForCapture(env, retryOnFailure = false)
      }
      return false
    }

    if (!waitForStreaming(env)) {
      env.logFailure("streaming_timeout", env.getStreamState().name)
      env.onHandoffProgress(HandoffPhase.IDLE)
      if (retryOnFailure) {
        Log.i(TAG, "Retrying camera burst attach after streaming timeout")
        return ensureCameraForCapture(env, retryOnFailure = false)
      }
      return false
    }

    env.onHandoffProgress(HandoffPhase.IDLE)
    return true
  }

  private suspend fun attachOrReuseStream(env: CaptureEnvironment): Stream? {
    env.stream?.let { existing ->
      if (env.getStreamState() != StreamState.CLOSED) {
        return existing
      }
    }

    val activeSession = env.session ?: return null
    if (env.stream != null) {
      activeSession.removeStream().onFailure { error, _ ->
        Log.w(TAG, "removeStream before re-attach failed: ${error.description}")
      }
      env.stream = null
    }

    val addedStream =
        activeSession.addStream(burstStreamConfig()).fold(
            onSuccess = { it },
            onFailure = { error, _ ->
              env.logFailure("add_stream_failed", error.description)
              null
            },
        )
    if (addedStream == null) return null
    env.stream = addedStream
    env.onStreamAttached(addedStream)
    return addedStream
  }

  private suspend fun awaitSessionStarted(
      env: CaptureEnvironment,
      timeoutMs: Long = SESSION_START_TIMEOUT_MS,
  ): Boolean {
    val activeSession = env.session ?: return false
    if (activeSession.state.value == DeviceSessionState.STARTED) return true
    val ready =
        withTimeoutOrNull(timeoutMs) {
          while (activeSession.state.value != DeviceSessionState.STARTED) {
            delay(100)
          }
        }
    return ready != null
  }

  private suspend fun recreateSession(env: CaptureEnvironment): Boolean {
    Log.i(TAG, "Recreating DAT session for voice capture")
    env.session?.stop()
    env.session = null
    env.stream = null
    val createdSession =
        Wearables.createSession(deviceSelector).fold(
            onSuccess = { it },
            onFailure = { error, _ ->
              env.logFailure("create_session_failed", error.description)
              null
            },
        )
        ?: return false
    env.session = createdSession
    env.onSessionRecreated(createdSession)
    createdSession.start()
    return awaitSessionStarted(env, 8_000L)
  }

  private suspend fun waitForStreaming(env: CaptureEnvironment): Boolean {
    val reachedStreaming =
        withTimeoutOrNull(STREAMING_TIMEOUT_MS) {
          while (env.getStreamState() != StreamState.STREAMING) {
            delay(100)
          }
        }
    return reachedStreaming != null
  }
}
