package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LionLearnTest {
    private val goodAnswer = "الكوروتين في كوتلن هو وحدة تنفيذ خفيفة تسمح بكتابة كود غير متزامن بشكل متسلسل، " +
        "وتُستخدم مع launch وasync داخل نطاق Scope لإدارة دورة حياة العمل."

    @Test
    fun learnsAndRetrievesRelatedQuestionOnly() {
        val kb = LionKnowledge.inMemory()
        assertTrue(kb.add("ما هو الكوروتين في كوتلن وكيف يعمل؟", goodAnswer, "chat", "chat"))
        assertEquals(1, kb.retrieve("اشرح لي الكوروتين في كوتلن").size)
        assertTrue(kb.retrieve("ما عاصمة فرنسا وما هي لغتها الرسمية").isEmpty())
        assertTrue(kb.block("اشرح لي الكوروتين في كوتلن").contains("معارف تعلّمتها"))
    }

    @Test
    fun rejectsSecretsProviderNamesErrorsAndPrivateContext() {
        val kb = LionKnowledge.inMemory()
        assertFalse(kb.add("كيف أستخدم هذا التوكن ghp_abcdefghijklmnop123456 في الدفع؟", goodAnswer, "chat", "chat"))
        assertFalse(kb.add("ما هو الكوروتين في كوتلن وكيف يعمل؟", "أنا نموذج من Gemini. $goodAnswer", "chat", "chat"))
        assertFalse(kb.add("ما هو الكوروتين في كوتلن وكيف يعمل؟", "⚠️ تعذر الوصول إلى مزود. $goodAnswer", "chat", "chat"))
        assertFalse(kb.add("لخص لي هذا الملف المرفق من فضلك", goodAnswer, "chat", "chat"))
        assertFalse(kb.add("راسلني على test@example.com بخصوص الكوروتين", goodAnswer, "chat", "chat"))
        assertEquals(0, kb.size())
    }

    @Test
    fun duplicateQuestionIsNotStoredTwiceAndPersists() {
        var box = ""
        val kb = LionKnowledge({ box }, { box = it })
        assertTrue(kb.add("ما هو الكوروتين في كوتلن وكيف يعمل؟", goodAnswer, "chat", "chat"))
        assertFalse(kb.add("وكيف يعمل الكوروتين في كوتلن ما هو", goodAnswer, "chat", "chat"))
        assertEquals(1, kb.size())
        val reloaded = LionKnowledge({ box }, { box = it })
        assertEquals(1, reloaded.size())
        assertNotNull(reloaded.retrieve("الكوروتين كوتلن").firstOrNull())
    }

    @Test
    fun htmlIsConvertedToCleanPassages() {
        val line = "هذه فقرة طويلة بما يكفي لتُعدّ مقطعاً معرفياً حقيقياً ويجب أن تبقى بعد التنظيف بلا وسوم ولا سكربتات."
        val html = "<html><head><title>t</title><script>alert(1)</script></head><body><nav>menu</nav><p>$line</p><p>$line</p></body></html>"
        val text = LionLearn.htmlToText(html)
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("menu"))
        assertTrue(LionLearn.passages(text).isNotEmpty())
    }

    @Test
    fun onlyPublicHttpsSourcesAreAllowed() {
        assertTrue(LionLearn.isSafeUrl("https://kotlinlang.org/docs/basic-syntax.html"))
        assertFalse(LionLearn.isSafeUrl("http://example.com"))
        assertFalse(LionLearn.isSafeUrl("https://192.168.1.5/admin"))
        assertFalse(LionLearn.isSafeUrl("https://localhost/x"))
    }
}
