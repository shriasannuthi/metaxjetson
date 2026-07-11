/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ai

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

interface DocumentOcrService {
  suspend fun transcribe(documentBitmap: Bitmap): String
}

class MlKitDocumentOcrService : DocumentOcrService {
  private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

  override suspend fun transcribe(documentBitmap: Bitmap): String {
    val image = InputImage.fromBitmap(documentBitmap, 0)
    val result = recognizer.process(image).await()
    return result.toReadableDocumentText()
  }

  private fun Text.toReadableDocumentText(): String {
    val blockText =
        textBlocks
            .mapNotNull { block ->
              block.lines
                  .map { it.text.trim() }
                  .filter { it.isNotBlank() }
                  .joinToString(separator = "\n")
                  .takeIf { it.isNotBlank() }
            }
            .joinToString(separator = "\n\n")
            .trim()
    return blockText.ifBlank { text.trim() }
  }
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
      addOnSuccessListener { result ->
        if (continuation.isActive) {
          continuation.resume(result)
        }
      }
      addOnFailureListener { error ->
        if (continuation.isActive) {
          continuation.resumeWithException(error)
        }
      }
      addOnCanceledListener { continuation.cancel() }
    }
