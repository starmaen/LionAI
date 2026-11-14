package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSessionKeepaliveTest {
    private class FakeHost(private val extendMs: Long, private val replyAfterMs: Long) : WebSessionTransport.Host {
        override fun start(request: WebSessionTransport.Request, done: (WebSessionTransport.Reply) -> Unit) {
            if (extendMs > 0) WebSessionTransport.extend(request.token, System.currentTimeMillis() + extendMs)
            Thread {
                Thread.sleep(replyAfterMs)
                done(WebSessionTransport.Reply("الرد الكامل", true, true))
            }.start()
        }
        override fun cancel(token: String) = Unit
        override fun open(provider: String) = Unit
        override fun isUiThread(): Boolean = false
    }

    @Test fun slowReplySurvivesWhenPageKeepsExtendingTheDeadline() {
        val host = FakeHost(extendMs = 10_000, replyAfterMs = 3_000)
        WebSessionTransport.attach(host)
        try {
            val reply = WebSessionTransport.exchange("deepseek_web", "x", 1000)
            assertTrue(reply.success)
            assertEquals("الرد الكامل", reply.text)
        } finally {
            WebSessionTransport.detach(host)
        }
    }

    @Test fun withoutExtensionAbsoluteTimeoutStillApplies() {
        val host = FakeHost(extendMs = 0, replyAfterMs = 3_500)
        WebSessionTransport.attach(host)
        try {
            val reply = WebSessionTransport.exchange("deepseek_web", "x", 1000)
            assertFalse(reply.success)
        } finally {
            WebSessionTransport.detach(host)
        }
    }
}
