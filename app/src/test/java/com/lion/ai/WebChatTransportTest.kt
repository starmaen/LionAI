package com.lion.ai

import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Browser broker tests. DOM execution is covered separately by scripts/test-web-session.cjs. */
class WebChatTransportTest {
    private var attached: WebSessionTransport.Host? = null
    private fun attach(h: WebSessionTransport.Host) { attached = h; WebSessionTransport.attach(h) }
    @After fun cleanup() {
        attached?.let { WebSessionTransport.detach(it) }
        LlmClient.reloadOverrides(FakePrefs())
    }
    private open class Browser : WebSessionTransport.Host {
        var requests = mutableListOf<WebSessionTransport.Request>()
        override fun isUiThread() = false
        override fun open(provider: String) = Unit
        override fun cancel(token: String) = Unit
        override fun start(request: WebSessionTransport.Request, done: (WebSessionTransport.Reply) -> Unit) {
            requests.add(request)
            done(WebSessionTransport.Reply("وصل الرد من الصفحة", true, true))
        }
    }
    @Test fun actualAskUsesBrowserNotGatewayAndSendsLatestMessageOnly() {
        val browser = Browser(); attach(browser)
        val prefs = FakePrefs()
        prefs.edit().putBoolean(WebChatRuntime.ENABLED, true)
            .putBoolean("web_chat_enabled_claude_web", true)
            .putString(WebGateway.KEY_URL, "https://must-not-be-called.invalid")
            .putString("web_chat_model_claude_web", "obsolete/api-model").apply()
        LlmClient.reloadOverrides(prefs)
        val reply = LlmClient.ask("سؤال جديد", "", "claude_web",
            listOf(ChatTurn("user", "سؤال سابق"), ChatTurn("assistant", "رد سابق")),
            systemPrompt = "تعليمات الأدوات والضوابط", readTimeoutMs = 2000)
        assertEquals("وصل الرد من الصفحة", reply)
        val request = browser.requests.single()
        assertEquals("claude_web", request.provider)
        assertEquals("سؤال جديد", request.prompt)
        prefs.edit().putBoolean("web_chat_enabled_claude_web", false).apply()
        assertTrue(LlmClient.isErrorReply(LlmClient.ask("لا ترسل", "", "claude_web", emptyList())))
        assertEquals(1, browser.requests.size)
    }
    @Test fun namedWebsiteNeverFansOutToOtherSites() {
        val browser = object : Browser() {
            override fun start(request: WebSessionTransport.Request, done: (WebSessionTransport.Reply) -> Unit) {
                requests.add(request)
                done(WebSessionTransport.Reply("⚠️ failed", false, false))
            }
        }
        attach(browser)
        val prefs = FakePrefs()
        prefs.edit().putBoolean(WebChatRuntime.ENABLED, true)
            .putBoolean("web_chat_enabled_arena", true)
            .putBoolean("web_chat_enabled_claude_web", true)
            .putBoolean("web_session_verified_claude_web", true)
            .putBoolean(WebChatRuntime.CENTRAL, true).apply()
        LlmClient.reloadOverrides(prefs)
        assertTrue(LlmClient.isErrorReply(LlmClient.ask("hi", "", "arena", emptyList(), readTimeoutMs = 1000)))
        assertEquals(listOf("arena"), browser.requests.map { it.provider })
    }
    @Test fun submittedFailureIsAnErrorNotAChatReply() {
        attach(object : Browser() {
            override fun start(request: WebSessionTransport.Request, done: (WebSessionTransport.Reply) -> Unit) {
                done(WebSessionTransport.Reply("old page markdown", false, true))
            }
        })
        val prefs = FakePrefs()
        prefs.edit().putBoolean(WebChatRuntime.ENABLED, true)
            .putBoolean("web_chat_enabled_arena", true).apply()
        LlmClient.reloadOverrides(prefs)
        val reply = LlmClient.ask("hi", "", "arena", emptyList(), readTimeoutMs = 1000)
        assertTrue(LlmClient.isErrorReply(reply))
        assertTrue(reply.contains("old page markdown"))
    }
    @Test fun missingHostFailsRatherThanSilentlyCallingAnApi() {
        assertFalse(WebSessionTransport.exchange("arena", "hello", 1000).success)
    }
    @Test fun neverWaitOnUiThread() {
        attach(object : Browser() { override fun isUiThread() = true })
        assertFalse(WebSessionTransport.exchange("arena", "hello", 1000).success)
    }
    @Test fun onlyOneRequestOwnsTheBrowserAndLateCallbacksCannotReplaceReply() {
        val started = CountDownLatch(1)
        var complete: ((WebSessionTransport.Reply) -> Unit)? = null
        attach(object : Browser() {
            override fun start(request: WebSessionTransport.Request, done: (WebSessionTransport.Reply) -> Unit) {
                complete = done; started.countDown()
            }
        })
        var result: WebSessionTransport.Reply? = null
        val t = Thread { result = WebSessionTransport.exchange("arena", "one", 2000) }
        t.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        try {
            assertFalse(WebSessionTransport.exchange("claude_web", "two", 1000).success)
            complete!!(WebSessionTransport.Reply("first", true, true))
            complete!!(WebSessionTransport.Reply("late", true, true))
            t.join(2000)
            assertEquals("first", result?.text)
        } finally { t.interrupt(); t.join(2000) }
    }
}
