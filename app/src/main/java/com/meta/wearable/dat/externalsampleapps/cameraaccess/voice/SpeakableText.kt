/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

object SpeakableText {
  fun truncateToLines(text: String, maxLines: Int = 2, maxChars: Int = 220): String {
    val lines =
        text
            .lines()
            .map { it.trim().trimStart('-', '•', '*', '#') }
            .filter { it.isNotBlank() }
            .take(maxLines)
    val joined = lines.joinToString(". ").replace(Regex("\\.{2,}"), ".")
    return if (joined.length <= maxChars) joined else joined.take(maxChars).trimEnd('.', ' ') + "."
  }
}
