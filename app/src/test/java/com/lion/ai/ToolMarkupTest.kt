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

    @Test fun explicitBranchTestRequestGrantsWriteButNegativePhrasingDoesNot() {
        val ok = "أنشئ فرعاً اختبارياً باسم test/tools-check-1 في مستودع starmaen/LionAI واكتب فيه الملف .lion-test/check.txt ثم شغّل build.yml واحذف الفرع. لديك إذن كتابة كامل على هذا الفرع وحده."
        assertTrue(WriteIntent.permits(ok))
        assertTrue(WriteIntent.isDenial("اختبار أدوات لا تغيّر أي شيء خارج الفرع"))
    }

    @Test fun scopedNegativeConstraintDoesNotCancelAnExplicitCommand() {
        val cmd = "احذف الفرع test/tools-check-1 من مستودع starmaen/LionAI ثم تحقق. لا تحذف أي فرع آخر ولا main."
        assertTrue(WriteIntent.isDenial(cmd))
        assertTrue(WriteIntent.startsImperative(cmd))
        assertTrue(!WriteIntent.isReadOnlyRequest(cmd))
        assertTrue(WriteIntent.permits(cmd))
        val m = TaskContinuity(); m.begin(cmd, false)
        assertTrue(m.writeGranted)
        assertTrue(WriteIntent.isReadOnlyRequest("لا تعدل شيئاً"))
    }

    @Test fun linkFirstMessageWithNegativeConstraintIsNotReadOnly() {
        val msg = "https://github.com/starmaen/LionAI احذف الفرع test/tools-check-1. لا تحذف أي فرع آخر ولا main."
        assertTrue(WriteIntent.isDenial(msg))
        assertTrue(!WriteIntent.isReadOnlyRequest(msg))
        assertTrue(WriteIntent.isReadOnlyRequest("افحص المشروع فقط ولا تعدل شيئاً"))
        assertTrue(WriteIntent.isReadOnlyRequest("لا تعدل شيئاً"))
    }
}
