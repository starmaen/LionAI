package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevLoopTest {
    @Test fun toolLoopIsNotCappedAtSixSteps() {
        assertEquals(96, ToolAgent.stepBudget(ChatMode.CODE, false))
        assertEquals(96, ToolAgent.stepBudget(ChatMode.GENERAL, true))
        assertTrue(ToolAgent.stepBudget(ChatMode.GENERAL, false) > 6)
        assertTrue(ToolAgent.stepBudget(ChatMode.LEARN, false) > 6)
    }

    @Test fun codingRequestEnablesShellQualityAndControlTools() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean("agent_tools", true).apply()
        val groups = AiTools.groupsFor("أصلح البناء وابنِ APK", prefs)
        assertTrue(groups.contains(ToolGroup.CODE))
        assertTrue(groups.contains(ToolGroup.SHELL))
        assertTrue(groups.contains(ToolGroup.QUALITY))
        assertTrue(groups.contains(ToolGroup.CONTROL))
        assertTrue(groups.contains(ToolGroup.FILES))
    }

    @Test fun codeProfileKeepsCoreDevToolGroups() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean("agent_tools", true).putBoolean("tools_everywhere", true).apply()
        val profile = ContextProfiles.of(ChatMode.CODE, prefs, "أصلح Main.kt")
        assertTrue(profile.allowTools)
        assertTrue(profile.groups.contains(ToolGroup.FILES))
        assertTrue(profile.groups.contains(ToolGroup.SHELL))
        assertTrue(profile.groups.contains(ToolGroup.QUALITY))
        assertTrue(profile.groups.contains(ToolGroup.CONTROL))
        assertTrue(profile.groups.contains(ToolGroup.MEMORY))
    }
}
