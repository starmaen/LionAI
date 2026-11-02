package com.lion.ai

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A real local HTTP server, not a mocked successful response inside the runtime. */
class WebChatTransportTest {
    @Test fun gatewayReceivesHistorySystemAndExactModelAndReturnsReply() {
        val server = MockWebServer()
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody("""{"choices":[{"message":{"content":"وصل الرد"}}]}"""))
        server.start()
        try {
            val prefs = FakePrefs()
            prefs.edit().putBoolean("web_chat_enabled", true)
                .putBoolean("web_chat_enabled_claude_web", true)
                .putString("web_chat_model_claude_web", "custom_adapter/actual-model")
                .putString(WebGateway.KEY_URL, server.url("/v1").toString())
                .putString(WebGateway.KEY_TOKEN, "test-relay-token")
                .putString(WebGateway.KEY_MODE, WebGateway.MODE_ONLY).apply()
            LlmClient.reloadOverrides(prefs)
            val reply = LlmClient.ask("سؤال جديد", "", "claude_web",
                listOf(ChatTurn("user", "سؤال سابق"), ChatTurn("assistant", "رد سابق")),
                systemPrompt = "تعليمات الأدوات والضوابط", readTimeoutMs = 5000)
            assertEquals("وصل الرد", reply)
            val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("POST", recorded.method)
            assertEquals("/v1/chat/completions", recorded.path)
            val request = JSONObject(recorded.body.readUtf8())
            assertEquals("Bearer test-relay-token", recorded.getHeader("Authorization"))
            assertEquals("custom_adapter/actual-model", request.getString("model"))
            val messages = request.getJSONArray("messages")
            assertEquals("تعليمات الأدوات والضوابط", messages.getJSONObject(0).getString("content"))
            assertEquals("سؤال سابق", messages.getJSONObject(1).getString("content"))
            assertEquals("رد سابق", messages.getJSONObject(2).getString("content"))
            assertEquals("سؤال جديد", messages.getJSONObject(3).getString("content"))
            // Disabling the route must block sending even if its model remains configured.
            prefs.edit().putBoolean("web_chat_enabled_claude_web", false).apply()
            assertTrue(LlmClient.isErrorReply(LlmClient.ask("لا ترسل", "", "claude_web", emptyList())))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
            LlmClient.reloadOverrides(FakePrefs())
        }
    }
}
