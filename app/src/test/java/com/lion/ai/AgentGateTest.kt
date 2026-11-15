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
}
