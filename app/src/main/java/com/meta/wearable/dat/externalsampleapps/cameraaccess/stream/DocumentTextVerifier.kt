/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlinx.coroutines.suspendCancellableCoroutine

class DocumentTextVerifier {
  private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

  suspend fun verify(frame: Bitmap): DocumentTextVerificationResult {
    val scaled = frame.scaleForTextRecognition()
    return try {
      val image = InputImage.fromBitmap(scaled, 0)
      val text = recognizer.process(image).await()
      scoreText(text)
    } finally {
      if (scaled != frame) scaled.recycle()
    }
  }

  private fun Bitmap.scaleForTextRecognition(): Bitmap {
    val longestSide = max(width, height)
    if (longestSide <= TEXT_RECOGNITION_LONGEST_SIDE) return this

    val scale = TEXT_RECOGNITION_LONGEST_SIDE.toFloat() / longestSide.toFloat()
    val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
  }

  private fun scoreText(text: Text): DocumentTextVerificationResult {
    val lines = text.textBlocks.flatMap { it.lines }
    val normalizedLines =
        lines.mapNotNull { line ->
          val cleaned = line.text.trim()
          if (cleaned.length >= MIN_LINE_LENGTH) {
            val box = line.boundingBox
            if (box != null) VerifiedLine(cleaned, box.left, box.top, box.width(), box.height()) else null
          } else {
            null
          }
        }

    val charCount = normalizedLines.sumOf { it.text.count { char -> char.isLetterOrDigit() } }
    val lineCount = normalizedLines.size
    val blockCount = text.textBlocks.count { block -> block.text.trim().length >= MIN_BLOCK_LENGTH }
    val horizontalLineCount = normalizedLines.count { line -> line.width > line.height * HORIZONTAL_LINE_RATIO }
    val alignedLinePairs = countAlignedPairs(normalizedLines)

    val charScore = (charCount.toFloat() / IDEAL_CHAR_COUNT).coerceIn(0f, 1f)
    val lineScore = (lineCount.toFloat() / IDEAL_LINE_COUNT).coerceIn(0f, 1f)
    val blockScore = (blockCount.toFloat() / IDEAL_BLOCK_COUNT).coerceIn(0f, 1f)
    val horizontalScore =
        if (lineCount == 0) 0f else (horizontalLineCount.toFloat() / lineCount).coerceIn(0f, 1f)
    val alignmentScore = (alignedLinePairs.toFloat() / IDEAL_ALIGNED_PAIRS).coerceIn(0f, 1f)

    val score =
        (charScore * CHAR_WEIGHT) +
            (lineScore * LINE_WEIGHT) +
            (blockScore * BLOCK_WEIGHT) +
            (horizontalScore * HORIZONTAL_WEIGHT) +
            (alignmentScore * ALIGNMENT_WEIGHT)

    val hasText =
        charCount >= MIN_CHAR_COUNT &&
            lineCount >= MIN_TEXT_LINES &&
            horizontalLineCount >= MIN_HORIZONTAL_LINES &&
            score >= TEXT_SCORE_THRESHOLD

    return DocumentTextVerificationResult(
        hasDocumentText = hasText,
        score = score,
        lineCount = lineCount,
        charCount = charCount,
        debugText = "text ${(score * 100).toInt()}% lines $lineCount chars $charCount",
    )
  }

  private fun countAlignedPairs(lines: List<VerifiedLine>): Int {
    var aligned = 0
    for (index in 0 until lines.lastIndex) {
      val current = lines[index]
      val next = lines[index + 1]
      val leftDelta = kotlin.math.abs(current.left - next.left)
      val verticalGap = next.top - current.top
      if (leftDelta <= ALIGNMENT_LEFT_TOLERANCE_PX && verticalGap > 0 && verticalGap < MAX_LINE_GAP_PX) {
        aligned++
      }
    }
    return aligned
  }

  private suspend fun <T> Task<T>.await(): T =
      suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
      }

  private data class VerifiedLine(
      val text: String,
      val left: Int,
      val top: Int,
      val width: Int,
      val height: Int,
  )

  private companion object {
    const val TEXT_RECOGNITION_LONGEST_SIDE = 1024
    const val MIN_LINE_LENGTH = 2
    const val MIN_BLOCK_LENGTH = 4
    const val MIN_CHAR_COUNT = 8
    const val MIN_TEXT_LINES = 2
    const val MIN_HORIZONTAL_LINES = 1
    const val HORIZONTAL_LINE_RATIO = 2.4f
    const val IDEAL_CHAR_COUNT = 70f
    const val IDEAL_LINE_COUNT = 6f
    const val IDEAL_BLOCK_COUNT = 3f
    const val IDEAL_ALIGNED_PAIRS = 3f
    const val ALIGNMENT_LEFT_TOLERANCE_PX = 34
    const val MAX_LINE_GAP_PX = 140
    const val TEXT_SCORE_THRESHOLD = 0.24f
    const val CHAR_WEIGHT = 0.30f
    const val LINE_WEIGHT = 0.24f
    const val BLOCK_WEIGHT = 0.14f
    const val HORIZONTAL_WEIGHT = 0.16f
    const val ALIGNMENT_WEIGHT = 0.16f
  }
}

data class DocumentTextVerificationResult(
    val hasDocumentText: Boolean,
    val score: Float,
    val lineCount: Int,
    val charCount: Int,
    val debugText: String,
)
