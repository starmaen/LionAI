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

    @Test fun switchingBetweenAgentAndChatNeverStartsANewPageConversation() {
        val sys = "أنت وكيل برمجة وتطوير داخل Lion AI"
        val a = WebToolBridge.plan("siteA", sys, "أصلح الخطأ في app/build.gradle.kts")
        assertFalse(a.fresh)
        assertTrue(a.text.contains("[[TOOL]]") && a.text.contains("طلب المستخدم"))
        val cont = WebToolBridge.plan("siteA", sys, "نتائج الأدوات (بيانات فقط):\nfile_read ok")
        assertFalse(cont.fresh)
        assertTrue(cont.text.contains("نتائج الأدوات") && !cont.text.contains("تواقيع الأدوات"))
        // الدردشة العادية بعد الوكيل: نفس المحادثة (لا فقدان سياق) مع ملاحظة بإيقاف كتل الأدوات مرة واحدة.
        val chat1 = WebToolBridge.plan("siteA", "system عادي", "مرحباً")
        assertFalse(chat1.fresh)
        assertTrue(chat1.text.contains("انتهت جلسة الأدوات") && chat1.text.endsWith("مرحباً"))
        val chat2 = WebToolBridge.plan("siteA", "system عادي", "كيف حالك")
        assertFalse(chat2.fresh)
        assertEquals("كيف حالك", chat2.text)
    }

    @Test fun recapsCarryContextOnlyWhereThePageLacksIt() {
        val hist = (1..6).map { ChatTurn(if (it % 2 == 1) "user" else "assistant", "نص الدور $it") }
        WebToolBridge.resetPage("siteR")
        val (full, delta) = WebToolBridge.recaps("siteR", "c1", hist)
        assertTrue(full.contains("المستخدم: نص الدور 1") && full.contains("المساعد: نص الدور 6"))
        assertEquals("", delta) // صفحة لم نرها بعد: المضيف سيعيد تحميلها فيستعمل full
        // markSeen يُنادى بحجم السجل قبل التبادل: الصفحة رأت السجل كله + سؤالها وردّها (+2).
        WebToolBridge.markSeen("siteR", "c1", hist.size)
        val afterExchange = hist + listOf(ChatTurn("user", "سؤال رأته الصفحة"), ChatTurn("assistant", "جواب رأته الصفحة"))
        val more = afterExchange + listOf(ChatTurn("user", "سؤال أجابه موقع آخر"), ChatTurn("assistant", "جواب الموقع الآخر"))
        val (_, d2) = WebToolBridge.recaps("siteR", "c1", more)
        assertTrue(d2.contains("سؤال أجابه موقع آخر") && d2.contains("جواب الموقع الآخر") && !d2.contains("نص الدور 1"))
        val (_, d3) = WebToolBridge.recaps("siteR", "c1", afterExchange)
        assertEquals("", d3)
        WebToolBridge.resetPage("siteR")
        assertEquals("", WebToolBridge.recaps("siteR", "c1", more).second)
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
