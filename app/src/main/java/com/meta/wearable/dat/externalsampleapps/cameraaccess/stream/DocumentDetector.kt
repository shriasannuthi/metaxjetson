/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class DocumentDetector {

  fun detect(frame: Bitmap): DocumentDetectionResult {
    val scaled = frame.scaleForDetection()
    return try {
      detectOnScaledBitmap(scaled)
    } finally {
      if (scaled != frame) scaled.recycle()
    }
  }

  private fun detectOnScaledBitmap(bitmap: Bitmap): DocumentDetectionResult {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val luminance = FloatArray(pixels.size)
    val saturation = FloatArray(pixels.size)
    var luminanceTotal = 0f
    pixels.forEachIndexed { index, pixel ->
      val red = (pixel shr 16) and 0xFF
      val green = (pixel shr 8) and 0xFF
      val blue = pixel and 0xFF
      val maxChannel = max(red, max(green, blue))
      val minChannel = min(red, min(green, blue))
      val gray = 0.299f * red + 0.587f * green + 0.114f * blue
      luminance[index] = gray
      saturation[index] = (maxChannel - minChannel) / 255f
      luminanceTotal += gray
    }
    val meanLuminance = luminanceTotal / luminance.size

    val edge = FloatArray(pixels.size)
    var edgeTotal = 0f
    for (y in 1 until height - 1) {
      for (x in 1 until width - 1) {
        val index = y * width + x
        val gx =
            -luminance[index - width - 1] -
                2f * luminance[index - 1] -
                luminance[index + width - 1] +
                luminance[index - width + 1] +
                2f * luminance[index + 1] +
                luminance[index + width + 1]
        val gy =
            -luminance[index - width - 1] -
                2f * luminance[index - width] -
                luminance[index - width + 1] +
                luminance[index + width - 1] +
                2f * luminance[index + width] +
                luminance[index + width + 1]
        val magnitude = sqrt(gx * gx + gy * gy)
        edge[index] = magnitude
        edgeTotal += magnitude
      }
    }

    val edgeMean = edgeTotal / ((width - 2) * (height - 2)).coerceAtLeast(1)
    val edgeThreshold = max(MIN_EDGE_THRESHOLD, edgeMean * EDGE_THRESHOLD_MULTIPLIER)
    val brightThreshold = max(MIN_BRIGHT_THRESHOLD, meanLuminance + BRIGHTNESS_MARGIN)

    var left = width
    var top = height
    var right = -1
    var bottom = -1
    var candidateCount = 0
    var brightLowSaturationCount = 0

    for (y in 1 until height - 1) {
      for (x in 1 until width - 1) {
        val index = y * width + x
        val brightLowSaturation =
            luminance[index] >= brightThreshold && saturation[index] <= MAX_DOCUMENT_SATURATION
        val strongEdge = edge[index] >= edgeThreshold
        if (brightLowSaturation) brightLowSaturationCount++
        if (brightLowSaturation || strongEdge) {
          left = min(left, x)
          top = min(top, y)
          right = max(right, x)
          bottom = max(bottom, y)
          candidateCount++
        }
      }
    }

    if (candidateCount < MIN_CANDIDATE_PIXELS || right <= left || bottom <= top) {
      return DocumentDetectionResult(
          isCandidate = false,
          score = 0f,
          debugText = "doc 0% - insufficient edges",
      )
    }

    val boxWidth = right - left + 1
    val boxHeight = bottom - top + 1
    val boxArea = boxWidth * boxHeight
    val imageArea = width * height
    val areaRatio = boxArea.toFloat() / imageArea
    val aspect = boxWidth.toFloat() / boxHeight.toFloat()
    val rectangularity = candidateCount.toFloat() / boxArea.coerceAtLeast(1)

    var innerLuminanceTotal = 0f
    var innerCount = 0
    var outerLuminanceTotal = 0f
    var outerCount = 0
    var insideEdges = 0
    var borderEdges = 0
    val borderBand = max(2, min(boxWidth, boxHeight) / BORDER_BAND_DIVISOR)

    for (y in 1 until height - 1) {
      for (x in 1 until width - 1) {
        val index = y * width + x
        val inside = x in left..right && y in top..bottom
        if (inside) {
          innerLuminanceTotal += luminance[index]
          innerCount++
          if (edge[index] >= edgeThreshold) {
            insideEdges++
            if (
                x - left <= borderBand ||
                    right - x <= borderBand ||
                    y - top <= borderBand ||
                    bottom - y <= borderBand
            ) {
              borderEdges++
            }
          }
        } else {
          outerLuminanceTotal += luminance[index]
          outerCount++
        }
      }
    }

    val innerMean = innerLuminanceTotal / innerCount.coerceAtLeast(1)
    val outerMean = outerLuminanceTotal / outerCount.coerceAtLeast(1)
    val contrastScore = (abs(innerMean - outerMean) / CONTRAST_FOR_FULL_SCORE).coerceIn(0f, 1f)
    val areaScore = areaRatio.scoreInRange(MIN_AREA_RATIO, IDEAL_AREA_RATIO, MAX_AREA_RATIO)
    val aspectScore = aspect.scoreInRange(MIN_ASPECT_RATIO, IDEAL_ASPECT_RATIO, MAX_ASPECT_RATIO)
    val edgeDensity = insideEdges.toFloat() / boxArea.coerceAtLeast(1)
    val edgeScore = edgeDensity.scoreInRange(MIN_EDGE_DENSITY, IDEAL_EDGE_DENSITY, MAX_EDGE_DENSITY)
    val borderScore = (borderEdges.toFloat() / insideEdges.coerceAtLeast(1)).coerceIn(0f, 1f)
    val brightScore = (brightLowSaturationCount.toFloat() / imageArea / BRIGHT_PIXEL_RATIO).coerceIn(0f, 1f)
    val rectangleScore = (1f - abs(rectangularity - IDEAL_RECTANGULARITY)).coerceIn(0f, 1f)

    val score =
        (areaScore * AREA_WEIGHT) +
            (aspectScore * ASPECT_WEIGHT) +
            (edgeScore * EDGE_WEIGHT) +
            (borderScore * BORDER_WEIGHT) +
            (contrastScore * CONTRAST_WEIGHT) +
            (brightScore * BRIGHT_WEIGHT) +
            (rectangleScore * RECTANGLE_WEIGHT)

    val isCandidate =
        score >= DOCUMENT_SCORE_THRESHOLD &&
            areaRatio in MIN_ACCEPTED_AREA_RATIO..MAX_ACCEPTED_AREA_RATIO &&
            edgeDensity >= REQUIRED_EDGE_DENSITY &&
            borderScore >= REQUIRED_BORDER_SCORE &&
            contrastScore >= REQUIRED_CONTRAST_SCORE
    return DocumentDetectionResult(
        isCandidate = isCandidate,
        score = score,
        areaRatio = areaRatio,
        aspectRatio = aspect,
        edgeDensity = edgeDensity,
        borderScore = borderScore,
        debugText =
            "doc ${(score * 100).toInt()}% area ${(areaRatio * 100).toInt()}% edge ${(edgeDensity * 100).toInt()}%",
    )
  }

  private fun Bitmap.scaleForDetection(): Bitmap {
    val longestSide = max(width, height)
    if (longestSide <= DETECTION_LONGEST_SIDE) return this

    val scale = DETECTION_LONGEST_SIDE.toFloat() / longestSide.toFloat()
    val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
  }

  private fun Float.scoreInRange(minValue: Float, idealValue: Float, maxValue: Float): Float {
    if (this <= minValue || this >= maxValue) return 0f
    return if (this <= idealValue) {
      ((this - minValue) / (idealValue - minValue)).coerceIn(0f, 1f)
    } else {
      ((maxValue - this) / (maxValue - idealValue)).coerceIn(0f, 1f)
    }
  }

  private companion object {
    const val DETECTION_LONGEST_SIDE = 160
    const val MIN_EDGE_THRESHOLD = 42f
    const val EDGE_THRESHOLD_MULTIPLIER = 2.3f
    const val MIN_BRIGHT_THRESHOLD = 130f
    const val BRIGHTNESS_MARGIN = 18f
    const val MAX_DOCUMENT_SATURATION = 0.42f
    const val MIN_CANDIDATE_PIXELS = 90
    const val BORDER_BAND_DIVISOR = 16

    const val MIN_AREA_RATIO = 0.08f
    const val IDEAL_AREA_RATIO = 0.35f
    const val MAX_AREA_RATIO = 0.88f
    const val MIN_ASPECT_RATIO = 0.42f
    const val IDEAL_ASPECT_RATIO = 0.72f
    const val MAX_ASPECT_RATIO = 2.15f
    const val MIN_EDGE_DENSITY = 0.015f
    const val IDEAL_EDGE_DENSITY = 0.08f
    const val MAX_EDGE_DENSITY = 0.32f
    const val CONTRAST_FOR_FULL_SCORE = 65f
    const val BRIGHT_PIXEL_RATIO = 0.18f
    const val IDEAL_RECTANGULARITY = 0.22f
    const val DOCUMENT_SCORE_THRESHOLD = 0.56f
    const val MIN_ACCEPTED_AREA_RATIO = 0.10f
    const val MAX_ACCEPTED_AREA_RATIO = 0.72f
    const val REQUIRED_EDGE_DENSITY = 0.018f
    const val REQUIRED_BORDER_SCORE = 0.08f
    const val REQUIRED_CONTRAST_SCORE = 0.10f

    const val AREA_WEIGHT = 0.16f
    const val ASPECT_WEIGHT = 0.14f
    const val EDGE_WEIGHT = 0.18f
    const val BORDER_WEIGHT = 0.24f
    const val CONTRAST_WEIGHT = 0.14f
    const val BRIGHT_WEIGHT = 0.10f
    const val RECTANGLE_WEIGHT = 0.04f
  }
}

data class DocumentDetectionResult(
    val isCandidate: Boolean,
    val score: Float,
    val areaRatio: Float = 0f,
    val aspectRatio: Float = 0f,
    val edgeDensity: Float = 0f,
    val borderScore: Float = 0f,
    val debugText: String = "",
)
