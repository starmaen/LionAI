package com.lion.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskContinuityTest {
    @Test fun newTaskPhraseIsDetectedAndFollowUpsAreNot() {
        assertTrue(WriteIntent.isNewTask("مهمة جديدة"))
        assertTrue(WriteIntent.isNewTask("start over"))
        assertFalse(WriteIntent.isNewTask("استخدم الفرع dev ثم أعد المحاولة"))
        assertFalse(WriteIntent.isNewTask("أكمل"))
    }
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
    @Test fun writingAFileIsAWriteRequestButWritingProseIsNot() {
        listOf("اكتب ملف Main.kt", "أنشئ ملف notes.txt", "write file notes.txt", "اكتب الكود", "أضف مجلد youtube-translator").forEach {
            assertTrue(it, WriteIntent.permits(it))
        }
        listOf("اكتب ملخصا", "write an explanation", "اكتب لي فقرة").forEach {
            assertFalse(it, WriteIntent.permits(it))
        }
    }
    @Test fun explicitApprovalAfterAPermissionQuestionGrantsWrites() {
        val m=TaskContinuity()
        m.begin("هل يمكنك إنشاء مجلد youtube-translator؟", false)
        assertFalse(m.writeGranted)
        m.begin("✅ موافق، تابع", true, answering = true)
        assertTrue(m.writeGranted)
    }
    @Test fun approvalAloneWithoutAnUnfinishedTaskDoesNotGrantWrites() {
        val m=TaskContinuity()
        m.begin("موافق", false)
        assertFalse(m.writeGranted)
    }
    @Test fun approvalWithDecisionCardNotesStillGrantsWrites() {
        val m=TaskContinuity()
        m.begin("أصلح الملف", false)
        m.begin("✅ موافق، تابع\nملاحظات: نفّذ في مجلد notes", true, answering = true)
        assertTrue(m.writeGranted)
    }
    @Test fun explicitDenialRevokesButCasualNegationDoesNot() {
        val granted=TaskContinuity();granted.begin("أصلح الملف",false)
        granted.begin("لا مشكلة، أكمل",true,answering=true)
        assertTrue(granted.writeGranted)
        granted.begin("⛔ لا تنفّذ أي تعديل",true,answering=true)
        assertFalse(granted.writeGranted)
        assertTrue(WriteIntent.isDenial("⛔ لا تنفّذ"))
        assertFalse(WriteIntent.isDenial("لا مشكلة، أكمل"))
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
