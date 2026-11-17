package com.lion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGateTest {
    @Test
    fun trivialQuestionsNeverOpenTools() {
        for (mode in ChatMode.values()) {
            assertFalse(AgentGate.shouldUseAgent("مرحبا", false, mode))
            assertFalse(AgentGate.shouldUseAgent("شكراً", true, mode))
            assertFalse(AgentGate.shouldUseAgent("ما هو بناء الجملة الاسمية؟", false, mode))
            assertFalse(AgentGate.shouldUseAgent("ما معنى كلمة سجل؟", false, mode))
            assertFalse(AgentGate.shouldUseAgent("ما هو العدل؟", false, mode))
            assertFalse(AgentGate.shouldUseAgent("اكتب لي رسالة شكر لصديقي", false, mode))
            assertFalse(AgentGate.shouldUseAgent("عدّل هذه الفقرة لتكون أقصر", false, mode))
        }
    }

    @Test
    fun realWorkOpensTools() {
        assertTrue(AgentGate.shouldUseAgent("اقرأ الملف src/Main.kt", false, ChatMode.CODE))
        assertTrue(AgentGate.shouldUseAgent("أصلح الخطأ في المشروع", false, ChatMode.CODE))
        assertTrue(AgentGate.shouldUseAgent("لماذا فشل البناء؟", false, ChatMode.CODE))
        assertTrue(AgentGate.shouldUseAgent("ارفع التعديلات إلى github.com/a/b", false, ChatMode.GENERAL))
        assertTrue(AgentGate.shouldUseAgent("نفذ", true, ChatMode.CODE))
    }

    @Test
    fun technicalQuestionsInDeveloperModeOpenTools() {
        assertTrue(AgentGate.shouldUseAgent("كيف أستخدم coroutines في kotlin؟", false, ChatMode.CODE))
        assertTrue(AgentGate.shouldUseAgent("لماذا يظهر exception في gradle؟", false, ChatMode.CODE))
        assertFalse(AgentGate.shouldUseAgent("كيف أستخدم coroutines في kotlin؟", false, ChatMode.GENERAL))
        assertFalse(AgentGate.shouldUseAgent("هل تستطيع كتابة كود؟", false, ChatMode.CODE))
        assertFalse(AgentGate.shouldUseAgent("ما هي عاصمة فرنسا", false, ChatMode.CODE))
    }

    @Test
    fun developerModeWorkVerbsOpenToolsWithoutExplicitTarget() {
        val cases = listOf(
            "افحص الأدوات", "اختبر البوابة", "ابدء الاصلاح", "ابدأ الإصلاح", "ابدا الاصلاح",
            "جرب الأداة وأخبرني", "تأكد من عمل الاختبارات", "تحقق من الأخطاء", "دقق في ShellTools",
            "شوف المشكلة", "راجع التقييم وطبق التحسينات", "وافحص التواصل مع الوكيل"
        )
        for (c in cases) assertTrue("يجب فتح الأدوات: $c", AgentGate.shouldUseAgent(c, false, ChatMode.CODE))
    }

    @Test
    fun workVerbsStayDirectInGeneralModeAndForPlainText() {
        assertFalse(AgentGate.shouldUseAgent("افحص الأدوات", false, ChatMode.GENERAL))
        assertFalse(AgentGate.shouldUseAgent("ابدأ الإصلاح", false, ChatMode.GENERAL))
        assertFalse(AgentGate.shouldUseAgent("راجع الرسالة قبل الإرسال", false, ChatMode.CODE))
        assertFalse(AgentGate.shouldUseAgent("حسن الفقرة التالية", false, ChatMode.CODE))
        assertFalse(AgentGate.shouldUseAgent("شكراً", false, ChatMode.CODE))
        assertFalse(AgentGate.shouldUseAgent("مرحبا", false, ChatMode.CODE))
    }
}
