package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardGatesTest {
    private fun a(path: String) = JSONObject().put("path", path)

    @Test
    fun editBeforeReadIsBlocked() {
        val g = HardGateState()
        assertNotNull(g.check("file_edit", a("app/Main.kt")))
        assertNotNull(g.check("file_apply_patch", a("app/Main.kt")))
        assertNotNull(g.check("code_patch", a("app/Main.kt")))
    }

    @Test
    fun readThenEditIsAllowedWithPathNormalization() {
        val g = HardGateState()
        g.observe("file_read", a("./app/Main.kt"), true, "1: x")
        assertNull(g.check("file_edit", a("app/Main.kt")))
        assertNotNull(g.check("file_edit", a("app/Other.kt")))
    }

    @Test
    fun failedReadDoesNotUnlockEdit() {
        val g = HardGateState()
        g.observe("file_read", a("A.kt"), false, "❌ not found")
        assertNotNull(g.check("file_edit", a("A.kt")))
    }

    @Test
    fun readManyUnlocksAllPaths() {
        val g = HardGateState()
        g.observe("file_read_many", JSONObject().put("paths", JSONArray(listOf("a.kt", "b.kt"))), true, "ok")
        assertNull(g.check("file_edit", a("a.kt")))
        assertNull(g.check("file_edit", a("b.kt")))
    }

    @Test
    fun fileCreatedByAgentCanBeEditedWithoutReread() {
        val g = HardGateState()
        assertNull(g.check("file_write", a("New.kt"))) // كتابة ملف جديد غير مقيّدة
        g.observe("file_write", a("New.kt"), true, "ok")
        assertNull(g.check("file_edit", a("New.kt")))
    }

    @Test
    fun pushBlockedAfterUnverifiedCodeEdit() {
        val g = HardGateState()
        g.observe("file_read", a("A.kt"), true, "x")
        g.observe("file_edit", a("A.kt"), true, "ok")
        assertNotNull(g.check("github_push_workspace", JSONObject()))
        assertNotNull(g.check("github_commit_files", JSONObject()))
        // dry_run مسموح دائماً
        assertNull(g.check("github_push_workspace", JSONObject().put("dry_run", true)))
    }

    @Test
    fun pushAllowedAfterPassingVerification() {
        val g = HardGateState()
        g.observe("file_write", a("A.kt"), true, "ok")
        g.observe("code_doctor", JSONObject(), true, "تقرير جاهزية المشروع: ممتاز")
        assertNull(g.check("github_push_workspace", JSONObject()))
        assertTrue(g.gaps().isEmpty())
    }

    @Test
    fun failedGateKeepsPushBlockedUntilItPasses() {
        val g = HardGateState()
        g.observe("file_write", a("A.kt"), true, "ok")
        g.observe("gate_run", JSONObject(), true, "⛔ فشلت البوابة — ملفات: 3")
        assertNotNull(g.check("github_push_workspace", JSONObject()))
        assertTrue(g.gaps().isNotEmpty())
        g.observe("gate_run", JSONObject(), true, "✅ اجتازت البوابة — ملفات: 3")
        assertNull(g.check("github_push_workspace", JSONObject()))
    }

    @Test
    fun editAfterPassingVerificationDirtiesAgain() {
        val g = HardGateState()
        g.observe("file_write", a("A.kt"), true, "ok")
        g.observe("gate_run", JSONObject(), true, "✅ اجتازت البوابة")
        g.observe("file_write", a("B.kt"), true, "ok")
        assertNotNull(g.check("github_push_workspace", JSONObject()))
    }

    @Test
    fun docsOnlyEditsDoNotRequireVerification() {
        val g = HardGateState()
        g.observe("file_write", a("README.md"), true, "ok")
        assertNull(g.check("github_push_workspace", JSONObject()))
    }

    @Test
    fun retryCapIsThree() {
        assertEquals(3, HardGateState.MAX_SAME_FAILURE)
    }

    @Test
    fun diagnosisHasFourFields() {
        val d = HardGateState.diagnosis(listOf("a", "b"), "فشل", "سبب", "خطوة")
        assertTrue(d.contains("ما جُرّب") && d.contains("النتيجة") && d.contains("السبب") && d.contains("الخطوة التالية"))
    }

    @Test
    fun quickSyntaxFindsUnbalancedBraces() {
        assertNull(QuickSyntax.balanceProblem("A.kt", "fun a() { val s = \"}\"; // }\n  if (x) { }\n}"))
        assertNotNull(QuickSyntax.balanceProblem("A.kt", "fun a() {\n  if (x) {\n}"))
        assertNotNull(QuickSyntax.balanceProblem("A.kt", "fun a() { ) }"))
        assertNull(QuickSyntax.balanceProblem("A.kt", "val c = '}'\nval r = \"\"\"{ ( \"\"\"\nfun f() {}"))
        assertNull(QuickSyntax.balanceProblem("a.json", "{\"k\":[1,2,{\"x\":\"}\"}]}"))
        assertNull(QuickSyntax.balanceProblem("notes.txt", "{{{"))
        assertFalse(QuickSyntax.supports("README.md"))
    }
}
