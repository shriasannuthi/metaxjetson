/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AudioPlaybackDialog(
    audioData: ByteArray,
    sampleRateHz: Int,
    channelCount: Int,
    onDismiss: () -> Unit,
) {
    var isPlaying by remember { mutableStateOf(false) }
    var audioTrack by remember { mutableStateOf<AudioTrack?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            audioTrack?.stop()
            audioTrack?.release()
        }
    }

    fun playAudio() {
        if (audioTrack == null) {
            val channelMask =
                if (channelCount >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val bufferSize =
                AudioTrack.getMinBufferSize(sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(channelMask)
                    .build())
                .setBufferSizeInBytes(bufferSize.coerceAtLeast(audioData.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.write(audioData, 0, audioData.size)
        }

        audioTrack?.play()
        isPlaying = true
    }

  fun pauseAudio() {
    audioTrack?.pause()
    isPlaying = false
  }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Mic, contentDescription = null, tint = AppColor.Green)
          Spacer(modifier = Modifier.width(8.dp))
          Text("Recorded Audio")
        }
      },
      text = {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
              "Audio from glasses recorded successfully.",
              style = MaterialTheme.typography.bodyMedium
          )
          Spacer(modifier = Modifier.height(16.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            if (isPlaying) {
              IconButton(onClick = { pauseAudio() }) {
                Icon(Icons.Default.Pause, contentDescription = "Pause")
              }
            } else {
              IconButton(onClick = { playAudio() }) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play")
              }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isPlaying) "Playing..." else "Ready to play")
          }
        }
      },
      confirmButton = {
        Button(onClick = onDismiss) {
          Text("Close")
        }
      }
  )
}
