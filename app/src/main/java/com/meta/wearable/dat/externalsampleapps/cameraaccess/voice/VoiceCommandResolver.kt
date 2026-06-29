/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

import kotlin.math.max
import kotlin.math.min

class VoiceCommandResolver(
    private val wakePhrase: String = VoiceWakeConfig.WAKE_PHRASE,
    private val commands: List<CommandDefinition> = VoiceCommandRegistry.commands,
    private val minConfidence: Float = VoiceWakeConfig.MIN_EXECUTION_CONFIDENCE,
) {
  private val normalizedWake = SpeechTextNormalizer.normalize(wakePhrase)
  private val wakeWords = normalizedWake.split(" ").filter { it.isNotBlank() }

  fun resolve(
      candidates: List<RecognizedCandidate>,
      sessionState: VoiceSessionState,
  ): VoiceResolveResult {
    if (candidates.isEmpty()) return VoiceResolveResult.NoCommand

    val eligible =
        candidates.filter { it.confidence >= minConfidence && it.text.isNotBlank() }
    if (eligible.isEmpty()) return VoiceResolveResult.LowConfidence

    val bestCandidate = eligible.maxBy { it.confidence }
    val normalizedTranscript = SpeechTextNormalizer.normalize(bestCandidate.text)
    if (normalizedTranscript.isBlank()) return VoiceResolveResult.NoCommand

    val commandText = extractCommandText(normalizedTranscript) ?: return VoiceResolveResult.NoWakePhrase
    if (commandText.isBlank()) return VoiceResolveResult.NoCommand

    val matches = mutableListOf<VoiceCommandMatch>()
    for (definition in commands) {
      if (sessionState !in definition.allowedStates && sessionState != VoiceSessionState.Resolving) {
        continue
      }
      for (phrase in definition.phrases) {
        if (matchesPhrase(commandText, phrase)) {
          matches.add(
              VoiceCommandMatch(
                  intent = definition.intent,
                  matchedPhrase = phrase,
                  commandText = commandText,
                  confidence = bestCandidate.confidence,
              ),
          )
        }
      }
    }

    val distinctIntents = matches.map { it.intent }.distinct()
    return when {
      matches.isEmpty() -> VoiceResolveResult.NoCommand
      distinctIntents.size > 1 -> VoiceResolveResult.Ambiguous
      else -> VoiceResolveResult.Matched(matches.first())
    }
  }

  fun containsWakePhrase(text: String): Boolean {
    val normalized = SpeechTextNormalizer.normalize(text)
    return extractCommandText(normalized) != null
  }

  private fun extractCommandText(normalizedTranscript: String): String? {
    if (normalizedTranscript == normalizedWake) return ""
    if (normalizedTranscript.startsWith("$normalizedWake ")) {
      return normalizedTranscript.removePrefix("$normalizedWake ").trim()
    }

    val words = normalizedTranscript.split(" ").filter { it.isNotBlank() }
    if (words.size < wakeWords.size) return null

    for (start in 0..min(words.size - wakeWords.size, 2)) {
      val slice = words.subList(start, start + wakeWords.size)
      if (wakeWordsMatch(slice)) {
        return words.drop(start + wakeWords.size).joinToString(" ").trim()
      }
    }
    return null
  }

  private fun wakeWordsMatch(words: List<String>): Boolean =
      wakeWords.indices.all { index ->
        isCloseMatch(words[index], wakeWords[index], allowLongWord = false)
      }

  private fun matchesPhrase(commandText: String, phrase: String): Boolean {
    val normalizedPhrase = SpeechTextNormalizer.normalize(phrase)
    if (normalizedPhrase.isBlank()) return false
    if (commandText == normalizedPhrase) return true
    if (commandText.startsWith("$normalizedPhrase ")) return true
    if (commandText.endsWith(" $normalizedPhrase")) return true

    val commandWords = commandText.split(" ").filter { it.isNotBlank() }
    val phraseWords = normalizedPhrase.split(" ").filter { it.isNotBlank() }
    if (phraseWords.size > commandWords.size) return false

    return commandWords.windowed(phraseWords.size, 1, partialWindows = false).any { window ->
      window.indices.all { index ->
        isCloseMatch(window[index], phraseWords[index], allowLongWord = true)
      }
    }
  }

  private fun isCloseMatch(actual: String, expected: String, allowLongWord: Boolean): Boolean {
    if (actual == expected) return true
    val maxDistance =
        if (expected.length <= VoiceWakeConfig.SHORT_WORD_LENGTH && !allowLongWord) {
          VoiceWakeConfig.MAX_TYPING_DISTANCE_SHORT
        } else {
          VoiceWakeConfig.MAX_TYPING_DISTANCE_LONG
        }
    return levenshteinDistance(actual, expected) <= maxDistance
  }

  private fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var previousRow = IntArray(b.length + 1) { it }
    var currentRow = IntArray(b.length + 1)

    for (i in 1..a.length) {
      currentRow[0] = i
      for (j in 1..b.length) {
        val substitutionCost = if (a[i - 1] == b[j - 1]) 0 else 1
        currentRow[j] =
            min(
                min(currentRow[j - 1] + 1, previousRow[j] + 1),
                previousRow[j - 1] + substitutionCost,
            )
      }
      val swap = previousRow
      previousRow = currentRow
      currentRow = swap
    }
    return previousRow[b.length]
  }
}
