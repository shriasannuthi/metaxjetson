package com.meta.wearable.dat.externalsampleapps.cameraaccess.ai

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalAiClientTest {
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun chatCallsAuthenticatedLocalEndpoint() = runBlocking {
    server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("""{"text":"Local answer","model":"test","latencyMs":12}""")
    )
    val client = LocalAiClient(baseUrl = server.url("/").toString(), token = "secret")

    val answer =
        client.chat(
            prompt = "Analyze locally",
            responseMode = LocalAiResponseMode.DOCUMENT_ANALYSIS,
            maxTokens = 350,
        )

    assertEquals("Local answer", answer)
    val request = server.takeRequest()
    assertEquals("/chat", request.path)
    assertEquals("secret", request.getHeader("X-Local-Token"))
    val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
    assertEquals("Analyze locally", json.get("prompt").asString)
    assertEquals("document_analysis", json.get("responseMode").asString)
    assertEquals(350, json.get("maxTokens").asInt)
  }

  @Test
  fun publicServerAddressIsRejected() {
    assertFalse(LocalAiClient(baseUrl = "https://example.com", token = "secret").isConfigured())
    assertFalse(
        LocalAiClient(baseUrl = "http://192.168.example.com:8000", token = "secret")
            .isConfigured()
    )
    assertTrue(LocalAiClient(baseUrl = "http://192.168.1.20:8000", token = "secret").isConfigured())
  }

  @Test
  fun serverErrorIsExposedWithoutCloudFallback() {
    server.enqueue(
        MockResponse()
            .setResponseCode(503)
            .addHeader("Content-Type", "application/json")
            .setBody("""{"detail":"Local Gemma transcription is unavailable"}""")
    )
    val client = LocalAiClient(baseUrl = server.url("/").toString(), token = "secret")

    val error = runCatching { runBlocking { client.chat("hello") } }.exceptionOrNull()

    assertTrue(error is LocalAiException)
    assertTrue(error?.message.orEmpty().contains("Local Gemma transcription is unavailable"))
    assertEquals(1, server.requestCount)
  }
}
