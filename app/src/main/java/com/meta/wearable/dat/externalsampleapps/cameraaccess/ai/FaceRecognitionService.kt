/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.CustomerRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter

class FaceRecognitionService(
    private val context: Context,
    private val customerRepository: CustomerRepository = CustomerRepository(context),
) {
  private val detector =
      FaceDetection.getClient(
          FaceDetectorOptions.Builder()
              .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
              .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
              .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
              .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
              .setMinFaceSize(MIN_FACE_SIZE)
              .build()
      )

  private val initializationMutex = Mutex()
  private var interpreter: Interpreter? = null
  private var referenceEmbeddings: List<CustomerFaceEmbedding>? = null

  suspend fun detectAndMatchFace(currentFrame: Bitmap): FaceRecognitionResult =
      withContext(Dispatchers.Default) {
        ensureInitialized()

        val liveEmbedding =
            currentFrame.createFaceEmbedding()
                ?: return@withContext FaceRecognitionResult.NoFaceDetected
        val references = referenceEmbeddings.orEmpty()
        if (references.isEmpty()) {
          return@withContext FaceRecognitionResult.Unavailable(
              "Add customer face images in assets/faces"
          )
        }

        val match =
            references
                .map { reference ->
                  FaceMatch(reference.customer, cosineSimilarity(liveEmbedding, reference.embedding))
                }
                .maxByOrNull { it.similarity }

        Log.i(
            TAG,
            "Local face match: customer=${match?.customer?.id ?: "none"}, similarity=${match?.similarity ?: 0f}",
        )

        if (match != null && match.similarity >= MIN_MATCH_SIMILARITY) {
          FaceRecognitionResult.Match(match.customer, match.similarity)
        } else {
          FaceRecognitionResult.NoMatch(match?.similarity ?: 0f)
        }
      }

  private suspend fun ensureInitialized() {
    if (referenceEmbeddings != null) return

    initializationMutex.withLock {
      if (referenceEmbeddings != null) return

      interpreter =
          runCatching {
                Interpreter(
                    loadModelAsset(MODEL_ASSET_PATH),
                    Interpreter.Options().apply { setNumThreads(INFERENCE_THREADS) },
                )
              }
              .onSuccess { Log.i(TAG, "Using TensorFlow Lite face model: $MODEL_ASSET_PATH") }
              .onFailure { Log.i(TAG, "No TFLite face model found; using local image embedding") }
              .getOrNull()
      referenceEmbeddings = buildReferenceEmbeddings()
      Log.i(TAG, "Loaded ${referenceEmbeddings.orEmpty().size} local face reference embeddings")
    }
  }

  private suspend fun buildReferenceEmbeddings(): List<CustomerFaceEmbedding> {
    val customers = customerRepository.loadCustomers()
    return customers.mapNotNull { customer ->
      runCatching {
            val bitmap =
                context.assets
                    .open(customerRepository.faceAssetPath(customer))
                    .use(BitmapFactory::decodeStream)
                    ?: error("Unable to decode ${customer.faceImage}")
            try {
              bitmap.createFaceEmbedding()?.let { embedding ->
                CustomerFaceEmbedding(customer, embedding)
              }
            } finally {
              bitmap.recycle()
            }
          }
          .onFailure {
            Log.w(TAG, "Skipping face reference for ${customer.id}: ${customer.faceImage}", it)
          }
          .getOrNull()
    }
  }

  private suspend fun Bitmap.createFaceEmbedding(): FloatArray? {
    val face = detectLargestFace(this) ?: return null
    val faceBitmap = cropFace(face.boundingBox)
    return try {
      runEmbedding(faceBitmap)
    } finally {
      if (faceBitmap != this) faceBitmap.recycle()
    }
  }

  private suspend fun detectLargestFace(bitmap: Bitmap): Face? {
    val image = InputImage.fromBitmap(bitmap, 0)
    return detector
        .process(image)
        .await()
        .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
  }

  private fun Bitmap.cropFace(bounds: Rect): Bitmap {
    val padded = bounds.withPadding(FACE_CROP_PADDING, width, height)
    if (padded.width() <= 0 || padded.height() <= 0) return this
    return Bitmap.createBitmap(this, padded.left, padded.top, padded.width(), padded.height())
  }

  private fun runEmbedding(faceBitmap: Bitmap): FloatArray {
    interpreter?.let { return runModelEmbedding(it, faceBitmap) }
    return runImageEmbedding(faceBitmap)
  }

  private fun runModelEmbedding(model: Interpreter, faceBitmap: Bitmap): FloatArray {
    val inputShape = model.getInputTensor(0).shape()
    val inputHeight = inputShape.getOrNull(1) ?: MODEL_INPUT_SIZE
    val inputWidth = inputShape.getOrNull(2) ?: MODEL_INPUT_SIZE
    val scaled = Bitmap.createScaledBitmap(faceBitmap, inputWidth, inputHeight, true)
    val input =
        ByteBuffer.allocateDirect(inputWidth * inputHeight * RGB_CHANNELS * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
    val pixels = IntArray(inputWidth * inputHeight)
    scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
    pixels.forEach { pixel ->
      input.putFloat((((pixel shr 16) and 0xFF) - RGB_MEAN) / RGB_STD)
      input.putFloat((((pixel shr 8) and 0xFF) - RGB_MEAN) / RGB_STD)
      input.putFloat(((pixel and 0xFF) - RGB_MEAN) / RGB_STD)
    }
    if (scaled != faceBitmap) scaled.recycle()

    val outputShape = model.getOutputTensor(0).shape()
    val embeddingSize = outputShape.lastOrNull() ?: MODEL_EMBEDDING_SIZE
    val output = Array(1) { FloatArray(embeddingSize) }
    model.run(input.rewind(), output)
    return output[0].l2Normalize()
  }

  private fun runImageEmbedding(faceBitmap: Bitmap): FloatArray {
    val scaled = Bitmap.createScaledBitmap(faceBitmap, EMBEDDING_IMAGE_SIZE, EMBEDDING_IMAGE_SIZE, true)
    val pixels = IntArray(EMBEDDING_IMAGE_SIZE * EMBEDDING_IMAGE_SIZE)
    scaled.getPixels(pixels, 0, EMBEDDING_IMAGE_SIZE, 0, 0, EMBEDDING_IMAGE_SIZE, EMBEDDING_IMAGE_SIZE)
    if (scaled != faceBitmap) scaled.recycle()

    val gray = FloatArray(pixels.size)
    pixels.forEachIndexed { index, pixel ->
      val red = (pixel shr 16) and 0xFF
      val green = (pixel shr 8) and 0xFF
      val blue = pixel and 0xFF
      gray[index] = (0.299f * red + 0.587f * green + 0.114f * blue) / 255f
    }

    return buildList {
          addAll(gray.zScoreNormalize().asList())
          addAll(gray.blockMeans(blocksPerSide = 8).asList())
          addAll(gray.gradientHistogram(blocksPerSide = 4).asList())
        }
        .toFloatArray()
        .l2Normalize()
  }

  private fun loadModelAsset(path: String): ByteBuffer {
    val bytes = context.assets.open(path).use { it.readBytes() }
    return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
      put(bytes)
      rewind()
    }
  }

  private fun Rect.withPadding(paddingRatio: Float, imageWidth: Int, imageHeight: Int): Rect {
    val horizontalPadding = (width() * paddingRatio).toInt()
    val verticalPadding = (height() * paddingRatio).toInt()
    return Rect(
        (left - horizontalPadding).coerceAtLeast(0),
        (top - verticalPadding).coerceAtLeast(0),
        (right + horizontalPadding).coerceAtMost(imageWidth),
        (bottom + verticalPadding).coerceAtMost(imageHeight),
    )
  }

  private fun FloatArray.l2Normalize(): FloatArray {
    val magnitude = kotlin.math.sqrt(sumOf { (it * it).toDouble() }).toFloat()
    if (magnitude <= 0f) return this
    for (index in indices) {
      this[index] /= magnitude
    }
    return this
  }

  private fun FloatArray.zScoreNormalize(): FloatArray {
    val mean = average().toFloat()
    val variance = sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / size
    val standardDeviation = kotlin.math.sqrt(variance).coerceAtLeast(0.0001f)
    return FloatArray(size) { index -> (this[index] - mean) / standardDeviation }
  }

  private fun FloatArray.blockMeans(blocksPerSide: Int): FloatArray {
    val blockSize = EMBEDDING_IMAGE_SIZE / blocksPerSide
    return FloatArray(blocksPerSide * blocksPerSide) { blockIndex ->
      val blockX = blockIndex % blocksPerSide
      val blockY = blockIndex / blocksPerSide
      var total = 0f
      for (y in 0 until blockSize) {
        for (x in 0 until blockSize) {
          total += this[(blockY * blockSize + y) * EMBEDDING_IMAGE_SIZE + blockX * blockSize + x]
        }
      }
      total / (blockSize * blockSize)
    }.zScoreNormalize()
  }

  private fun FloatArray.gradientHistogram(blocksPerSide: Int): FloatArray {
    val blockSize = EMBEDDING_IMAGE_SIZE / blocksPerSide
    val binsPerBlock = 8
    val histogram = FloatArray(blocksPerSide * blocksPerSide * binsPerBlock)

    for (y in 1 until EMBEDDING_IMAGE_SIZE - 1) {
      for (x in 1 until EMBEDDING_IMAGE_SIZE - 1) {
        val dx = this[y * EMBEDDING_IMAGE_SIZE + x + 1] - this[y * EMBEDDING_IMAGE_SIZE + x - 1]
        val dy = this[(y + 1) * EMBEDDING_IMAGE_SIZE + x] - this[(y - 1) * EMBEDDING_IMAGE_SIZE + x]
        val magnitude = kotlin.math.sqrt(dx * dx + dy * dy)
        val angle = kotlin.math.atan2(dy, dx) + Math.PI.toFloat()
        val bin = ((angle / (2f * Math.PI.toFloat())) * binsPerBlock).toInt().coerceIn(0, binsPerBlock - 1)
        val blockX = (x / blockSize).coerceAtMost(blocksPerSide - 1)
        val blockY = (y / blockSize).coerceAtMost(blocksPerSide - 1)
        histogram[(blockY * blocksPerSide + blockX) * binsPerBlock + bin] += magnitude
      }
    }

    return histogram.l2Normalize()
  }

  private fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
    var similarity = 0f
    val size = minOf(left.size, right.size)
    for (index in 0 until size) {
      similarity += left[index] * right[index]
    }
    return similarity
  }

  private suspend fun <T> Task<T>.await(): T =
      kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
      }

  private data class CustomerFaceEmbedding(
      val customer: Customer,
      val embedding: FloatArray,
  )

  private data class FaceMatch(
      val customer: Customer,
      val similarity: Float,
  )

  private companion object {
    const val TAG = "CameraAccess:FaceRecognition"
    const val MODEL_ASSET_PATH = "models/mobile_face_net.tflite"
    const val MODEL_INPUT_SIZE = 112
    const val MODEL_EMBEDDING_SIZE = 192
    const val FLOAT_BYTES = 4
    const val RGB_CHANNELS = 3
    const val INFERENCE_THREADS = 2
    const val RGB_MEAN = 127.5f
    const val RGB_STD = 128f
    const val EMBEDDING_IMAGE_SIZE = 32
    const val MIN_FACE_SIZE = 0.12f
    const val FACE_CROP_PADDING = 0.2f
    const val MIN_MATCH_SIMILARITY = 0.52f
  }
}

sealed class FaceRecognitionResult {
  data class Match(val customer: Customer, val similarity: Float) : FaceRecognitionResult()
  data class NoMatch(val bestSimilarity: Float) : FaceRecognitionResult()
  data object NoFaceDetected : FaceRecognitionResult()
  data class Unavailable(val reason: String) : FaceRecognitionResult()
}
