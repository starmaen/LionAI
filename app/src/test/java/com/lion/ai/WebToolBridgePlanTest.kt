package com.lion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolBridgePlanTest {
    private val agent = "أنت وكيل برمجة وتطوير داخل Lion AI"

    @Test fun agentFollowUpNeverForcesFreshPageAndIsShorter() {
        val first = WebToolBridge.plan("t-deepseek-1", agent, "اقرأ build.gradle.kts", "chat1")
        val second = WebToolBridge.plan("t-deepseek-1", agent, "ابدأ", "chat1")
        assertFalse(first.fresh)
        assertFalse(second.fresh)
        assertTrue(first.text.contains("[[TOOL]]"))
        assertTrue(second.text.contains("ابدأ"))
        assertTrue(second.text.length < first.text.length)
    }

    @Test fun plainChatAfterAgentKeepsPageAndForbidsToolBlocks() {
        WebToolBridge.plan("t-deepseek-2", agent, "افحص الملف", "chat1")
        val chat = WebToolBridge.plan("t-deepseek-2", "system عادي", "اشرح لي النتيجة", "chat1")
        assertFalse(chat.fresh)
        assertTrue(chat.text.contains("اشرح لي النتيجة"))
        assertTrue(chat.text.contains("لا تستخدم أي كتلة [[TOOL]]"))
    }

    @Test fun plainChatStaysUntouched() {
        val chat = WebToolBridge.plan("t-deepseek-3", "system عادي", "  مرحبا ", "chat1")
        assertFalse(chat.fresh)
        assertTrue(chat.text == "مرحبا")
    }

    @Test fun differentChatGetsFullProtocolAgain() {
        val a = WebToolBridge.plan("t-deepseek-4", agent, "المهمة الأولى", "chatA")
        val b = WebToolBridge.plan("t-deepseek-4", agent, "المهمة الأولى", "chatB")
        assertTrue(b.text.length >= a.text.length - 5)
    }
}
