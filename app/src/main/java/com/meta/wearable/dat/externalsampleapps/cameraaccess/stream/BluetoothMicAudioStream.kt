/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * BluetoothMicAudioStream - Captures real audio from Meta glasses microphones.
 *
 * The DAT SDK does not expose a dedicated audio streaming API. Per Meta's documentation
 * (https://wearables.developer.meta.com/docs/microphones-and-speakers/), glasses
 * microphone/speaker audio is delivered over the standard Bluetooth Hands-Free Profile (HFP),
 * shared with the system Bluetooth stack — not through Stream/DeviceSession. This class routes
 * the phone's audio input to the glasses over Bluetooth SCO and records from it with the
 * standard AudioRecord API.
 *
 * AudioRecord.read() is a blocking call, so all reading happens on Dispatchers.IO via flowOn() —
 * never call this on Dispatchers.Main, or it will freeze the UI thread.
 *
 * Requires RECORD_AUDIO + BLUETOOTH_CONNECT permissions to be granted, and the glasses connected
 * as a Bluetooth audio (HFP) accessory.
 */
class BluetoothMicAudioStream(context: Context) : AudioStream {

  companion object {
    private const val TAG = "CameraAccess:BluetoothMicAudioStream"

    // HFP streams audio at 8kHz mono - see Meta's microphones-and-speakers documentation.
    private const val SAMPLE_RATE_HZ = 8000
    private const val CHANNEL_COUNT = 1
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val BYTES_PER_SAMPLE = 2
    private const val FRAME_DURATION_MS = 20L

    // How long to back off after a failed mic/device acquisition attempt before retrying.
    private const val RETRY_DELAY_MS = 500L
  }

  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val bytesPerFrame =
    ((SAMPLE_RATE_HZ * FRAME_DURATION_MS) / 1000).toInt() * CHANNEL_COUNT * BYTES_PER_SAMPLE

  // Written from the caller's thread (start/stop), read from the IO-dispatched flow below.
  @Volatile private var isStreaming = false

  // Only ever touched from within the flow's coroutine (confined by flowOn), so no extra
  // synchronization is needed here despite the flow possibly hopping across IO pool threads.
  private var audioRecord: AudioRecord? = null

  override val audioStream: Flow<AudioFrame> = flow {
    val readBuffer = ByteArray(bytesPerFrame)
    var timestampUs = 0L

    try {
      while (coroutineContext.isActive) {
        if (!isStreaming) {
          releaseAudioRecordIfNeeded()
          delay(FRAME_DURATION_MS)
          continue
        }

        val record = audioRecord ?: createAudioRecord()?.also { audioRecord = it }
        if (record == null) {
          // Missing permission, or no glasses currently on the Bluetooth SCO profile.
          // Back off instead of spinning.
          delay(RETRY_DELAY_MS)
          continue
        }

        // Blocking call by design — safe here because this flow runs on Dispatchers.IO
        // (see flowOn() below), never on the caller's original dispatcher.
        val bytesRead = record.read(readBuffer, 0, readBuffer.size)

        if (!isStreaming) {
          // stop() was requested while this read was in flight — drop the frame and let the
          // next loop iteration clean up the record.
          continue
        }

        if (bytesRead > 0) {
          val buffer = ByteBuffer.allocateDirect(bytesRead)
          buffer.put(readBuffer, 0, bytesRead)
          buffer.flip()
          emit(AudioFrame(buffer, timestampUs, SAMPLE_RATE_HZ, CHANNEL_COUNT))
          timestampUs +=
            (bytesRead.toLong() * 1_000_000L) / (SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE)
        }
      }
    } finally {
      releaseAudioRecordIfNeeded()
    }
  }.flowOn(Dispatchers.IO)

  override fun start() {
    if (isStreaming) return
    routeAudioToGlasses()
    isStreaming = true
  }

  override fun stop() {
    // Only flip the flag and undo routing here. The AudioRecord itself is created and torn
    // down exclusively inside the flow's own coroutine (see releaseAudioRecordIfNeeded), so we
    // never call stop()/release() on it from a different thread than the one reading it.
    isStreaming = false
    restoreAudioRouting()
  }

  private fun createAudioRecord(): AudioRecord? {
    val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
    if (minBufferSize <= 0) {
      Log.e(TAG, "Unsupported AudioRecord configuration for glasses microphone")
      return null
    }

    val record =
      try {
        AudioRecord(
          MediaRecorder.AudioSource.VOICE_COMMUNICATION,
          SAMPLE_RATE_HZ,
          CHANNEL_CONFIG,
          AUDIO_FORMAT,
          maxOf(minBufferSize, bytesPerFrame * 4),
        )
      } catch (e: SecurityException) {
        Log.e(TAG, "RECORD_AUDIO permission not granted", e)
        return null
      }

    if (record.state != AudioRecord.STATE_INITIALIZED) {
      Log.e(TAG, "Failed to initialize AudioRecord for glasses microphone")
      record.release()
      return null
    }

    record.startRecording()
    return record
  }

  private fun releaseAudioRecordIfNeeded() {
    val record = audioRecord ?: return
    audioRecord = null
    try {
      record.stop()
    } catch (e: IllegalStateException) {
      Log.w(TAG, "AudioRecord was already stopped", e)
    }
    record.release()
  }

  // Routes phone audio input/output to the glasses over Bluetooth SCO, as recommended by
  // https://wearables.developer.meta.com/docs/microphones-and-speakers/
  private fun routeAudioToGlasses() {
    val scoDevice =
      audioManager.availableCommunicationDevices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
      }
    if (scoDevice == null) {
      Log.w(TAG, "No glasses found on the Bluetooth SCO audio profile")
      return
    }
    audioManager.mode = AudioManager.MODE_NORMAL
    if (!audioManager.setCommunicationDevice(scoDevice)) {
      Log.w(TAG, "Failed to route audio to the glasses microphone")
    }
  }

  private fun restoreAudioRouting() {
    audioManager.clearCommunicationDevice()
  }
}