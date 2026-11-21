package com.lion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ذكر وسم الأداة في شرح لا يُعدّ محاولة استدعاء معطوبة، بينما المحاولات الفعلية (حتى المعطوبة) تبقى مرصودة. */
class ToolMarkupAttemptTest {
    @Test fun mentionOfTagNameIsNotAnAttempt() {
        assertFalse(ToolMarkup.looksLikeAttempt("الوسم [[TOOL]] يلتقطه المحلّل ويعتبره استدعاءً."))
        assertFalse(ToolMarkup.looksLikeAttempt("الصيغة: `[[TOOL]]{...}[[/TOOL]]`"))
        assertFalse(ToolMarkup.looksLikeAttempt("```\n[[TOOL]] ... [[/TOOL]]\n```"))
        assertFalse(ToolMarkup.looksLikeAttempt("عادي"))
    }

    @Test fun realAndMalformedAttemptsAreStillDetected() {
        assertTrue(ToolMarkup.looksLikeAttempt("[[TOOL]]{\"name\":\"file_read\",\"args\":{}}[[/TOOL]]"))
        assertTrue(ToolMarkup.looksLikeAttempt("```\n[[TOOL]]{\"name\":\"file_stat_many\",\"args\":{\"path\":\".gitignore\"}}[[TOOL]]\n```"))
        assertTrue(ToolMarkup.looksLikeAttempt("[[TOOL]]\n```json\n{\"tool\":\"x\"}\n```"))
        assertTrue(ToolMarkup.looksLikeAttempt("[[TOOL]]{name: \"file_read\""))
        assertTrue(ToolMarkup.looksLikeAttempt("<tool_call>{\"name\":\"x\"}</tool_call>"))
    }
}
