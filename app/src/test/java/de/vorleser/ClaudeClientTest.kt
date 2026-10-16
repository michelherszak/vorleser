package de.vorleser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class ClaudeClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    @After fun close() { server.shutdown() }

    private fun response(text: String = "Sehr geehrte Frau Müller,\nbitte zahlen Sie 123,45 € bis 31.10.2026.", kind: String = "document", stop: String = "end_turn"): String =
        JSONObject().put("stop_reason", stop).put("content", JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", ""))
            .put(JSONObject().put("type", "text").put("text", JSONObject().put("kind", kind).put("text", text).toString()))).toString()

    @Test fun `complete transcription preserves original numbers and paragraphs`() {
        assertEquals("Sehr geehrte Frau Müller,\nbitte zahlen Sie 123,45 € bis 31.10.2026.", ClaudeClient.parseResponse(200, response()))
    }

    @Test fun `truncation and context limits are never accepted as a complete page`() {
        for (stop in listOf("max_tokens", "model_context_window_exceeded")) {
            val error = assertThrows(ReadingException::class.java) { ClaudeClient.parseResponse(200, response(stop = stop)) }
            assertEquals(Recovery.RETAKE, error.recovery)
            assertTrue(error.userMessage.contains("abgeschnitten"))
        }
    }

    @Test fun `unreadable photo requests a retake rather than becoming a saved page`() {
        val error = assertThrows(ReadingException::class.java) { ClaudeClient.parseResponse(200, response("", "unreadable")) }
        assertEquals(Recovery.RETAKE, error.recovery)
    }

    @Test fun `empty malformed refused and unfinished replies are rejected`() {
        for (body in listOf("{}", "not json", response(""), response(stop = "refusal"), response(stop = "pause_turn"))) {
            assertThrows(ReadingException::class.java) { ClaudeClient.parseResponse(200, body) }
        }
    }

    @Test fun `credentials quota overload and service errors offer useful recovery`() {
        for ((code, action) in listOf(401 to Recovery.SETTINGS, 403 to Recovery.SETTINGS, 402 to Recovery.SETTINGS,
            429 to Recovery.RETRY, 503 to Recovery.RETRY, 529 to Recovery.RETRY, 413 to Recovery.RETAKE)) {
            val error = assertThrows(ReadingException::class.java) { ClaudeClient.parseResponse(code, "private server details") }
            assertEquals(action, error.recovery)
            assertFalse(error.userMessage.contains("private server details"))
        }
        val noCredit = """{"error":{"type":"invalid_request_error","message":"Your credit balance is too low"}}"""
        assertEquals(Recovery.SETTINGS, assertThrows(ReadingException::class.java) { ClaudeClient.parseResponse(400, noCredit) }.recovery)
    }

    @Test fun `upload uses current model structured output and one photo`() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        val file = temporary.newFile("page.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val client = ClaudeClient(endpoint = server.url("/v1/messages").toString())
        assertTrue(client.analyze("test-key", file).contains("123,45 €"))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("test-key", request.getHeader("x-api-key"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("claude-sonnet-5-5", body.getString("model"))
        assertEquals("between_tools", body.getJSONObject("thinking").getString("type"))
        assertEquals("json_schema", body.getJSONObject("output_config").getJSONObject("format").getString("type"))
        assertEquals(8192, body.getInt("max_tokens"))
        assertEquals("AQID", body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            .getJSONObject(0).getJSONObject("source").getString("data"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `missing key does not upload anything`() {
        val client = ClaudeClient(endpoint = server.url("/").toString())
        val error = assertThrows(ReadingException::class.java) { runBlocking { client.analyze(" ", temporary.newFile()) } }
        assertEquals(Recovery.SETTINGS, error.recovery)
        assertEquals(0, server.requestCount)
    }

    @Test fun `rate limit does not automatically repeat a billable upload`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        val client = ClaudeClient(endpoint = server.url("/").toString())
        val error = assertThrows(ReadingException::class.java) { runBlocking { client.analyze("key", temporary.newFile()) } }
        assertEquals(Recovery.RETRY, error.recovery)
        assertEquals(1, server.requestCount)
    }

    @Test fun `invalid key characters request settings without an upload`() {
        val client = ClaudeClient(endpoint = server.url("/").toString())
        val error = assertThrows(ReadingException::class.java) {
            runBlocking { client.analyze("key\nwrong", temporary.newFile()) }
        }
        assertEquals(Recovery.SETTINGS, error.recovery)
        assertEquals(0, server.requestCount)
    }

    @Test fun `cancelling a reading cancels the underlying network request`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val client = ClaudeClient(http, server.url("/").toString())
        val photo = temporary.newFile()
        val reading = async(Dispatchers.Default) { client.analyze("key", photo) }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        withTimeout(3000) { reading.cancelAndJoin() }
        assertTrue(reading.isCancelled)
        assertTrue(http.dispatcher.runningCalls().all { it.isCanceled() })
    }

    @Test fun `network deadline produces a retryable message`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
        val client = ClaudeClient(http, server.url("/").toString())
        val error = assertThrows(ReadingException::class.java) { runBlocking { client.analyze("key", temporary.newFile()) } }
        assertEquals(Recovery.RETRY, error.recovery)
        assertTrue(error.userMessage.contains("zu lange"))
    }
}
