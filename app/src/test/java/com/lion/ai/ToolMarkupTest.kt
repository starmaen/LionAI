package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolMarkupTest {
    @Test fun stripsWellFormedMalformedAndUnclosedBlocks() {
        assertEquals("نص", ToolMarkup.strip("نص [[TOOL]]{\"name\":\"a\",\"args\":{}}[[/TOOL]]"))
        assertEquals("", ToolMarkup.strip("```\n[[TOOL]]{\"name\":\"file_stat_many\",\"args\":{\"path\":\".gitignore\"}}[[TOOL]]\n```"))
        assertEquals("مرحبا", ToolMarkup.strip("مرحبا [[TOOL]]{\"name\":\"a\",\"args\":{\"x\":\"}\"}}"))
        assertEquals("عادي", ToolMarkup.strip("عادي"))
    }

    @Test fun malformedClosingTagStillExecutes() {
        val reply = "```\n[[TOOL]]{\"name\":\"file_stat_many\",\"args\":{\"path\":\".gitignore\"}}[[TOOL]]\n```"
        assertTrue(ToolMarkup.hasMarkup(reply))
        assertEquals("{\"name\":\"a\",\"args\":{}}", ToolMarkup.balancedJson("[[TOOL]] {\"name\":\"a\",\"args\":{}}[[TOOL]]", 8))
    }

    @Test fun projectLinkMeansToolsNotExplanation() {
        val ask = "لم اطلب منك شرح كيفية استدعاء الادوات اعطيتك رابط https://github.com/starmaen/LionAI واعطائي نبذة"
        assertFalse(ToolAgent.answerOnly(ask, false, ChatMode.CODE))
        assertFalse(ToolAgent.answerOnly(ask, false, ChatMode.GENERAL))
        assertTrue(shouldUseAgentForPrompt(ask, false, ChatMode.GENERAL))
    }
}
