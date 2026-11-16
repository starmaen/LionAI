package com.lion.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StopAndRiskTest {
    @Test fun explicitStopAndDenialsAreHardStops() {
        listOf("توقف", "قلت لاتعدل اي شيء", "لاترفع تعديلات", "لا تعدل", "Stop!").forEach {
            assertTrue(it, AgentInterrupt.isHardStop(it))
            assertTrue(it, AgentInterrupt.deniesChanges(it))
        }
    }

    @Test fun longObjectionRevokesWriteButIsNotAHardStop() {
        val t = "انا سالت سؤال واضح ماهو رقم البناء بعد الدمج وانت لم تجيب لماذا الدمج تم ليس مطلوب منك اي تعديل لاتعدل"
        assertTrue(AgentInterrupt.deniesChanges(t))
        assertFalse(AgentInterrupt.isHardStop(t))
    }

    @Test fun ordinaryMessagesAreNotStops() {
        listOf("لا مشكلة أكمل", "توقف عن تعديل الملف وافعل كذا", "ماهو رقم البناء؟").forEach {
            assertFalse(it, AgentInterrupt.isHardStop(it))
        }
    }

    @Test fun interruptFlagsAreConsumedOnce() {
        AgentInterrupt.request("c1", "ملاحظة")
        assertTrue(AgentInterrupt.isPending("c1"))
        assertTrue(AgentInterrupt.consume("c1"))
        assertFalse(AgentInterrupt.isPending("c1"))
        AgentInterrupt.revokeWrite("c1")
        assertTrue(AgentInterrupt.consumeRevoke("c1"))
        assertFalse(AgentInterrupt.consumeRevoke("c1"))
        AgentInterrupt.request("c2", "x"); AgentInterrupt.revokeWrite("c2"); AgentInterrupt.clear("c2")
        assertFalse(AgentInterrupt.isPending("c2")); assertFalse(AgentInterrupt.consumeRevoke("c2"))
    }

    private fun call(name: String, args: String) = ToolCall(name, JSONObject(args))

    @Test fun riskyOperationsAreDetectedAndNamedTargetsPass() {
        val del = RiskPolicy.assess(call("github_delete_branch", """{"repo":"a/b","branch":"old-feature"}"""))
        assertNotNull(del)
        assertFalse(RiskPolicy.requestNames(del!!, "حسّن الأداء"))
        assertTrue(RiskPolicy.requestNames(del, "احذف الفرع old-feature"))
        assertNotNull(RiskPolicy.assess(call("shell_exec", """{"command":"rm -rf build"}""")))
        assertNotNull(RiskPolicy.assess(call("file_delete", """{"path":"app/src","recursive":true}""")))
        assertNotNull(RiskPolicy.assess(call("file_write", """{"path":"keystore/release.jks","content":"x"}""")))
    }

    @Test fun ordinaryWorkIsNotBlocked() {
        assertNull(RiskPolicy.assess(call("file_write", """{"path":"app/src/Main.kt","content":"x"}""")))
        assertNull(RiskPolicy.assess(call("shell_exec", """{"command":"git status"}""")))
        assertNull(RiskPolicy.assess(call("github_push_workspace", """{"repo":"a/b","path":"p","dry_run":true}""")))
    }

    @Test fun riskApprovalIsPerOperationAndClearedForNewTask() {
        val m = TaskContinuity()
        m.begin("نظّف المشروع", false)
        m.pendingRisk.add("k1")
        m.begin("نعم", true, answering = true)
        assertTrue("k1" in m.riskApproved)
        m.begin("مهمة جديدة", false)
        assertTrue(m.riskApproved.isEmpty())
    }
}

class GlyphsTest {
    @Test fun statusEmojiBecomeProfessionalSymbols() {
        assertEquals("✓ تم  ✕ فشل", Glyphs.modernize("✅ تم  ❌ فشل"))
        assertEquals("⊘ ممنوع", Glyphs.modernize("⛔ ممنوع"))
        assertTrue(Glyphs.modernize("⚠️ تنبيه").startsWith("⚠"))
    }

    @Test fun plainTextAndExpressiveEmojiAreUntouched() {
        assertEquals("مرحباً بك 🙂", Glyphs.modernize("مرحباً بك 🙂"))
        assertEquals("نص عادي", Glyphs.modernize("نص عادي"))
    }

    @Test fun promptCarriesTheExpressionGuide() {
        assertTrue(LLM_SYSTEM_PROMPT.contains("دليل الرموز التعبيرية"))
    }
}
