package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildDiagnoseTest {
    private val ciLog = listOf(
        "2026-10-31T10:00:00.0000000Z > Task :app:compileDebugKotlin FAILED",
        "2026-10-31T10:00:01.0000000Z e: file:///home/runner/work/LionAI/LionAI/app/src/main/java/com/lion/ai/Foo.kt:(12, 5): Unresolved reference: bar",
        "2026-10-31T10:00:02.0000000Z FAILURE: Build failed with an exception.",
        "2026-10-31T10:00:02.0000000Z * What went wrong:",
        "2026-10-31T10:00:02.0000000Z Execution failed for task ':app:compileDebugKotlin'.",
        "2026-10-31T10:00:02.0000000Z > Compilation error. See log for more details"
    ).joinToString("\n")

    @Test
    fun cleanRemovesTimestamps() {
        assertTrue(BuildDiagnose.clean(ciLog).startsWith("> Task :app:compileDebugKotlin FAILED"))
    }

    @Test
    fun parsesKotlinErrorFromCiLog() {
        val report = BuildDiagnose.analyze(ciLog)
        assertEquals(":app:compileDebugKotlin", report.failedTask)
        assertTrue(report.cause.contains("Compilation error"))
        assertEquals("app/src/main/java/com/lion/ai/Foo.kt:12 — Unresolved reference: bar", report.errors.first())
    }

    @Test
    fun parsesFailedTests() {
        val log = "com.lion.ai.FooTest > bar FAILED\n    java.lang.AssertionError at FooTest.kt:10"
        val report = BuildDiagnose.analyze(log)
        assertEquals(listOf("com.lion.ai.FooTest > bar FAILED"), report.failedTests)
    }
}
