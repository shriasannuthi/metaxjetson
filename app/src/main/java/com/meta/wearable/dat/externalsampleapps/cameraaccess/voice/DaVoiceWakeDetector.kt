/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Thin wrapper around DaVoice [WakeWordDetectionAPI] for on-device wake-word spotting.
 *
 * Requires `app/libs/keyworddetection-1.0.0.aar`, a `.dm` model in assets, and a license key in
 * `local.properties`. See `app/libs/README.md`.
 */
class DaVoiceWakeDetector(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
  companion object {
    private const val TAG = "CameraAccess:DaVoiceWake"
    private const val API_CLASS = "com.davoice.keywordspotting.WakeWordDetectionAPI"
    private const val LISTENER_CLASS =
        "com.davoice.keywordspotting.WakeWordDetectionAPI\$OnKeywordDetectionListener"
  }

  private val appContext = context.applicationContext
  private var api: Any? = null
  private var isDetecting = false
  private var wakeCallback: (() -> Unit)? = null
  private var errorCallback: ((String) -> Unit)? = null

  val isLibraryPresent: Boolean
    get() =
        runCatching { Class.forName(API_CLASS) }.isSuccess

  fun start(onWake: () -> Unit, onError: (String) -> Unit) {
    runOnMain {
      wakeCallback = onWake
      errorCallback = onError

      if (!DaVoiceConfig.isConfigured()) {
        onError(
            "DaVoice not configured — set DAVOICE_LICENSE_KEY and DAVOICE_WAKE_MODEL in local.properties",
        )
        return@runOnMain
      }

      if (!isLibraryPresent) {
        onError("DaVoice AAR missing — copy keyworddetection-1.0.0.aar to app/libs/")
        return@runOnMain
      }

      try {
        ensureApi()
        val activeApi = api ?: return@runOnMain
        val instanceId = DaVoiceConfig.INSTANCE_ID

        if (!invokeBoolean(activeApi, "hasInstance", instanceId)) {
          invokeVoid(
              activeApi,
              "createInstance",
              arrayOf(String::class.java, String::class.java, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType),
              arrayOf(instanceId, DaVoiceConfig.wakeModelAsset, DaVoiceConfig.DEFAULT_THRESHOLD, DaVoiceConfig.BUFFER_COUNT),
          )
        }

        val licensed =
            invokeBoolean(
                activeApi,
                "setKeywordDetectionLicense",
                arrayOf(String::class.java, String::class.java),
                arrayOf(instanceId, DaVoiceConfig.licenseKey),
            )
        if (!licensed) {
          onError("DaVoice license rejected — check DAVOICE_LICENSE_KEY")
          return@runOnMain
        }

        invokeVoid(
            activeApi,
            "startKeywordDetection",
            arrayOf(String::class.java, Float::class.javaPrimitiveType),
            arrayOf(instanceId, DaVoiceConfig.detectionThreshold),
        )
        isDetecting = true
        Log.i(TAG, "DaVoice wake detection started (model=${DaVoiceConfig.wakeModelAsset})")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to start DaVoice wake detection", e)
        onError(e.message ?: "DaVoice start failed")
      }
    }
  }

  fun pause() {
    runOnMain {
      if (!isDetecting) return@runOnMain
      runCatching {
        api?.let { invokeVoid(it, "stopKeywordDetection", DaVoiceConfig.INSTANCE_ID) }
      }
      isDetecting = false
      Log.i(TAG, "DaVoice wake detection paused")
    }
  }

  fun resume() {
    val wake = wakeCallback ?: return
    val error = errorCallback ?: return
    if (isDetecting) return
    start(wake, error)
  }

  fun stop() {
    runOnMain {
      wakeCallback = null
      errorCallback = null
      if (isDetecting) {
        runCatching {
          api?.let { invokeVoid(it, "stopKeywordDetection", DaVoiceConfig.INSTANCE_ID) }
        }
        isDetecting = false
      }
      runCatching {
        api?.let { invokeVoid(it, "destroyInstance", DaVoiceConfig.INSTANCE_ID) }
      }
      api = null
      Log.i(TAG, "DaVoice wake detection stopped")
    }
  }

  private fun ensureApi() {
    if (api != null) return
    val apiClass = Class.forName(API_CLASS)
    val constructor = apiClass.getConstructor(Context::class.java)
    val created = constructor.newInstance(appContext)
    api = created

    val listenerClass = Class.forName(LISTENER_CLASS)
    val handler =
        InvocationHandler { _: Any?, method: Method, args: Array<out Any>? ->
          if (method.name == "onKeywordDetected") {
            Log.i(TAG, "DaVoice wake phrase detected (instance=${args?.getOrNull(0)}, phrase=${args?.getOrNull(1)})")
            mainHandler.post { wakeCallback?.invoke() }
          }
          null
        }
    val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass), handler)
    val setListener =
        apiClass.getMethod(
            "setOnKeywordDetectionListener",
            listenerClass,
        )
    setListener.invoke(created, listener)
  }

  private fun invokeVoid(target: Any, methodName: String, vararg args: Any?) {
    val argTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
    val method = target.javaClass.getMethod(methodName, *argTypes.map { normalizeType(it) }.toTypedArray())
    method.invoke(target, *args)
  }

  private fun invokeVoid(
      target: Any,
      methodName: String,
      parameterTypes: Array<Class<*>>,
      args: Array<Any?>,
  ) {
    val method = target.javaClass.getMethod(methodName, *parameterTypes)
    method.invoke(target, *args)
  }

  private fun invokeBoolean(target: Any, methodName: String, vararg args: Any?): Boolean {
    val argTypes = args.map { normalizeType(it?.javaClass ?: Any::class.java) }.toTypedArray()
    val method = target.javaClass.getMethod(methodName, *argTypes)
    return method.invoke(target, *args) as Boolean
  }

  private fun invokeBoolean(
      target: Any,
      methodName: String,
      parameterTypes: Array<Class<*>>,
      args: Array<Any?>,
  ): Boolean {
    val method = target.javaClass.getMethod(methodName, *parameterTypes)
    return method.invoke(target, *args) as Boolean
  }

  private fun normalizeType(type: Class<*>): Class<*> =
      when (type) {
        java.lang.Float::class.java -> Float::class.javaPrimitiveType!!
        java.lang.Integer::class.java -> Int::class.javaPrimitiveType!!
        java.lang.Boolean::class.java -> Boolean::class.javaPrimitiveType!!
        else -> type
      }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
    } else {
      mainHandler.post(block)
    }
  }
}
