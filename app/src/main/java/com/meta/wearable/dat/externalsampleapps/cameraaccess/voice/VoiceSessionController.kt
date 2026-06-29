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
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.Executor

/**
 * Manages a single pinned microphone route for the voice-assistance phase.
 *
 * Bluetooth HFP/SCO must not be acquired and released repeatedly while a DAT session is active —
 * that pattern drops the glasses session. This controller keeps one audio route open from
 * customer identification through wake commands, document Q&A, and TTS, and only swaps the
 * [SpeechRecognizer] between wake and dictation modes.
 */
class VoiceSessionController(
    context: Context,
    private val commandHandler: VoiceCommandHandler,
    private val onSnapshotChanged: (VoiceSessionSnapshot) -> Unit,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    mainExecutor: Executor = Executor { mainHandler.post(it) },
) : VoiceDictationPort {
  companion object {
    private const val TAG = "CameraAccess:VoiceSession"
    private const val BASE_BACKOFF_MS = 800L
    private const val MAX_BACKOFF_MS = 8_000L
  }

  private enum class SessionMode {
    IDLE,
    WAKE,
    DICTATION,
  }

  private val appContext = context.applicationContext
  private val audioRouter = VoiceAudioRouter(appContext, mainHandler)
  private val resolver = VoiceCommandResolver()
  private val earcons = VoiceEarconPlayer()
  private val vibrator = appContext.getSystemService(Vibrator::class.java)
  private val tts =
      VoiceTextToSpeechEngine(appContext) { ready ->
        if (!ready) {
          updateSnapshot { it.copy(statusMessage = "Speech playback unavailable on phone") }
        }
      }

  private var recognizer: VoiceRecognizerEngine? = null
  private var snapshot = VoiceSessionSnapshot()
  private var mode = SessionMode.IDLE
  private var voicePhaseActive = false
  private var wakeRequested = false
  private var resumeWakeAfterDictation = false
  private var dictationCallbacks: VoiceDictationCallbacks? = null
  private var lastSpokenResponse: String? = null
  private var executedPartialKey: String? = null
  private var immediateRetryUsed = false
  private var consecutiveFailures = 0
  private var pendingResumeWake = false
  private var activeRouteKind: VoiceRouteKind? = null
  private var preferPhoneMicForSession = false

  /** Enter the voice-assistance phase with a single long-lived audio route. */
  fun startVoicePhase(preferPhoneMic: Boolean = false) {
    runOnMain {
      preferPhoneMicForSession = preferPhoneMic
      voicePhaseActive = true
      wakeRequested = true
      resumeWakeAfterDictation = false
      if (mode == SessionMode.DICTATION) {
        endDictation(notifyCancel = false)
      }
      if (snapshot.state == VoiceSessionState.Speaking) {
        pendingResumeWake = true
        return@runOnMain
      }
      if (audioRouter.isRouteHeld() && activeRouteKind != null && !needsRouteSwitch(preferPhoneMic)) {
        switchToWakeListening()
      } else {
        beginWakeSession(preferPhoneMic = preferPhoneMic)
      }
    }
  }

  fun startWakeListening(preferPhoneMic: Boolean = false) {
    startVoicePhase(preferPhoneMic)
  }

  /** Tear down the entire voice phase, including the pinned Bluetooth route. */
  fun stopVoicePhase() {
    runOnMain {
      voicePhaseActive = false
      wakeRequested = false
      pendingResumeWake = false
      resumeWakeAfterDictation = false
      endDictation(notifyCancel = false)
      transitionTo(VoiceSessionState.Idle, "Voice stopped")
      releaseAudioRoute()
    }
  }

  fun stop() {
    stopVoicePhase()
  }

  /** Restart wake listening without releasing the pinned audio route. */
  fun restartWakeListening(preferPhoneMic: Boolean = false) {
    runOnMain {
      preferPhoneMicForSession = preferPhoneMic
      wakeRequested = true
      voicePhaseActive = true
      if (needsRouteSwitch(preferPhoneMic)) {
        releaseAudioRoute()
        beginWakeSession(preferPhoneMic = preferPhoneMic)
        return@runOnMain
      }
      if (audioRouter.isRouteHeld() && activeRouteKind != null) {
        switchToWakeListening()
      } else {
        beginWakeSession(preferPhoneMic = preferPhoneMic)
      }
    }
  }

  fun restartWakeListeningNow(preferPhoneMic: Boolean = false) {
    restartWakeListening(preferPhoneMic)
  }

  override fun startDictation(callbacks: VoiceDictationCallbacks, resumeWakeAfter: Boolean) {
    startDictation(callbacks, resumeWakeAfter, preferPhoneMic = preferPhoneMicForSession)
  }

  fun startDictation(
      callbacks: VoiceDictationCallbacks,
      resumeWakeAfter: Boolean,
      preferPhoneMic: Boolean,
  ) {
    runOnMain {
      preferPhoneMicForSession = preferPhoneMic
      voicePhaseActive = true
      wakeRequested = false
      resumeWakeAfterDictation = resumeWakeAfter
      dictationCallbacks = callbacks
      mode = SessionMode.DICTATION
      pendingResumeWake = false
      stopRecognizerOnly()
      transitionTo(VoiceSessionState.Routing, "Preparing microphone")

      val onRouteReady = { route: VoiceRouteKind ->
        activeRouteKind = route
        updateDiagnostics(route)
        ensureRecognizer(forDictation = true, routeKind = route) {
          transitionTo(VoiceSessionState.Listening, "Listening for your question")
          scheduleStartListening(forDictation = true)
        }
      }

      if (audioRouter.isRouteHeld() && activeRouteKind != null && !needsRouteSwitch(preferPhoneMic)) {
        onRouteReady(activeRouteKind!!)
      } else {
        if (audioRouter.isRouteHeld()) {
          releaseAudioRoute()
        }
        audioRouter.acquireRoute(
            preferPhoneMic = preferPhoneMic,
            onReady = onRouteReady,
            onDisconnect = {
              updateSnapshot {
                it.copy(statusMessage = "Glasses audio disconnected; retrying route")
              }
            },
        )
      }
    }
  }

  override fun stopDictation(cancel: Boolean) {
    runOnMain {
      if (!cancel) {
        val partial = snapshot.partialTranscript ?: snapshot.finalTranscript
        if (!partial.isNullOrBlank()) {
          val callbacks = dictationCallbacks
          recognizer?.stopListening(cancel = false)
          dictationCallbacks = null
          mode = SessionMode.IDLE
          callbacks?.onFinalTranscript(partial.trim())
          resumeWakeAfterDictationIfNeeded()
          return@runOnMain
        }
      }
      endDictation(notifyCancel = cancel)
      resumeWakeAfterDictationIfNeeded()
    }
  }

  fun speak(text: String, onComplete: (() -> Unit)? = null) {
    runOnMain {
      if (text.isNotBlank()) {
        lastSpokenResponse = text
      }
      transitionTo(VoiceSessionState.Speaking, "Speaking response")
      stopRecognizerOnly()
      tts.speak(text) {
        mainHandler.postDelayed(
            {
              onComplete?.invoke()
              if (pendingResumeWake || wakeRequested || resumeWakeAfterDictation) {
                finishSpeakingAndMaybeResumeWake()
              } else if (voicePhaseActive) {
                switchToWakeListening()
              } else {
                transitionTo(VoiceSessionState.Idle, "Ready")
                releaseAudioRoute()
              }
            },
            VoiceWakeConfig.POST_TTS_COOLDOWN_MS,
        )
      }
    }
  }

  fun speakSummary(fullText: String, onComplete: (() -> Unit)? = null) {
    speak(summarizeForSpeech(fullText), onComplete)
  }

  private fun beginWakeSession(preferPhoneMic: Boolean = false) {
    mode = SessionMode.WAKE
    immediateRetryUsed = false
    transitionTo(VoiceSessionState.Routing, "Routing audio to glasses")
    audioRouter.acquireRoute(
        preferPhoneMic = preferPhoneMic,
        onReady = { route ->
          activeRouteKind = route
          updateDiagnostics(route)
          ensureRecognizer(forDictation = false, routeKind = route) {
            transitionTo(
                VoiceSessionState.Listening,
                "Listening for \"${VoiceWakeConfig.WAKE_PHRASE}\" commands",
            )
            scheduleStartListening(forDictation = false)
          }
        },
        onDisconnect = {
          if (mode == SessionMode.WAKE && wakeRequested) {
            handleRecoverableError("Glasses audio disconnected")
          }
        },
    )
  }

  private fun switchToWakeListening() {
    mode = SessionMode.WAKE
    immediateRetryUsed = false
    executedPartialKey = null
    dictationCallbacks = null
    stopRecognizerOnly()
    val route = activeRouteKind ?: audioRouter.currentRouteKind()
    if (route == null) {
      beginWakeSession(preferPhoneMic = preferPhoneMicForSession)
      return
    }
    activeRouteKind = route
    transitionTo(VoiceSessionState.Routing, "Resuming wake listening")
    ensureRecognizer(forDictation = false, routeKind = route) {
      transitionTo(
          VoiceSessionState.Listening,
          "Listening for \"${VoiceWakeConfig.WAKE_PHRASE}\" commands",
      )
      scheduleStartListening(forDictation = false)
    }
  }

  private fun needsRouteSwitch(preferPhoneMic: Boolean): Boolean {
    if (!audioRouter.isRouteHeld()) return false
    return preferPhoneMic != audioRouter.isPhoneFallback()
  }

  private fun ensureRecognizer(
      forDictation: Boolean,
      routeKind: VoiceRouteKind,
      onReady: () -> Unit,
  ) {
    stopRecognizerOnly()
    recognizer =
        VoiceRecognizerEngine(
            context = appContext,
            onPartial = { candidates -> handlePartialResults(candidates, forDictation) },
            onFinal = { candidates -> handleFinalResults(candidates, forDictation) },
            onReadyForSpeech = {
              earcons.playStart()
              if (forDictation) {
                dictationCallbacks?.onReady()
              }
            },
            onError = { code, message -> handleRecognizerError(code, message, forDictation) },
            mainExecutor = mainExecutor(forDictation),
        )
    recognizer?.discoverSupport(routeKind) { provider ->
      updateSnapshot {
        it.copy(
            diagnostics =
                it.diagnostics.copy(
                    recognitionProvider = provider,
                    routeLabel = audioRouter.routeLabel(),
                    phoneAudioFallback = audioRouter.isPhoneFallback(),
                ),
        )
      }
      onReady()
    }
  }

  private fun scheduleStartListening(forDictation: Boolean) {
    mainHandler.postDelayed(
        { recognizer?.startListening(forDictation) },
        VoiceWakeConfig.POST_ROUTE_STABILIZATION_MS,
    )
  }

  private fun mainExecutor(@Suppress("UNUSED_PARAMETER") forDictation: Boolean): Executor =
      Executor { mainHandler.post(it) }

  private fun handlePartialResults(candidates: List<RecognizedCandidate>, forDictation: Boolean) {
    val best = candidates.maxByOrNull { it.confidence } ?: return
    updateSnapshot {
      it.copy(
          partialTranscript = best.text,
          finalTranscript = null,
          statusMessage =
              if (forDictation) "Capturing speech" else "Heard: ${best.text}",
      )
    }

    if (forDictation) {
      dictationCallbacks?.onPartialTranscript(best.text)
      return
    }

    if (best.confidence < VoiceWakeConfig.STABLE_PARTIAL_CONFIDENCE) return
    val partialKey = "${best.text}:${best.confidence}"
    if (partialKey == executedPartialKey) return

    when (val result = resolver.resolve(candidates, VoiceSessionState.Resolving)) {
      is VoiceResolveResult.Matched -> {
        executedPartialKey = partialKey
        executeMatchedCommand(result.match, best.text)
      }
      else -> Unit
    }
  }

  private fun handleFinalResults(candidates: List<RecognizedCandidate>, forDictation: Boolean) {
    val best = candidates.maxByOrNull { it.confidence }
    updateSnapshot {
      it.copy(
          partialTranscript = null,
          finalTranscript = best?.text,
          statusMessage = best?.text?.let { text -> "Heard: $text" } ?: it.statusMessage,
      )
    }

    if (forDictation) {
      val transcript = best?.text?.trim().orEmpty()
      val callbacks = dictationCallbacks
      endDictation(notifyCancel = false)
      if (transcript.isNotBlank()) {
        callbacks?.onFinalTranscript(transcript)
      } else {
        callbacks?.onError("I couldn't hear that clearly")
      }
      resumeWakeAfterDictationIfNeeded()
      return
    }

    recognizer?.stopListening(cancel = false)
    when (val result = resolver.resolve(candidates, VoiceSessionState.Resolving)) {
      is VoiceResolveResult.Matched -> executeMatchedCommand(result.match, best?.text.orEmpty())
      VoiceResolveResult.NoWakePhrase -> resumeWakeAfterNoCommand()
      VoiceResolveResult.NoCommand ->
          speakUnknownCommand("I didn't understand that command.") { resumeWakeAfterNoCommand() }
      VoiceResolveResult.Ambiguous ->
          speakUnknownCommand("That command was ambiguous.") { resumeWakeAfterNoCommand() }
      VoiceResolveResult.LowConfidence ->
          speakUnknownCommand("I didn't catch that clearly.") { resumeWakeAfterNoCommand() }
    }
  }

  private fun executeMatchedCommand(match: VoiceCommandMatch, transcript: String) {
    stopRecognizerOnly()
    pulseFeedback()
    transitionTo(
        VoiceSessionState.Executing,
        "Command: ${match.intent.name.lowercase()}",
    )
    updateSnapshot {
      it.copy(
          matchedIntent = match.intent,
          finalTranscript = transcript,
          partialTranscript = null,
      )
    }

    val response =
        when (match.intent) {
          VoiceAppIntent.SCAN_DOCUMENT -> {
            wakeRequested = false
            commandHandler.onScanDocument()
            VoiceCommandRegistry.commands
                .first { it.intent == VoiceAppIntent.SCAN_DOCUMENT }
                .spokenResponse!!
          }
          VoiceAppIntent.CAPTURE_PHOTO -> {
            commandHandler.onCapturePhoto()
            VoiceCommandRegistry.commands
                .first { it.intent == VoiceAppIntent.CAPTURE_PHOTO }
                .spokenResponse!!
          }
          VoiceAppIntent.REPEAT_RESPONSE -> {
            commandHandler.getLastSpokenResponse()
                ?: lastSpokenResponse
                ?: "Nothing to repeat yet."
          }
          VoiceAppIntent.CANCEL -> {
            commandHandler.onCancelVoiceCommand()
            VoiceCommandRegistry.commands
                .first { it.intent == VoiceAppIntent.CANCEL }
                .spokenResponse!!
          }
        }

    earcons.playEnd()
    speak(response)
  }

  private fun speakUnknownCommand(message: String, onComplete: () -> Unit) {
    earcons.playEnd()
    speak(message) { onComplete() }
  }

  private fun resumeWakeAfterNoCommand() {
    executedPartialKey = null
    if (!wakeRequested) {
      if (voicePhaseActive) {
        switchToWakeListening()
      } else {
        transitionTo(VoiceSessionState.Idle, "Ready")
        releaseAudioRoute()
      }
      return
    }
    switchToWakeListening()
  }

  private fun finishSpeakingAndMaybeResumeWake() {
    pendingResumeWake = false
    executedPartialKey = null
    val shouldResume = wakeRequested || resumeWakeAfterDictation
    resumeWakeAfterDictation = false
    if (shouldResume) {
      wakeRequested = true
      switchToWakeListening()
    } else if (voicePhaseActive) {
      switchToWakeListening()
    } else {
      transitionTo(VoiceSessionState.Idle, "Ready")
      releaseAudioRoute()
    }
  }

  private fun resumeWakeAfterDictationIfNeeded() {
    if (resumeWakeAfterDictation) {
      resumeWakeAfterDictation = false
      wakeRequested = true
      switchToWakeListening()
    } else if (voicePhaseActive && wakeRequested) {
      switchToWakeListening()
    } else {
      mode = SessionMode.IDLE
      transitionTo(VoiceSessionState.Idle, "Ready")
    }
  }

  private fun endDictation(notifyCancel: Boolean) {
    dictationCallbacks?.let { callbacks ->
      if (notifyCancel) {
        callbacks.onError("Cancelled")
      }
    }
    dictationCallbacks = null
    recognizer?.stopListening(cancel = notifyCancel)
    if (mode == SessionMode.DICTATION) {
      mode = SessionMode.IDLE
      updateSnapshot { it.copy(isDictationActive = false) }
    }
  }

  private fun handleRecognizerError(code: Int, message: String, forDictation: Boolean) {
    Log.i(TAG, "Recognizer error $code: $message")
    val isServiceDisconnect =
        code == VoiceRecognizerEngine.ERROR_SERVER_DISCONNECTED ||
            code == 12 ||
            code == 13

    if (isServiceDisconnect && recognizer != null) {
      recognizer?.fallbackToOnline {
        updateSnapshot {
          it.copy(
              diagnostics = it.diagnostics.copy(recognitionProvider = RecognitionProvider.ONLINE),
              statusMessage = "Retrying with online speech recognition",
          )
        }
        mainHandler.postDelayed(
            { recognizer?.startListening(forDictation) },
            VoiceWakeConfig.RECOGNIZER_RETRY_DELAY_MS,
        )
      }
      return
    }

    val nonRecoverable =
        code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            code == SpeechRecognizer.ERROR_CLIENT

    if (mode == SessionMode.DICTATION) {
      val callbacks = dictationCallbacks
      endDictation(notifyCancel = false)
      callbacks?.onError(message)
      resumeWakeAfterDictationIfNeeded()
      return
    }

    if (nonRecoverable) {
      consecutiveFailures = 0
      transitionTo(VoiceSessionState.Error, message)
      updateSnapshot {
        it.copy(diagnostics = it.diagnostics.copy(lastError = message))
      }
      releaseAudioRoute()
      return
    }

    val canImmediateRetry =
        !immediateRetryUsed &&
            (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                code == SpeechRecognizer.ERROR_AUDIO ||
                code == VoiceRecognizerEngine.ERROR_SERVER_DISCONNECTED)
    if (canImmediateRetry) {
      immediateRetryUsed = true
      mainHandler.postDelayed(
          { recognizer?.startListening(forDictation = false) },
          VoiceWakeConfig.RECOGNIZER_RETRY_DELAY_MS,
      )
      return
    }

    handleRecoverableError(message)
  }

  private fun handleRecoverableError(message: String) {
    consecutiveFailures += 1
    val backoff =
        (BASE_BACKOFF_MS * (1 shl (consecutiveFailures - 1).coerceAtMost(3))).coerceAtMost(
            MAX_BACKOFF_MS,
        )
    transitionTo(VoiceSessionState.Error, message)
    updateSnapshot {
      it.copy(
          diagnostics =
              it.diagnostics.copy(
                  lastError = message,
                  consecutiveFailures = consecutiveFailures,
              ),
      )
    }
    stopRecognizerOnly()
    if (!wakeRequested) {
      releaseAudioRoute()
      return
    }
    mainHandler.postDelayed(
        {
          if (wakeRequested) {
            if (audioRouter.isRouteHeld() && activeRouteKind != null) {
              switchToWakeListening()
            } else {
              beginWakeSession(preferPhoneMic = preferPhoneMicForSession)
            }
          }
        },
        backoff,
    )
  }

  private fun stopRecognizerOnly() {
    recognizer?.destroy()
    recognizer = null
  }

  private fun releaseAudioRoute() {
    stopRecognizerOnly()
    audioRouter.release()
    activeRouteKind = null
  }

  private fun transitionTo(state: VoiceSessionState, statusMessage: String) {
    updateSnapshot {
      it.copy(
          state = state,
          statusMessage = statusMessage,
          isWakeListening = mode == SessionMode.WAKE && state == VoiceSessionState.Listening,
          isDictationActive = mode == SessionMode.DICTATION,
      )
    }
  }

  private fun updateDiagnostics(route: VoiceRouteKind) {
    updateSnapshot {
      it.copy(
          diagnostics =
              it.diagnostics.copy(
                  routeLabel = audioRouter.routeLabel(),
                  phoneAudioFallback = route == VoiceRouteKind.PHONE_MIC,
              ),
      )
    }
  }

  private fun updateSnapshot(transform: (VoiceSessionSnapshot) -> VoiceSessionSnapshot) {
    snapshot = transform(snapshot)
    onSnapshotChanged(snapshot)
  }

  private fun pulseFeedback() {
    runCatching {
      vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
    }
  }

  private fun summarizeForSpeech(text: String): String {
    val cleaned = text.trim().replace(Regex("\\s+"), " ")
    if (cleaned.length <= 180) return cleaned
    val sentenceEnd = cleaned.indexOfAny(charArrayOf('.', '!', '?'), 120)
    return if (sentenceEnd in 80..220) {
      cleaned.substring(0, sentenceEnd + 1)
    } else {
      cleaned.take(180).trimEnd() + "..."
    }
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
    } else {
      mainHandler.post(block)
    }
  }

  fun shutdown() {
    runOnMain {
      stopVoicePhase()
      tts.shutdown()
      earcons.release()
    }
  }
}
