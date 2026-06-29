/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

import java.util.Locale

object SpeechTextNormalizer {
  private val NON_ALPHANUMERIC = Regex("[^a-z0-9 ]")
  private val WHITESPACE = Regex("\\s+")

  fun normalize(text: String, locale: Locale = Locale.US): String =
      text
          .lowercase(locale)
          .replace(NON_ALPHANUMERIC, " ")
          .replace(WHITESPACE, " ")
          .trim()
}
