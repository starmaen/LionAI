package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/** ربط شات التطبيق بصفحات مزودات الويب: الأحداث الحية والفلتر الذي يمنع تكرار ما أرسله التطبيق. */
class WebSessionMirrorTest {

    private class Recording : WebSessionMirror.Listener {
        val outbound = mutableListOf<String>()
        val partials = mutableListOf<String>()
        val stages = mutableListOf<String>()
        val turns = mutableListOf<Triple<String, String, String>>() // role, text, provider
        override fun onOutbound(chatId: String, provider: String, fullText: String) { outbound.add(fullText) }
        override fun onPartial(chatId: String, provider: String, fullText: String) { partials.add(fullText) }
        override fun onStage(chatId: String, provider: String, note: String) { stages.add(note) }
        override fun onCapturedTurn(chatId: String, provider: String, role: String, text: String) {
            turns.add(Triple(role, text, provider))
        }
    }

    private var attached: Recording? = null
    @After fun cleanup() { attached?.let { WebSessionMirror.detach(it) } }

    @Test fun liveEventsReachTheAttachedListener() {
        val rec = Recording()
        attached = rec
        WebSessionMirror.attach(rec)
        WebSessionMirror.outbound("c1", "gemini_web", "سياق\n\nرسالة كاملة")
        WebSessionMirror.partial("c1", "gemini_web", "جزء أول من الرد")
        WebSessionMirror.partial("c1", "gemini_web", "جزء أول من الرد وأكمل")
        WebSessionMirror.stage("c1", "gemini_web", "📡 اتصال بصفحة Gemini")
        WebSessionMirror.capturedTurn("c1", "gemini_web", "user", "رسالة كتبها المستخدم في الصفحة")
        WebSessionMirror.capturedTurn("c1", "gemini_web", "assistant", "رد لم يمرّ بالشات")
        assertEquals(listOf("سياق\n\nرسالة كاملة"), rec.outbound)
        assertEquals(listOf("جزء أول من الرد", "جزء أول من الرد وأكمل"), rec.partials)
        assertEquals(1, rec.stages.size)
        assertEquals(listOf("user", "assistant"), rec.turns.map { it.first })
        assertEquals(listOf("gemini_web", "gemini_web"), rec.turns.map { it.third })
    }

    @Test fun blankPartialAndBlankTurnsNeverFire() {
        val rec = Recording()
        attached = rec
        WebSessionMirror.attach(rec)
        WebSessionMirror.partial("c1", "gemini_web", "  ")
        WebSessionMirror.capturedTurn("c1", "gemini_web", "assistant", "   ")
        assertTrue(rec.partials.isEmpty())
        assertTrue(rec.turns.isEmpty())
    }

    @Test fun noListenerIsNeverACrash() {
        WebSessionMirror.partial("c1", "gemini_web", "نص")
        WebSessionMirror.outbound("c1", "gemini_web", "نص")
        WebSessionMirror.stage("c1", "gemini_web", "ملاحظة")
        WebSessionMirror.capturedTurn("c1", "gemini_web", "user", "نص")
        assertFalse(WebSessionMirror.attached())
    }

    @Test fun detachingStopsDelivery() {
        val rec = Recording()
        WebSessionMirror.attach(rec)
        WebSessionMirror.detach(rec)
        WebSessionMirror.partial("c1", "gemini_web", "بعد الانفصال")
        assertTrue(rec.partials.isEmpty())
    }

    @Test fun filterAdmitsEachTurnOnceAndToleratesWhitespaceVariants() {
        val f = WebMirrorFilter()
        val turn = "رسالة المستخدم الأولى\nثلاثة أسطر"
        assertTrue(f.admit(turn))
        assertFalse("نفس النص لا يعاد", f.admit(turn))
        // الصفحة تعيد الرسم بأسطر/مسافات مختلفة: البصمة المطاطة تمنع التكرار.
        assertFalse(f.admit("رسالة المستخدم الأولى\n\nثلاثة أسطر "))
        assertFalse(f.admit("رسالة  المستخدم الأولى ثلاثة أسطر"))
        assertFalse(f.isNew(turn))
        assertTrue(f.isNew("نص مختلف تماماً"))
    }

    @Test fun markOwnPreventsAppSentTextFromMirroring() {
        val f = WebMirrorFilter()
        val outbound = "سياق المحادثة السابقة\n— انتهى السياق —\n\nالرسالة الجديدة: كم القاهرة؟"
        f.markOwn(outbound)
        assertFalse(f.admit(outbound))
        // الصفحة تعيد رسم ما كتبه التطبيق مع فروق أسطر/مسافات فقط: لا ينعكس مكرراً.
        assertFalse(f.admit("سياق المحادثة السابقة\n\n— انتهى السياق —\n\n\nالرسالة الجديدة: كم القاهرة؟"))
        assertTrue(f.admit("سؤال كتبه المستخدم بنفسه داخل الصفحة"))
    }

    @Test fun filterEvictsOldestSignaturesBeyondCapacity() {
        val f = WebMirrorFilter(maxSignatures = 8)
        (1..12).forEach { i -> assertTrue(f.admit("دور رقم $i")) }
        // الأقدم طُرد من ذاكرة البصمات، فيصبح «جديداً» ثانية؛ الأحدث يبقى معلوماً.
        assertTrue(f.isNew("دور رقم 1"))
        assertFalse(f.isNew("دور رقم 12"))
    }

    @Test fun filterClearsEverything() {
        val f = WebMirrorFilter()
        f.markOwn("نص التطبيق")
        f.clear()
        assertTrue(f.admit("نص التطبيق"))
    }

    @Test fun webOutboundSurvivesSaveAndLoadRoundTrip() {
        val outbound = "سياق كامل…\n\nالرسالة: أكمل من حيث توقفت"
        val encoded = ChatStore.encode(listOf(
            Message("رد من صفحة المزود", false, via = "🌐 Gemini · رد الصفحة", webOutbound = outbound)
        ))
        val decoded = ChatStore.decode(encoded).single()
        assertEquals(outbound, decoded.webOutbound)
        assertEquals("🌐 Gemini · رد الصفحة", decoded.via)
        // الرسائل القديمة بلا الحقل تبقى سليمة.
        val legacy = ChatStore.decode(ChatStore.encode(listOf(Message("نص عادي", true))))
        assertEquals(null, legacy.single().webOutbound)
    }
}
