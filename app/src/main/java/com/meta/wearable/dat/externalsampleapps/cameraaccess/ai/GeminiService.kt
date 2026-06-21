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
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.CustomerRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiService(
  private val context: Context,
  private val customerRepository: CustomerRepository = CustomerRepository(context),
  private val httpClient: OkHttpClient = DEFAULT_HTTP_CLIENT,
  private val gson: Gson = Gson(),
  private val apiKey: String = BuildConfig.GEMINI_API_KEY,
) {
  private var pdfChunks: List<PdfChunk>? = null
  private val chunksMutex = Mutex()

  fun isConfigured(): Boolean = apiKey.isNotBlank()

  suspend fun postToGemini(prompt: String, images: List<Bitmap> = emptyList()): String =
    withContext(Dispatchers.IO) {
      if (!isConfigured()) {
        throw GeminiException("GEMINI_API_KEY is not configured in local.properties.")
      }

      val startedAtMs = SystemClock.elapsedRealtime()
      val request =
          Request.Builder()
              .url("$GEMINI_ENDPOINT?key=$apiKey")
              .post(buildRequestBody(prompt, images).toRequestBody(JSON_MEDIA_TYPE))
              .build()

      Log.d(TAG, "Posting Gemini request with ${images.size} image(s)")
      httpClient.newCall(request).execute().use { response ->
        val responseBody = response.body?.string().orEmpty()
        val durationMs = SystemClock.elapsedRealtime() - startedAtMs
        Log.d(
            TAG,
            "Gemini response received: http=${response.code}, durationMs=$durationMs, bodyChars=${responseBody.length}",
        )
        if (!response.isSuccessful) {
          throw GeminiException("Gemini request failed: HTTP ${response.code}", responseBody)
        }

        parseStreamingText(responseBody).ifBlank {
          throw GeminiException("Gemini returned an empty response.", responseBody)
        }
      }
    }

  suspend fun detectAndMatchFace(currentFrame: Bitmap): Customer? {
    val customers = customerRepository.loadCustomers()
    if (customers.isEmpty()) return null

    val faceReferences = loadCustomerFaceReferences(customers)
    if (faceReferences.isEmpty()) {
      Log.w(TAG, "No customer face reference images were found in assets/faces")
      return null
    }

    val prompt = buildFaceMatchPrompt(faceReferences)
    val responseJson =
        try {
          postToGemini(prompt, listOf(currentFrame) + faceReferences.map { it.bitmap })
        } finally {
          faceReferences.forEach { it.bitmap.recycle() }
        }
    val match =
        parseJsonOnly(responseJson)?.let { gson.fromJson(it, FaceMatchResponse::class.java) }

    val confidence = match?.confidence ?: 0.0
    val matchedCustomerId = match?.matchedCustomerId.orEmpty()
    Log.i(
        TAG,
        "Face match response: isMatch=${match?.isMatch}, customerId=$matchedCustomerId, confidence=$confidence",
    )
    if (match == null) {
      Log.w(TAG, "Unable to parse face match JSON: ${responseJson.take(MAX_LOG_RESPONSE_CHARS)}")
    }
    if (!match?.isMatch.orFalse() || confidence < MIN_FACE_MATCH_CONFIDENCE) return null

    return customers.firstOrNull { it.id.equals(matchedCustomerId, ignoreCase = true) }
  }

  suspend fun analyzeDocument(
      documentBitmap: Bitmap,
      customer: Customer?,
  ): DocumentAnalysisResult {
    Log.i(
        TAG,
        "Document analysis request: bitmap=${documentBitmap.width}x${documentBitmap.height}, customer=${customer?.id ?: "none"}",
    )
    val responseJson = postToGemini(buildDocumentAnalysisPrompt(customer), listOf(documentBitmap))
    val parsed = parseJsonOnly(responseJson)
    Log.i(
        TAG,
        "Document analysis response parsed=${parsed != null}, rawChars=${responseJson.length}, documentType=${parsed?.get("documentType")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()}",
    )
    if (parsed == null) {
      Log.w(TAG, "Document analysis raw response: ${responseJson.take(MAX_LOG_RESPONSE_CHARS)}")
    }
    return DocumentAnalysisResult(rawJson = responseJson, json = parsed)
  }

  suspend fun getConversationSuggestion(
      utterance: String,
      customer: Customer?,
  ): String {
    Log.i(
        TAG,
        "Conversation suggestion request: utterance='$utterance', customer=${customer?.id ?: "none"}",
    )
    val customerContext = customer?.let {
      """
      Current customer context:
      - ID: ${it.id}
      - Name: ${it.name}
      - Profile: ${it.profile}
      - Accounts & Balances: ${it.accounts.joinToString { acc -> "${acc.type} (${acc.balance})" }}
      - Last Visit Notes: ${it.history.firstOrNull()?.notes.orEmpty()}
      """.trimIndent()
    } ?: "No customer has been matched yet."

    val customers = customerRepository.loadCustomers()
    val customerDbString = customers.joinToString(separator = "\n\n") { cust ->
      """
      Name: ${cust.name} (ID: ${cust.id})
      - Phone: ${cust.phone}
      - Profile: ${cust.profile}
      - Accounts: ${cust.accounts.joinToString { "${it.type} (${it.balance})" }}
      - Notes: ${cust.history.joinToString { it.notes }}
      """.trimIndent()
    }

    val fallbackKnowledgeBase = """
      1. Checking Accounts:
         - Everyday Checking: $10 monthly service fee, waivable with $500 minimum daily balance or 10+ posted transactions.
         - Clear Access Banking: $5 monthly service fee, waivable for ages 13-24. No overdraft fees.
         - Overdraft Fee: $35 per item.
      2. Savings Accounts:
         - Way2Save Savings: Interest rate is 0.01% APY. $5 monthly fee, waivable with $300 minimum daily balance or $25+ recurring monthly transfer.
         - Platinum Savings: Interest rate is 0.01% APY (relationship rates up to 2.5% APY). $12 monthly fee, waivable with $3,500 minimum daily balance.
      3. Home Loans & Mortgages:
         - Fixed-rate (15-year and 30-year) and adjustable-rate mortgages (ARM) are available.
         - Relationship Benefits: Qualified relationship customers with eligible assets held at Wells Fargo can receive closing cost credits or interest rate discounts.
      4. Credit Cards:
         - Cards like Active Cash and Autograph have no annual fee.
         - 0% introductory APR offers on purchases or balance transfers for qualified customers.
         - Autopay and overdraft protection options are available.
    """.trimIndent()

    val chunks = getOrLoadPdfChunks()
    val knowledgeBaseContext = if (chunks.isNotEmpty()) {
      val relevantChunks = retrieveRelevantChunks(utterance, chunks)
      if (relevantChunks.isNotEmpty()) {
        relevantChunks.joinToString(separator = "\n\n") { chunk ->
          "Source: ${chunk.sourceFile} (Page ${chunk.pageNumber}):\n${chunk.text}"
        }
      } else {
        chunks.take(2).joinToString(separator = "\n\n") { chunk ->
          "Source: ${chunk.sourceFile} (Page ${chunk.pageNumber}):\n${chunk.text}"
        }
      }
    } else {
      Log.w(TAG, "No PDFs found in assets/pdf. Using fallback hardcoded Wells Fargo knowledge base.")
      fallbackKnowledgeBase
    }

    val prompt = """
      You are an AI assistant helping a bank Relationship Manager (RM) who is wearing smart glasses and talking to a customer.
      
      [KNOWLEDGE BASE]
      $knowledgeBaseContext

      [CUSTOMER DATABASE]
      $customerDbString

      [CURRENT CUSTOMER CONTEXT]
      $customerContext

      [RM OR CUSTOMER UTTERANCE]
      "$utterance"

      [INSTRUCTIONS]
      Provide a helpful suggestion for the RM:
      - Answer using the provided knowledge base and customer database ONLY. Do not use external or other bank information. If the information is not present in the knowledge base or customer context, state clearly that you do not have that information.
      - Quote correct interest rates, fees, benefits, or requirements if relevant.
      - Suggest next steps or a helpful response.
      - Keep it short, actionable, and under 50 words.
      - Do not output markdown blocks or JSON, just plain text.
      - CRITICAL: Output ONLY the direct answer/suggestion. Do NOT repeat the prompt, the user role, customer data, the query, or the knowledge base. Do NOT output introductory text, bullet points summarizing the context, or headers. Begin your response immediately with the suggestion itself.

      [SUGGESTION]
    """.trimIndent()

    return postToGemini(prompt)
  }

  suspend fun classifyQueryIsBankRelated(query: String): Boolean {
    val prompt = """
      You are an intent classifier for a banking assistant.
      Determine if the following user query is related to banking, accounts, financial transactions, interest rates, customer details, or loans.
      Respond with "YES" if it is related, and "NO" if it is not related. Do not output any other text.

      Query: "$query"
    """.trimIndent()

    return try {
      val response = postToGemini(prompt)
      response.trim().startsWith("YES", ignoreCase = true)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to classify query, falling back to true", e)
      true
    }
  }

  suspend fun processVoiceCommand(transcribedText: String): VoiceCommandResult {
    val normalized = transcribedText.trim().lowercase()
    if (normalized.contains("hey meta") && normalized.contains("scan")) {
      return VoiceCommandResult(intent = VoiceCommandIntent.SCAN_DOCUMENT, confidence = 1.0)
    }

    val responseJson =
        postToGemini(
            """
            You are an intent classifier for a bank relationship manager assistant.
            Respond with valid JSON only using this schema:
            {"intent":"SCAN_DOCUMENT|NONE","confidence":0.0,"reason":"short reason"}

            Classify whether this text asks the assistant to scan a document:
            "$transcribedText"
            """.trimIndent()
        )
    val parsed = parseJsonOnly(responseJson)
    val response = parsed?.let { gson.fromJson(it, VoiceIntentResponse::class.java) }
    val intent =
        if (response?.intent.equals("SCAN_DOCUMENT", ignoreCase = true)) {
          VoiceCommandIntent.SCAN_DOCUMENT
        } else {
          VoiceCommandIntent.NONE
        }
    return VoiceCommandResult(
        intent = intent,
        confidence = response?.confidence ?: 0.0,
        reason = response?.reason.orEmpty(),
        rawJson = responseJson,
    )
  }

  private fun buildRequestBody(prompt: String, images: List<Bitmap>): String {
    Log.d(
        TAG,
        "Building Gemini request: promptChars=${prompt.length}, imageSizes=${images.joinToString { "${it.width}x${it.height}" }}",
    )
    val parts =
        JsonArray().apply {
          add(JsonObject().apply { addProperty("text", prompt) })
          images.forEach { bitmap ->
            add(
                JsonObject().apply {
                  add(
                      "inlineData",
                      JsonObject().apply {
                        addProperty("mimeType", "image/jpeg")
                        addProperty("data", bitmap.toJpegBase64())
                      },
                  )
                }
            )
          }
        }
    val content =
        JsonObject().apply {
          addProperty("role", "user")
          add("parts", parts)
        }
    return gson.toJson(
        JsonObject().apply {
          add("contents", JsonArray().apply { add(content) })
          add(
              "generationConfig",
              JsonObject().apply {
                addProperty("maxOutputTokens", 120)
                add(
                    "thinkingConfig",
                    JsonObject().apply {
                      addProperty("thinkingLevel", "MINIMAL")
                    },
                )
              },
          )
        }
    )
  }

  private fun parseStreamingText(responseBody: String): String {
    val trimmed = responseBody.trim()
    if (trimmed.isEmpty()) return ""

    val chunks =
        when {
          trimmed.startsWith("[") -> JsonParser.parseString(trimmed).asJsonArray.toList()
          trimmed.startsWith("{") -> listOf(JsonParser.parseString(trimmed))
          else ->
              trimmed
                  .lineSequence()
                  .map { it.removePrefix("data:").trim() }
                  .filter { it.isNotEmpty() && it != "[DONE]" }
                  .mapNotNull { runCatching { JsonParser.parseString(it) }.getOrNull() }
                  .toList()
        }

    return buildString {
      chunks.forEach chunkLoop@{ chunk ->
        val candidates = chunk.asJsonObject.getAsJsonArray("candidates") ?: return@chunkLoop
        candidates.forEach candidateLoop@{ candidate ->
          val parts =
              candidate
                  .asJsonObject
                  .getAsJsonObject("content")
                  ?.getAsJsonArray("parts")
                  ?: return@candidateLoop
          parts.forEach { part ->
            val partObj = part.asJsonObject
            val isThought = partObj.get("thought")?.asBoolean ?: false
            if (!isThought) {
              partObj.get("text")?.asString?.let(::append)
            }
          }
        }
      }
    }.trim()
  }

  private fun buildFaceMatchPrompt(faceReferences: List<CustomerFaceReference>): String {
    val referenceSummaries =
        faceReferences.mapIndexed { index, reference ->
          val customer = reference.customer
          "Image ${index + 2}: ${customer.id}, ${customer.name}, phone ${customer.phone}, profile: ${customer.profile}, faceImage: ${customer.faceImage}"
        }
            .joinToString(separator = "\n")
    return """
      You are matching a live camera frame to a small bank customer reference list.
      Image 1 is the current camera frame.
      Each remaining image is a known customer face reference mapped below.

      Known face references:
      $referenceSummaries

      Respond with valid JSON only using this schema:
      {
        "isMatch": true,
        "matchedCustomerId": "CUST001",
        "confidence": 0.0,
        "reason": "short visual matching reason"
      }
      Use "isMatch": false and an empty matchedCustomerId when confidence is low.
    """.trimIndent()
  }

  private fun buildDocumentAnalysisPrompt(customer: Customer?): String {
    val customerContext =
        customer?.let {
          """
          Current customer:
          - id: ${it.id}
          - name: ${it.name}
          - phone: ${it.phone}
          - profile: ${it.profile}
          - lastVisit: ${it.lastVisit}
          """.trimIndent()
        } ?: "No customer has been matched yet."

    return """
      You are a bank relationship manager assistant. Perform OCR on the document image and analyze it with banking context.

      $customerContext

      Respond with valid JSON only using this schema:
      {
        "documentType": "string",
        "extractedFields": {},
        "summary": "string",
        "customerRelevance": "string",
        "riskFlags": [],
        "recommendedActions": []
      }
      Keep extractedFields as key-value pairs. Use empty arrays when there are no flags or actions.
    """.trimIndent()
  }

  private fun loadCustomerFaceReferences(customers: List<Customer>): List<CustomerFaceReference> =
      customers.mapNotNull { customer ->
        runCatching {
              val bitmap =
                  context.assets
                      .open(customerRepository.faceAssetPath(customer))
                      .use(BitmapFactory::decodeStream)
                      ?: error("Unable to decode ${customer.faceImage}")
              CustomerFaceReference(customer = customer, bitmap = bitmap)
            }
            .onFailure {
              Log.w(TAG, "Missing face reference for ${customer.id}: ${customer.faceImage}")
            }
            .getOrNull()
      }

  private fun parseJsonOnly(text: String): JsonObject? {
    val trimmed = text.trim().removeSurrounding("```json", "```").removeSurrounding("```")
    return runCatching { JsonParser.parseString(trimmed).asJsonObject }
        .recoverCatching {
          val start = trimmed.indexOf('{')
          val end = trimmed.lastIndexOf('}')
          if (start >= 0 && end > start) {
            JsonParser.parseString(trimmed.substring(start, end + 1)).asJsonObject
          } else {
            throw it
          }
        }
        .getOrNull()
  }

  private fun Bitmap.toJpegBase64(): String {
    val resized = resizeForGemini()
    return ByteArrayOutputStream().use { outputStream ->
      resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
      Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
  }

  private fun Bitmap.resizeForGemini(): Bitmap {
    val longestSide = maxOf(width, height)
    if (longestSide <= MAX_IMAGE_SIDE_PX) return this

    val scale = MAX_IMAGE_SIDE_PX.toFloat() / longestSide.toFloat()
    val resizedWidth = (width * scale).toInt().coerceAtLeast(1)
    val resizedHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, resizedWidth, resizedHeight, true)
  }

  private fun Boolean?.orFalse(): Boolean = this == true

  private suspend fun getOrLoadPdfChunks(): List<PdfChunk> {
    if (pdfChunks != null) return pdfChunks!!
    return chunksMutex.withLock {
      if (pdfChunks != null) return@withLock pdfChunks!!
      val chunks = loadAllPdfsFromAssets()
      pdfChunks = chunks
      chunks
    }
  }

  private suspend fun loadAllPdfsFromAssets(): List<PdfChunk> = withContext(Dispatchers.IO) {
    val chunksList = mutableListOf<PdfChunk>()
    try {
      val pdfDir = "pdf"
      val files = context.assets.list(pdfDir) ?: emptyArray()
      Log.i(TAG, "Found ${files.size} PDF files in assets/$pdfDir")
      for (fileName in files) {
        if (fileName.endsWith(".pdf", ignoreCase = true)) {
          val fullPath = "$pdfDir/$fileName"
          try {
            context.assets.open(fullPath).use { inputStream ->
              val reader = PdfReader(inputStream)
              val numPages = reader.numberOfPages
              Log.d(TAG, "Parsing PDF: $fileName with $numPages pages")
              for (page in 1..numPages) {
                val pageText = PdfTextExtractor.getTextFromPage(reader, page)
                if (!pageText.isNullOrBlank()) {
                  chunksList.addAll(chunkText(fileName, pageText, page))
                }
              }
              reader.close()
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error parsing PDF file: $fileName", e)
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error listing PDF assets", e)
    }
    chunksList
  }

  private fun chunkText(fileName: String, text: String, page: Int): List<PdfChunk> {
    val paragraphs = text.split(Regex("(\\n\\r?|\\r){2,}"))
    val chunks = mutableListOf<PdfChunk>()
    
    var currentChunk = StringBuilder()
    for (para in paragraphs) {
      val trimmed = para.trim()
      if (trimmed.isEmpty()) continue
      
      if (currentChunk.isNotEmpty() && currentChunk.length + trimmed.length > 500) {
        chunks.add(PdfChunk(fileName, currentChunk.toString(), page))
        currentChunk = StringBuilder()
      }
      
      if (currentChunk.isNotEmpty()) {
        currentChunk.append("\n\n")
      }
      currentChunk.append(trimmed)
    }
    if (currentChunk.isNotEmpty()) {
      chunks.add(PdfChunk(fileName, currentChunk.toString(), page))
    }
    
    if (chunks.isEmpty() && text.isNotBlank()) {
      var index = 0
      while (index < text.length) {
        val end = minOf(index + 500, text.length)
        chunks.add(PdfChunk(fileName, text.substring(index, end), page))
        index += 400
      }
    }
    
    return chunks
  }

  private fun retrieveRelevantChunks(utterance: String, chunks: List<PdfChunk>, topN: Int = 3): List<PdfChunk> {
    if (chunks.isEmpty()) return emptyList()
    
    val stopWords = setOf(
      "the", "a", "an", "and", "or", "but", "if", "then", "else", "when", 
      "at", "by", "for", "with", "about", "against", "between", "into", 
      "through", "during", "before", "after", "above", "below", "to", 
      "from", "up", "down", "in", "out", "on", "off", "over", "under", 
      "again", "further", "then", "once", "here", "there", "all", "any", 
      "both", "each", "few", "more", "most", "other", "some", "such", 
      "no", "nor", "not", "only", "own", "same", "so", "than", "too", 
      "very", "s", "t", "can", "will", "just", "don", "should", "now", 
      "i", "me", "my", "myself", "we", "our", "ours", "ourselves", "you", 
      "your", "yours", "yourself", "yourselves", "he", "him", "his", 
      "himself", "she", "her", "hers", "herself", "it", "its", "itself", 
      "they", "them", "their", "theirs", "themselves", "what", "which", 
      "who", "whom", "this", "that", "these", "those", "am", "is", "are", 
      "was", "were", "be", "been", "being", "have", "has", "had", "having", 
      "do", "does", "did", "doing", "would", "should", "could", "ought", 
      "i'm", "you're", "he's", "she's", "it's", "we're", "they're", 
      "i've", "you've", "we've", "they've", "i'd", "you'd", "he'd", 
      "she'd", "we'd", "they'd", "i'll", "you'll", "he'll", "she'll", 
      "we'll", "they'll", "isn't", "aren't", "wasn't", "weren't", 
      "hasn't", "haven't", "hadn't", "doesn't", "don't", "didn't", 
      "won't", "wouldn't", "shan't", "shouldn't", "can't", "cannot", 
      "couldn't", "mustn't", "let's", "that's", "who's", "what's", 
      "here's", "there's", "when's", "where's", "why's", "how's"
    )
    
    val queryTokens = utterance.lowercase()
      .split(Regex("[^a-zA-Z0-9']"))
      .map { it.trim() }
      .filter { it.length > 1 && it !in stopWords }
      .toSet()
      
    if (queryTokens.isEmpty()) {
      return chunks.take(topN)
    }
    
    val scoredChunks = chunks.map { chunk ->
      val chunkTextLower = chunk.text.lowercase()
      var score = 0.0
      
      for (token in queryTokens) {
        if (chunkTextLower.contains(token)) {
          score += 1.0
          if (chunkTextLower.contains("\\b${Regex.escape(token)}\\b".toRegex())) {
            score += 1.0
          }
          val occurrences = chunkTextLower.split(token).size - 1
          score += occurrences * 0.1
        }
      }
      chunk to score
    }
    
    return scoredChunks
      .filter { it.second > 0.0 }
      .sortedByDescending { it.second }
      .map { it.first }
      .take(topN)
  }

  companion object {
    private const val TAG = "CameraAccess:GeminiService"
    private const val MODEL_ID = "gemma-4-26b-a4b-it"
    private const val GEMINI_ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_ID:streamGenerateContent"
    private const val PLACEHOLDER_API_KEY = "your_actual_key_here"
    private const val JPEG_QUALITY = 78
    private const val MAX_IMAGE_SIDE_PX = 1024
    private const val MIN_FACE_MATCH_CONFIDENCE = 0.65
    private const val MAX_LOG_RESPONSE_CHARS = 500
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val DEFAULT_HTTP_CLIENT =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()
  }
}

data class DocumentAnalysisResult(
  val rawJson: String,
  val json: JsonObject?,
)

data class VoiceCommandResult(
  val intent: VoiceCommandIntent,
  val confidence: Double,
  val reason: String = "",
  val rawJson: String = "",
)

enum class VoiceCommandIntent {
  SCAN_DOCUMENT,
  NONE,
}

class GeminiException(
  message: String,
  val responseBody: String? = null,
) : IOException(message)

private data class FaceMatchResponse(
  val isMatch: Boolean = false,
  val matchedCustomerId: String = "",
  val confidence: Double = 0.0,
  val reason: String = "",
)

private data class VoiceIntentResponse(
  val intent: String = "NONE",
  val confidence: Double = 0.0,
  val reason: String = "",
)

private data class CustomerFaceReference(
  val customer: Customer,
  val bitmap: Bitmap,
)

data class PdfChunk(
  val sourceFile: String,
  val text: String,
  val pageNumber: Int,
)
