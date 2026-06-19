package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import java.nio.ByteBuffer

/**
 * AudioFrame - Representation of a single audio frame captured from the wearable device's
 * microphone, delivered to the phone over the Bluetooth Hands-Free Profile (HFP).
 */
data class AudioFrame(
    val buffer: ByteBuffer,
    val presentationTimeUs: Long,
    val sampleRate: Int = 8000,
    val channelCount: Int = 1
)

/** RecordedAudio - A buffered recording plus the format needed to play it back correctly. */
data class RecordedAudio(
    val data: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int,
)