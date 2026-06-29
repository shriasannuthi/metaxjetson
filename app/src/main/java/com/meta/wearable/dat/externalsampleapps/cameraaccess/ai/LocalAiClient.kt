/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ai

import android.graphics.Bitmap
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LocalAiClient(
    baseUrl: String = BuildConfig.LOCAL_AI_BASE_URL,
    private val token: String = BuildConfig.LOCAL_AI_TOKEN,
    private val httpClient: OkHttpClient = DEFAULT_HTTP_CLIENT,
    private val gson: Gson = Gson(),
) {
  private val serverUrl: HttpUrl? = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
  private val groundingHttpClient =
      httpClient
          .newBuilder()
          .readTimeout(GROUNDING_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          .callTimeout(GROUNDING_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          .build()

  fun isConfigured(): Boolean =
      token.isNotBlank() && serverUrl?.let { it.scheme == "http" && it.host.isPrivateLanHost() } == true

  suspend fun chat(
      prompt: String,
      responseMode: LocalAiResponseMode = LocalAiResponseMode.TEXT,
      maxTokens: Int = DEFAULT_MAX_TOKENS,
  ): String {
    val body =
        JsonObject().apply {
          addProperty("prompt", prompt)
          addProperty("responseMode", responseMode.wireValue)
          addProperty("maxTokens", maxTokens)
        }
    return executeTextRequest(
        Request.Builder()
            .url(endpoint("chat"))
            .addHeader(TOKEN_HEADER, token)
            .post(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
    )
  }

  suspend fun ground(documentBitmap: Bitmap): String {
    val imageBytes =
        ByteArrayOutputStream().use { output ->
          if (!documentBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
            throw LocalAiException("Could not encode the document image")
          }
          output.toByteArray()
        }
    val requestBody =
        MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "document.jpg",
                imageBytes.toRequestBody(JPEG_MEDIA_TYPE),
            )
            .build()
    return executeTextRequest(
        Request.Builder()
            .url(endpoint("ground"))
            .addHeader(TOKEN_HEADER, token)
            .post(requestBody)
            .build(),
        groundingHttpClient,
    )
  }

  private fun endpoint(path: String): HttpUrl {
    val base = serverUrl
    if (!isConfigured() || base == null) {
      throw LocalAiException(
          "Local AI server is not configured with a local URL and token"
      )
    }
    return base.newBuilder().addPathSegment(path).build()
  }

  private suspend fun executeTextRequest(
      request: Request,
      requestClient: OkHttpClient = httpClient,
  ): String =
      withContext(Dispatchers.IO) {
        try {
          requestClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
              throw LocalAiException(
                  "Local AI request failed: HTTP ${response.code}. ${responseBody.errorSummary()}",
                  responseBody,
              )
            }
            val text =
                runCatching {
                      JsonParser.parseString(responseBody)
                          .asJsonObject
                          .get("text")
                          ?.asString
                          .orEmpty()
                    }
                    .getOrElse {
                      throw LocalAiException("Local AI returned an invalid response", responseBody)
                    }
                    .trim()
            if (text.isBlank()) {
              throw LocalAiException("Local AI returned an empty response", responseBody)
            }
            text
          }
        } catch (e: LocalAiException) {
          throw e
        } catch (e: IOException) {
          throw LocalAiException(
              "Cannot reach the local AI server. Keep the USB cable connected and run start_local_ai.ps1 -UsbOnly on the laptop.",
              cause = e,
          )
        }
      }

  private fun String.errorSummary(): String {
    val detail =
        runCatching {
              JsonParser.parseString(this).asJsonObject.get("detail")?.let {
                if (it.isJsonPrimitive) it.asString else it.toString()
              }
            }
            .getOrNull()
    return detail?.takeIf { it.isNotBlank() } ?: take(MAX_ERROR_CHARS).ifBlank { "No response body" }
  }

  private fun String.isPrivateLanHost(): Boolean {
    val normalized = lowercase()
    if (
        normalized == "localhost" ||
            normalized == "::1" ||
            normalized.endsWith(".local") ||
            normalized.startsWith("fe80:") ||
            normalized.startsWith("fc") ||
            normalized.startsWith("fd")
    ) {
      return true
    }
    val octets = normalized.split('.')
    if (octets.size != 4) return false
    val values = octets.map { it.toIntOrNull() ?: return false }
    if (values.any { it !in 0..255 }) return false
    return when (values[0]) {
      10, 127 -> true
      169 -> values[1] == 254
      172 -> values[1] in 16..31
      192 -> values[1] == 168
      else -> false
    }
  }

  private companion object {
    const val TOKEN_HEADER = "X-Local-Token"
    const val DEFAULT_MAX_TOKENS = 320
    const val JPEG_QUALITY = 92
    const val MAX_ERROR_CHARS = 500
    const val GROUNDING_READ_TIMEOUT_SECONDS = 120L
    const val GROUNDING_CALL_TIMEOUT_SECONDS = 130L
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
    val DEFAULT_HTTP_CLIENT =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(100, TimeUnit.SECONDS)
            .build()
  }
}

enum class LocalAiResponseMode(val wireValue: String) {
  TEXT("text"),
  DOCUMENT_ANALYSIS("document_analysis"),
}

class LocalAiException(
    message: String,
    val responseBody: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)
