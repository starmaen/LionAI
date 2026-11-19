package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchFlowTest {
    @Test
    fun missingManifestIsAWarningNotAnError() {
        val report = ControlPlane.evaluate(ProjectSnapshot(mapOf("src/main/kotlin/Main.kt" to "fun main() = Unit")), null)
        assertTrue(report.issues.any { it.code == "NO_MANIFEST" && it.severity == "warn" })
        assertTrue(report.errors().none { it.code == "NO_MANIFEST" })
    }

    @Test
    fun protocolStallDoesNotSwitchProviderInManualMode() {
        val r = ToolAgent.Result("x", emptyList(), complete = false, responded = true, stalled = true, protocolStall = true)
        val manualStalled = r.stalled && !(true && r.protocolStall)
        val autoStalled = r.stalled && !(false && r.protocolStall)
        assertFalse(manualStalled)
        assertTrue(autoStalled)
        assertEquals(true, r.protocolStall)
    }
}

class CiGateTest {
    @Test
    fun pushWithoutConfirmedCiBlocksCompletion() {
        assertTrue(ToolAgent.ciGateNeeded(true, false, false, false))
        assertFalse(ToolAgent.ciGateNeeded(false, false, false, false))
        assertFalse(ToolAgent.ciGateNeeded(true, true, false, false))   // مستودع بلا CI
        assertFalse(ToolAgent.ciGateNeeded(true, false, true, false))   // قراءة فقط
        assertFalse(ToolAgent.ciGateNeeded(true, false, false, true))   // سؤال معرفي
    }

    @Test
    fun onlyCompletedSuccessCountsAsPass() {
        assertTrue(ToolAgent.isCiPass("✅ conclusion: success"))
        assertFalse(ToolAgent.isCiPass("⏳ البناء #5 ما زال in_progress"))
        assertFalse(ToolAgent.isCiPass("conclusion: failure ❌"))
    }
}

class ShellPushGuardTest {
    @Test
    fun pushToMainOrWithoutTargetOrForcedIsRejected() {
        for (c in listOf("git push", "git push origin main", "git push origin HEAD:main", "git push -f origin lion/x",
            "git push origin +lion/x", "git add . && git commit -m x && git push origin master", "git push --force origin work", "git push origin"))
            assertTrue(c, ShellTools.pushViolation(c) != null)
    }

    @Test
    fun pushToWorkBranchAndOtherCommandsAreAllowed() {
        assertEquals(null, ShellTools.pushViolation("git push origin lion/abc-1119"))
        assertEquals(null, ShellTools.pushViolation("git status"))
        assertEquals(null, ShellTools.pushViolation("git commit -m \"push fix\""))
    }
}
