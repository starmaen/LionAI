package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBudgetTest {
    @Test
    fun picksFirstTierThatFitsBudget() {
        val out = ToolAgent.fitSystem(10, { "x".repeat(30) }, { "y".repeat(8) }, { "z" })
        assertEquals("y".repeat(8), out)
    }

    @Test
    fun fallsBackToSmallestWhenNothingFits() {
        val out = ToolAgent.fitSystem(5, { "x".repeat(30) }, { "y".repeat(20) })
        assertEquals("y".repeat(20), out)
    }

    @Test
    fun leanSetKeepsTheEditBuildLoopAndDiscovery() {
        val lean = ToolAgent.LEAN_TOOLS
        listOf("tools_schema", "file_read", "file_edit", "file_write", "android_build", "github_wait_run")
            .forEach { assertTrue("$it missing", it in lean) }
    }
}
