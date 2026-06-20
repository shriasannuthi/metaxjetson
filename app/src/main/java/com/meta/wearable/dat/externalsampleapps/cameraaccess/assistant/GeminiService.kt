package com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.ConversationRole
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.ConversationTurn
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A connection-reusing REST client. It emits model text as soon as each streamed chunk arrives. */
class GeminiService(
    private val apiKey: String,
    private val gson: Gson = Gson(),
    private val client: OkHttpClient = sharedClient,
) {
  fun streamAnswer(
      customer: Customer,
      question: String,
      conversation: List<ConversationTurn> = emptyList(),
  ): Flow<String> = callbackFlow {
    if (apiKey.isBlank()) {
      close(IllegalStateException("Add GEMINI_API_KEY to local.properties"))
      return@callbackFlow
    }

    val request = Request.Builder()
        .url("$BASE_URL/$MODEL:streamGenerateContent?key=$apiKey&alt=json")
        .post(buildBody(customer, question, conversation).toRequestBody(JSON_MEDIA_TYPE))
        .build()
    val call = client.newCall(request)
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) { close(e) }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (!response.isSuccessful) {
            val message = response.body?.string()?.take(500).orEmpty()
            close(IOException("Gemini request failed (${response.code}): $message"))
            return
          }
          try {
            val body = requireNotNull(response.body)
            JsonReader(body.charStream()).use { reader ->
              reader.beginArray()
              while (reader.hasNext()) {
                val chunk = gson.fromJson<JsonObject>(reader, JsonObject::class.java)
                extractText(chunk).forEach { trySend(it) }
              }
              reader.endArray()
            }
            close()
          } catch (error: Throwable) {
            close(error)
          }
        }
      }
    })
    awaitClose { call.cancel() }
  }

  private fun buildBody(
      customer: Customer,
      question: String,
      conversation: List<ConversationTurn>,
  ): String {
    val contents = JsonArray()
    conversation.forEach { turn ->
      // Skip the latest question if it's already in the conversation to avoid redundancy
      if (turn.text != question || turn.role != ConversationRole.CUSTOMER) {
        contents.add(content(if (turn.role == ConversationRole.ASSISTANT) "model" else "user", turn.text))
      }
    }
    contents.add(content("user", question))

    return JsonObject().apply {
      add("system_instruction", content("user", buildInstruction(customer)))
      add("contents", contents)
      add("generationConfig", JsonObject().apply {
        addProperty("temperature", 0.1) // Lower temperature for more focused answers
        addProperty("maxOutputTokens", 200)
      })
    }.toString()
  }

  private fun buildInstruction(customer: Customer): String = buildString {
    append("You are a bank relationship-manager copilot. Provide only the direct answer to the customer's latest question. ")
    append("Do NOT repeat the customer's question or add any conversational filler like 'Sure' or 'Here is the information'. ")
    append("Answer in at most 3 concise sentences, grounded only in the supplied bank data and conversation. ")
    append("Never expose full account or phone numbers. Do not invent balances, transactions, policies, ")
    append("rates, or approvals. Clearly say when the RM must verify something. Customer data JSON: ")
    append(gson.toJson(customer))
  }

  private fun content(role: String, text: String) = JsonObject().apply {
    addProperty("role", role)
    add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", text) }) })
  }

  private fun extractText(chunk: JsonObject): List<String> =
      chunk.getAsJsonArray("candidates")?.flatMap { candidate ->
        candidate.asJsonObject.getAsJsonObject("content")?.getAsJsonArray("parts")
            ?.mapNotNull { it.asJsonObject.get("text")?.asString }
            .orEmpty()
      }.orEmpty()

  companion object {
    private const val MODEL = "gemma-4-26b-a4b-it"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val sharedClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply {
          maxRequests = 8
          maxRequestsPerHost = 4
        })
        .retryOnConnectionFailure(true)
        .build()
  }
}
