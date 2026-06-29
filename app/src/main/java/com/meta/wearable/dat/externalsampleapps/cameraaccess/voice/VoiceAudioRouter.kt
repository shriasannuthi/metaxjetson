/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

enum class VoiceRouteKind {
  GLASSES_SCO,
  GLASSES_BLE,
  PHONE_MIC,
}

class VoiceAudioRouter(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
  companion object {
    private const val TAG = "CameraAccess:VoiceAudioRouter"
  }

  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private var focusRequest: AudioFocusRequest? = null
  private var deviceCallback: AudioDeviceCallback? = null
  private var scoReceiver: BroadcastReceiver? = null
  private var activeRoute: VoiceRouteKind? = null
  private var routeReadyCallback: ((VoiceRouteKind) -> Unit)? = null
  private var disconnectCallback: (() -> Unit)? = null
  private var routeTimeoutRunnable: Runnable? = null
  private var scoTimeoutRunnable: Runnable? = null
  private var isHeld = false
  private var scoStartedByRouter = false

  fun acquireRoute(
      onReady: (VoiceRouteKind) -> Unit,
      onDisconnect: () -> Unit,
      preferPhoneMic: Boolean = false,
      timeoutMs: Long = VoiceWakeConfig.ROUTE_CONFIRM_TIMEOUT_MS,
  ) {
    runOnMain {
      if (isHeld && !preferPhoneMic) {
        activeRoute?.let(onReady)
        return@runOnMain
      }
      if (isHeld && preferPhoneMic) {
        release()
      }
      isHeld = true
      routeReadyCallback = onReady
      disconnectCallback = onDisconnect
      requestAudioFocus()
      audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
      registerDeviceCallback()

      if (preferPhoneMic) {
        Log.i(TAG, "Using phone microphone to avoid glasses Bluetooth contention")
        finalizeRoute(VoiceRouteKind.PHONE_MIC)
        return@runOnMain
      }

      attemptRouteSelection()

      routeTimeoutRunnable =
          Runnable {
            if (activeRoute == null) {
              Log.w(TAG, "Route confirmation timed out; falling back to phone microphone")
              finalizeRoute(VoiceRouteKind.PHONE_MIC)
            }
          }
      mainHandler.postDelayed(routeTimeoutRunnable!!, timeoutMs)
    }
  }

  fun isRouteHeld(): Boolean = isHeld

  fun currentRouteKind(): VoiceRouteKind? = activeRoute

  fun release() {
    runOnMain {
      if (!isHeld) return@runOnMain
      isHeld = false
      routeReadyCallback = null
      disconnectCallback = null
      routeTimeoutRunnable?.let(mainHandler::removeCallbacks)
      routeTimeoutRunnable = null
      scoTimeoutRunnable?.let(mainHandler::removeCallbacks)
      scoTimeoutRunnable = null
      unregisterScoReceiver()
      unregisterDeviceCallback()
      abandonAudioFocus()
      if (scoStartedByRouter) {
        runCatching { audioManager.stopBluetoothSco() }
        scoStartedByRouter = false
      }
      runCatching { audioManager.clearCommunicationDevice() }
      audioManager.mode = AudioManager.MODE_NORMAL
      activeRoute = null
      Log.i(TAG, "Released voice audio route")
    }
  }

  fun routeLabel(): String? =
      when (activeRoute) {
        VoiceRouteKind.GLASSES_SCO -> "Bluetooth SCO"
        VoiceRouteKind.GLASSES_BLE -> "BLE headset"
        VoiceRouteKind.PHONE_MIC -> "Phone microphone"
        null -> null
      }

  fun isPhoneFallback(): Boolean = activeRoute == VoiceRouteKind.PHONE_MIC

  private fun attemptRouteSelection() {
    val scoDevice = findCommunicationDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    if (scoDevice != null && setCommunicationDevice(scoDevice)) {
      waitForScoConnected { finalizeRoute(VoiceRouteKind.GLASSES_SCO) }
      return
    }

    val bleDevice = findCommunicationDevice(AudioDeviceInfo.TYPE_BLE_HEADSET)
    if (bleDevice != null && setCommunicationDevice(bleDevice)) {
      finalizeRoute(VoiceRouteKind.GLASSES_BLE)
      return
    }

    if (audioManager.communicationDevice != null) {
      val type = audioManager.communicationDevice?.type
      when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
            waitForScoConnected { finalizeRoute(VoiceRouteKind.GLASSES_SCO) }
        AudioDeviceInfo.TYPE_BLE_HEADSET -> finalizeRoute(VoiceRouteKind.GLASSES_BLE)
        else -> finalizeRoute(VoiceRouteKind.PHONE_MIC)
      }
      return
    }

    Log.i(TAG, "No glasses communication device yet; waiting for callback or timeout")
  }

  private fun waitForScoConnected(onConnected: () -> Unit) {
    if (audioManager.isBluetoothScoOn) {
      onConnected()
      return
    }

    unregisterScoReceiver()
    scoReceiver =
        object : BroadcastReceiver() {
          override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
            when (
                intent.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR,
                )
            ) {
              AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                unregisterScoReceiver()
                scoTimeoutRunnable?.let(mainHandler::removeCallbacks)
                Log.i(TAG, "Bluetooth SCO audio connected")
                onConnected()
              }
              AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                Log.w(TAG, "Bluetooth SCO disconnected while connecting")
              }
            }
          }
        }
    appContext.registerReceiver(
        scoReceiver,
        IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          Context.RECEIVER_NOT_EXPORTED
        } else {
          0
        },
    )
    scoStartedByRouter = true
    audioManager.startBluetoothSco()
    scoTimeoutRunnable =
        Runnable {
          Log.w(TAG, "Bluetooth SCO connect timed out; proceeding with selected device")
          unregisterScoReceiver()
          onConnected()
        }
    mainHandler.postDelayed(scoTimeoutRunnable!!, VoiceWakeConfig.BLUETOOTH_SCO_CONNECT_TIMEOUT_MS)
  }

  private fun unregisterScoReceiver() {
    scoReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
    scoReceiver = null
  }

  private fun finalizeRoute(route: VoiceRouteKind) {
    if (activeRoute != null) return
    activeRoute = route
    routeTimeoutRunnable?.let(mainHandler::removeCallbacks)
    routeTimeoutRunnable = null
    Log.i(TAG, "Voice route active: $route")
    routeReadyCallback?.invoke(route)
  }

  private fun registerDeviceCallback() {
    if (deviceCallback != null) return
    deviceCallback =
        object : AudioDeviceCallback() {
          override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (activeRoute == null) {
              attemptRouteSelection()
            }
          }

          override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (!isHeld) return
            val lostGlassesRoute =
                removedDevices.any {
                  it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                      it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            if (lostGlassesRoute && activeRoute != VoiceRouteKind.PHONE_MIC) {
              Log.w(TAG, "Glasses audio route disconnected during voice session")
              activeRoute = null
              disconnectCallback?.invoke()
              attemptRouteSelection()
            }
          }
        }
    audioManager.registerAudioDeviceCallback(deviceCallback!!, mainHandler)
  }

  private fun unregisterDeviceCallback() {
    deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
    deviceCallback = null
  }

  private fun findCommunicationDevice(type: Int): AudioDeviceInfo? =
      audioManager.availableCommunicationDevices.firstOrNull { it.type == type }

  private fun setCommunicationDevice(device: AudioDeviceInfo): Boolean =
      runCatching { audioManager.setCommunicationDevice(device) }
          .onFailure { Log.w(TAG, "Failed to set communication device", it) }
          .getOrDefault(false)

  private fun requestAudioFocus() {
    val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { /* keep route while session active */ }
            .build()
    audioManager.requestAudioFocus(focusRequest!!)
  }

  private fun abandonAudioFocus() {
    focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    focusRequest = null
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
    } else {
      mainHandler.post(block)
    }
  }
}
