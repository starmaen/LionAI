package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlanSearchToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun plan(vararg pairs: Pair<String, String>): JSONObject {
        val arr = JSONArray()
        pairs.forEach { arr.put(JSONObject().put("step", it.first).put("status", it.second)) }
        return JSONObject().put("plan", arr)
    }

    @Test
    fun planAcceptsSingleInProgress() {
        val (steps, err) = PlanTool.parse(plan("a" to "completed", "b" to "in_progress", "c" to "pending"))
        assertNull(err)
        assertEquals(3, steps.size)
        assertEquals(2, PlanTool.openCount(steps))
        assertTrue(PlanTool.render(steps).contains("▶️ 2. b"))
    }

    @Test
    fun planRejectsTwoInProgressBadStatusAndEmpty() {
        assertNotNull(PlanTool.parse(plan("a" to "in_progress", "b" to "in_progress")).second)
        assertNotNull(PlanTool.parse(plan("a" to "doing")).second)
        assertNotNull(PlanTool.parse(plan("" to "pending")).second)
        assertNotNull(PlanTool.parse(JSONObject()).second)
        assertNotNull(PlanTool.parse(JSONObject().put("plan", JSONArray())).second)
    }

    @Test
    fun planPersistsPerChat() {
        val prefs = FakePrefs()
        val (steps, _) = PlanTool.parse(plan("a" to "completed", "b" to "pending"))
        PlanTool.save(prefs, "chat1", steps)
        assertEquals(steps, PlanTool.read(prefs, "chat1"))
        assertTrue(PlanTool.read(prefs, "chat2").isEmpty())
    }

    @Test
    fun globMatchesLikeStandardTools() {
        val root = tmp.newFolder("ws")
        java.io.File(root, "app/src").mkdirs()
        java.io.File(root, "app/src/Main.kt").writeText("x")
        java.io.File(root, "Top.kt").writeText("x")
        java.io.File(root, "app/src/a.java").writeText("x")
        java.io.File(root, "build").mkdirs()
        java.io.File(root, "build/Gen.kt").writeText("x")
        assertEquals(listOf("Top.kt", "app/src/Main.kt"), SearchTools.glob(root, root, "**/*.kt", 50))
        assertEquals(listOf("Top.kt", "app/src/Main.kt"), SearchTools.glob(root, root, "*.kt", 50))
        assertEquals(listOf("app/src/Main.kt", "app/src/a.java"), SearchTools.glob(root, root, "app/src/*.{kt,java}", 50))
        assertFalse(SearchTools.glob(root, root, "**/*", 50).any { it.startsWith("build/") })
    }

    @Test
    fun grepReturnsContextAndRespectsGlobAndLimit() {
        val root = tmp.newFolder("ws2")
        java.io.File(root, "A.kt").writeText("line1\nfun main() {\n  println(1)\n}\nline5")
        java.io.File(root, "B.txt").writeText("fun main elsewhere")
        val hits = SearchTools.grep(root, root, "fun\\s+main", glob = "*.kt", context = 1)
        assertEquals(listOf(1, 2, 3), hits.map { it.line })
        assertTrue(hits.single { it.isMatch }.line == 2)
        assertTrue(hits.all { it.path == "A.kt" })
        val rendered = SearchTools.renderGrep(hits)
        assertTrue(rendered.contains("A.kt:2: fun main()"))
        assertEquals("لا نتائج.", SearchTools.renderGrep(SearchTools.grep(root, root, "zzz")))
        assertEquals(1, SearchTools.grep(root, root, "fun", max = 1).count { it.isMatch })
    }
}
