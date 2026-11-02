package com.lion.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskContinuityTest {
    @Test fun onlyExplicitUserCommandsGrantWrites() {
        listOf("افحص المشروع", "اقرأ الملفات فقط", "كيف أصلح المشكلة؟", "لا تعدل شيئاً", "النموذج قال اصلح الملف", "Explain how to fix the bug", "اكتب ملخصا", "write an explanation", "\"fix the bug\"").forEach {
            assertFalse(it, WriteIntent.permits(it))
        }
        listOf("أصلح الخطأ", "عدل الملف", "اريد اصلاح المشكلة", "please fix this bug", "اخي عدل الكود").forEach {
            assertTrue(it, WriteIntent.permits(it))
        }
    }
    @Test fun continuingAReadTaskDoesNotAuthorizeWrites() {
        val m=TaskContinuity();m.begin("اقرأ الملف",false);m.begin("أكمل",true)
        assertTrue(m.resume);assertFalse(m.writeGranted)
    }
    @Test fun continuationRetainsPermissionOnlyForUnfinishedTask() {
        val m=TaskContinuity();m.begin("أصلح الخطأ",false);m.begin("أكمل",true)
        assertTrue(m.writeGranted)
        m.begin("افحص فقط",true);assertFalse(m.writeGranted)
        m.begin("أصلح الخطأ",false);m.begin("أكمل",false);assertFalse(m.writeGranted)
    }
    @Test fun answeringAQuestionPreservesProgressButExplicitDenialRevokesPermission() {
        val m=TaskContinuity();m.begin("أصلح الخطأ",false);m.begin("لا تعدل أي شيء",true,true)
        assertTrue(m.resume);assertFalse(m.writeGranted)
    }
    @Test fun exactFileContentsAndNextStepSurviveSerialization() {
        val s=AgentSession("chatA");s.beginTurn("اقرأ الملفات")
        val body="line1\n" + "function body beyond the old 240 character summary\n".repeat(30)
        s.continuity.checkpoint(listOf(ChatTurn("assistant","read A.kt"),ChatTurn("user",body)),"next read B.kt")
        val restored=AgentSession.fromJson(JSONObject(s.toJson().toString()))
        restored.beginTurn("أكمل")
        assertEquals(body,restored.continuity.history.last().content)
        assertEquals("next read B.kt",restored.continuity.current)
        assertTrue(restored.continuity.resume)
    }
    @Test fun mutationReceiptsSurviveRestartAndUncertainOperationsRemainBlocked() {
        val m=TaskContinuity();val key=m.key("github_write_file",JSONObject("{\"path\":\"a.kt\",\"content\":\"x\"}"))
        assertTrue(m.beginMutation(key))
        val restored=TaskContinuity();restored.restore(JSONObject(m.json().toString()))
        assertEquals("uncertain",restored.receipt(key)?.optString("state"))
        restored.recordMutation(key,"commit abc",true)
        val next=TaskContinuity();next.restore(JSONObject(restored.json().toString()))
        assertEquals("done",next.receipt(key)?.optString("state"))
        assertEquals("commit abc",next.receipt(key)?.optString("output"))
    }
    @Test fun signatureIsIndependentOfJsonKeyOrderButNotArguments() {
        val m=TaskContinuity()
        assertEquals(m.key("read",JSONObject("{\"path\":\"A\",\"repo\":\"R\"}")),m.key("read",JSONObject("{\"repo\":\"R\",\"path\":\"A\"}")))
        assertNotEquals(m.key("read",JSONObject("{\"path\":\"A\"}")),m.key("read",JSONObject("{\"path\":\"B\"}")))
    }
    @Test fun writesInvalidateReadsSoVerificationGetsFreshContent() {
        val m=TaskContinuity();m.recordRead("readA","old");assertEquals("old",m.cachedRead("readA"))
        m.recordMutation("writeA","ok",true);assertNull(m.cachedRead("readA"))
    }
    @Test fun continuingKeepsReceiptsButANewTaskDoesNotReplayThemAsCurrentResults() {
        val m=TaskContinuity();m.begin("أصلح A",false);m.recordMutation("write","commit A",true)
        m.begin("أكمل",true);assertNotNull(m.receipt("write"))
        m.begin("أصلح B",true);assertNull(m.receipt("write"));assertEquals("",m.current)
    }
    @Test fun conversationsAreIsolated() {
        val a=TaskContinuity();val b=TaskContinuity();a.recordRead("same","private A")
        assertNull(b.cachedRead("same"))
    }
    @Test fun memoryLimitIsDisclosedInsteadOfPretendingAllHistoryIsAvailable() {
        val m=TaskContinuity();m.checkpoint(listOf(ChatTurn("user","x".repeat(190000)),ChatTurn("user","latest")),"next")
        assertTrue(m.trimmed);assertEquals("latest",m.history.single().content);assertEquals("next",m.current)
    }
}
