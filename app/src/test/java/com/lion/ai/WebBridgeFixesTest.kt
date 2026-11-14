package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebBridgeFixesTest {
    @Test fun intentFallbackUrlIsFollowedOnlyWhenHttps() {
        val url = "intent://chat#Intent;scheme=ms-copilot;S.browser_fallback_url=https%3A%2F%2Fcopilot.com%2Fchats;end"
        assertEquals("https://copilot.com/chats", intentFallbackUrl(url))
        assertNull(intentFallbackUrl("intent://x#Intent;scheme=a;S.browser_fallback_url=http%3A%2F%2Fevil.test;end"))
        assertNull(intentFallbackUrl("ms-copilot://open"))
        assertNull(intentFallbackUrl("https://copilot.com/"))
    }

    @Test fun appStoreRedirectsAreRecognised() {
        assertTrue(isAppStoreUrl("https://play.google.com/store/apps/details?id=com.microsoft.copilot"))
        assertTrue(isAppStoreUrl("market://details?id=com.microsoft.copilot"))
        assertTrue(!isAppStoreUrl("https://copilot.com/"))
    }

    private class Echo : WebSessionTransport.Host {
        val seen = mutableListOf<WebSessionTransport.Request>()
        override fun start(request: WebSessionTransport.Request, done: (WebSessionTransport.Reply) -> Unit) {
            seen += request
            done(WebSessionTransport.Reply("ok", true, true))
        }
        override fun cancel(token: String) {}
        override fun open(provider: String) {}
        override fun isUiThread() = false
    }

    @Test fun chatIdAndFilesReachTheHost() {
        val host = Echo()
        WebSessionTransport.attach(host)
        try {
            val file = WebSessionTransport.WebFile("a.png", "image/png", "AAAA")
            val reply = WebSessionTransport.exchange("claude_web", "hi", 2000, chatId = "chat_A", files = listOf(file))
            assertTrue(reply.success)
            assertEquals("chat_A", host.seen.single().chatId)
            assertEquals(listOf(file), host.seen.single().files)
        } finally {
            WebSessionTransport.detach(host)
        }
    }

    @Test fun directExecutiveCommandIsOfficialPermission() {
        assertTrue(WriteIntent.isDirectCommand("ارجوك اصلح جسر الويب في الملف WebSessionTransport.kt"))
        assertTrue(WriteIntent.isDirectCommand("قم بتعديل الملف app/build.gradle.kts"))
        assertTrue(WriteIntent.isDirectCommand("المطلوب اصلاح الخطأ في المشروع"))
        assertTrue(!WriteIntent.isDirectCommand("هل يمكنك تعديل الملف؟"))
        assertTrue(!WriteIntent.isDirectCommand("كيف احذف ملفا؟"))
        assertTrue(!WriteIntent.isDirectCommand("اكتب لي رسالة شكر"))
        assertTrue(!WriteIntent.isDirectCommand("لا تعدل شيئا فقط افحص"))
    }
}
