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

    @Test
    fun leanSetCarriesEveryToolTheLoopInstructionsNameAndAllOfThemExist() {
        val lean = ToolAgent.LEAN_TOOLS
        listOf("github_merge_pr", "github_dispatch", "github_get_run", "github_delete_branch", "code_doctor", "file_delete")
            .forEach { assertTrue("$it missing", it in lean) }
        val spec = AiTools.spec(ToolGroup.values().toSet(), FakePrefs(), lean)
        lean.forEach { assertTrue("$it has no definition", spec.contains(it)) }
    }

    @Test
    fun generalChatWithoutKeywordsStillHasMetaToolsOnDemand() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean("agent_tools", true).putBoolean("tools_everywhere", true).apply()
        val profile = ContextProfiles.of(ChatMode.GENERAL, prefs, "كيف حالك اليوم")
        assertTrue(profile.allowTools)
        assertTrue(profile.groups.contains(ToolGroup.META))
    }
}
