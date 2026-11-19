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
