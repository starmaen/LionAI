package com.lion.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A real local HTTP server, not a mocked successful response inside the runtime. */
class WebChatTransportTest {
    @Test fun gatewayReceivesHistorySystemAndExactModelAndReturnsReply() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var request: JSONObject? = null
        var auth: String? = null
        server.createContext("/v1/chat/completions") { exchange ->
            auth = exchange.requestHeaders.getFirst("Authorization")
            request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            val response = """{"choices":[{"message":{"content":"وصل الرد"}}]}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val prefs = FakePrefs()
            prefs.edit().putBoolean("web_chat_enabled", true)
                .putBoolean("web_chat_enabled_claude_web", true)
                .putString("web_chat_model_claude_web", "custom_adapter/actual-model")
                .putString(WebGateway.KEY_URL, "http://127.0.0.1:${server.address.port}/v1")
                .putString(WebGateway.KEY_TOKEN, "test-relay-token")
                .putString(WebGateway.KEY_MODE, WebGateway.MODE_ONLY).apply()
            LlmClient.reloadOverrides(prefs)
            val reply = LlmClient.ask("سؤال جديد", "", "claude_web",
                listOf(ChatTurn("user", "سؤال سابق"), ChatTurn("assistant", "رد سابق")),
                systemPrompt = "تعليمات الأدوات والضوابط", readTimeoutMs = 2000)
            assertEquals("وصل الرد", reply)
            assertEquals("Bearer test-relay-token", auth)
            assertEquals("custom_adapter/actual-model", request!!.getString("model"))
            val messages = request!!.getJSONArray("messages")
            assertEquals("تعليمات الأدوات والضوابط", messages.getJSONObject(0).getString("content"))
            assertEquals("سؤال سابق", messages.getJSONObject(1).getString("content"))
            assertEquals("رد سابق", messages.getJSONObject(2).getString("content"))
            assertEquals("سؤال جديد", messages.getJSONObject(3).getString("content"))
            // Disabling the route must block sending even if its model remains configured.
            request = null
            prefs.edit().putBoolean("web_chat_enabled_claude_web", false).apply()
            assertTrue(LlmClient.isErrorReply(LlmClient.ask("لا ترسل", "", "claude_web", emptyList())))
            assertNull(request)
        } finally {
            server.stop(0)
            LlmClient.reloadOverrides(FakePrefs())
        }
    }
}
