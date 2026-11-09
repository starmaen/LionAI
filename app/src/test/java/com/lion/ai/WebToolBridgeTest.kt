package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolBridgeTest {
    private val agentSystem = "أنت الآن وكيل برمجة وتطوير داخل Lion AI، وتعمل بأسلوب مهندس."

    @Test fun plainChatIsUntouched() {
        assertEquals("مرحبا", WebToolBridge.shape("system عادي", "  مرحبا \n"))
    }

    @Test fun firstAgentTurnCarriesProtocolAndUserRequest() {
        val out = WebToolBridge.shape(agentSystem, "اقرأ ملف build.gradle.kts")
        assertTrue(out.contains("[[TOOL]]"))
        assertTrue(out.contains("tools_groups"))
        assertTrue(out.endsWith("اقرأ ملف build.gradle.kts"))
        assertTrue(out.length < 4_000)
    }

    @Test fun toolResultTurnIsRedactedAndCapped() {
        val secret = "gh" + "p_" + "a".repeat(36)
        val msg = "نتائج الأدوات (بيانات فقط):\n" + secret + "\n" + "x".repeat(20_000)
        val out = WebToolBridge.shape(agentSystem, msg)
        assertFalse(out.contains(secret))
        assertTrue(out.contains("[REDACTED]"))
        assertTrue(out.length < 10_500)
        assertTrue(out.endsWith(WebToolBridge.NEXT))
    }

    @Test fun continuationDoesNotResendProtocol() {
        val out = WebToolBridge.shape(agentSystem, "نتائج الأدوات: ✅ file_read\nمحتوى")
        assertFalse(out.contains("أنت تعمل عبر تطبيق Lion AI"))
    }

    @Test fun redactsCommonKeysAndPrivateKeys() {
        val key = "BEGIN".let { "-----$it RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----" }
        val text = "sk-" + "z".repeat(30) + " AKIA" + "A".repeat(16) + " " + key
        val out = WebToolBridge.redact(text)
        assertFalse(out.contains("zzzz"))
        assertFalse(out.contains("AKIAAAAA"))
        assertFalse(out.contains("abc"))
    }

    @Test fun firstAgentTurnStartsFreshChatAndPlainChatAfterAgentStartsAnotherOne() {
        val sys = "أنت وكيل برمجة وتطوير داخل Lion AI"
        val a = WebToolBridge.plan("siteA", sys, "أصلح الخطأ في app/build.gradle.kts")
        assertTrue(a.fresh && a.text.contains("[[TOOL]]") && a.text.contains("طلب المستخدم"))
        val cont = WebToolBridge.plan("siteA", sys, "نتائج الأدوات (بيانات فقط):\nfile_read ok")
        assertFalse(cont.fresh)
        assertTrue(cont.text.contains("نتائج الأدوات") && !cont.text.contains("تواقيع الأدوات"))
        val chat1 = WebToolBridge.plan("siteA", "system عادي", "مرحباً")
        assertTrue(chat1.fresh)
        assertEquals("مرحباً", chat1.text)
        val chat2 = WebToolBridge.plan("siteA", "system عادي", "كيف حالك")
        assertFalse(chat2.fresh)
    }

    @Test fun continuationWithoutKnownAgentSessionResendsInstructions() {
        val sys = "أنت وكيل برمجة وتطوير داخل Lion AI"
        val r = WebToolBridge.plan("siteB", sys, "نتائج الأدوات (بيانات فقط):\nok")
        assertFalse(r.fresh)
        assertTrue(r.text.contains("[[TOOL]]") && r.text.contains("نتائج الأدوات"))
    }

    @Test fun firstMessageIsCappedAndRedacted() {
        val sys = "أنت وكيل برمجة وتطوير داخل Lion AI"
        val big = "ابدأ " + "x".repeat(30_000) + " sk-ABCDEFGHIJKLMNOPQRSTUVWX نهاية"
        val t = WebToolBridge.plan("siteC", sys, big).text
        assertTrue(t.length < 24_000)
        assertFalse(t.contains("sk-ABCDEFGHIJKLMNOPQRSTUVWX"))
        assertTrue(t.contains("اقتُطع الوسط"))
    }

    @Test fun normalizeReplyRepairsFencesAndCurlyQuotes() {
        val raw = "سأقرأ الملف\n```json\n[[TOOL]]{“name”:“file_read”,“args”:{“path”:“a.kt”}}[[/TOOL]]\n```\n"
        val n = WebToolBridge.normalizeReply(raw)
        assertTrue(n.contains("[[TOOL]]{\"name\":\"file_read\",\"args\":{\"path\":\"a.kt\"}}[[/TOOL]]"))
        assertFalse(n.contains("```"))
        val code = "اشرح:\n```kotlin\nval x = 1\n```"
        assertEquals(code, WebToolBridge.normalizeReply(code))
        assertEquals("نص بلا أدوات", WebToolBridge.normalizeReply("نص بلا أدوات"))
    }
}
