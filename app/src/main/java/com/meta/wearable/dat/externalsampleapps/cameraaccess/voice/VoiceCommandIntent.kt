/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.voice

enum class VoiceCommandIntent {
  OPEN_QNA,
  DOCUMENT_QNA,
  SCAN_DOCUMENT,
  CAPTURE_PHOTO,
  CANCEL,
  /** No usable command heard — restart wake listening instead of opening Q&A. */
  NO_COMMAND,
}
