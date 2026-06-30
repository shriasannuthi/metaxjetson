/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

object SpeakableText {
  /** Strips markdown and other formatting so TTS reads natural plain speech. */
  fun toPlainSpeech(text: String): String =
      text
          .replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
          .replace(Regex("""`+"""), "")
          .replace(Regex("""\*{1,2}([^*]+)\*{1,2}"""), "$1")
          .replace(Regex("""_{1,2}([^_]+)_{1,2}"""), "$1")
          .replace(Regex("""^#{1,6}\s*""", RegexOption.MULTILINE), "")
          .replace(Regex("""^>\s?""", RegexOption.MULTILINE), "")
          .lines()
          .map { it.trim().trimStart('-', '•', '*', '#', '>', '|') }
          .filter { it.isNotBlank() && !it.matches(Regex("^[-|_]{3,}$")) }
          .joinToString(". ")
          .replace(Regex("""\s+"""), " ")
          .replace(Regex("""\.{2,}"""), ".")
          .trim()

  fun truncateToLines(text: String, maxLines: Int = 2, maxChars: Int = 220): String {
    val plain = toPlainSpeech(text)
    val lines =
        plain
            .split(Regex("""\.\s+"""))
            .map { it.trim().trimEnd('.') }
            .filter { it.isNotBlank() }
            .take(maxLines)
    val joined = lines.joinToString(". ")
    return if (joined.length <= maxChars) joined else joined.take(maxChars).trimEnd('.', ' ') + "."
  }
}
