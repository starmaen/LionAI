package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    @Test fun compressedSystemKeepsTheWholeDevelopmentCore() {
        val input = ToolGroup.values().toSet()
        val core = ToolAgent.coreGroups(input)
        setOf(
            ToolGroup.META, ToolGroup.FILES, ToolGroup.CODE, ToolGroup.GITHUB,
            ToolGroup.SHELL, ToolGroup.QUALITY, ToolGroup.CONTROL, ToolGroup.MEMORY
        ).forEach { assertTrue("missing $it", core.contains(it)) }
    }

    @Test fun askAndCompletionMessagesKeepContinuationNatural() {
        val ask = ToolAgent.formatUserAsk("  هل أتابع؟  ")
        assertTrue(ask.startsWith("هل أتابع؟"))
        assertTrue(ask.contains("أنتظر ردك"))

        val done = ToolAgent.withNextStepInvite("تم التحقق.", true)
        assertTrue(done.contains("أرسل أي رد أو قرار"))
        assertEquals("إجابة فقط", ToolAgent.withNextStepInvite("إجابة فقط", false))
    }

    @Test fun developmentToolsDoNotDependOnProviderOrChannelMode() {
        val automatic = FakePrefs()
        automatic.edit().putBoolean("agent_tools", true).putBoolean("tools_everywhere", true)
            .putBoolean("auto_route", true).apply()
        val manual = FakePrefs()
        manual.edit().putBoolean("agent_tools", true).putBoolean("tools_everywhere", true)
            .putBoolean("auto_route", false).putBoolean("channel_direct", false)
            .putBoolean("channel_local", true).apply()
        val request = "أصلح البناء وابنِ APK داخل مساحة العمل"
        val autoGroups = ContextProfiles.of(ChatMode.GENERAL, automatic, request).groups
        val manualGroups = ContextProfiles.of(ChatMode.GENERAL, manual, request).groups
        assertTrue(autoGroups.containsAll(setOf(ToolGroup.FILES, ToolGroup.SHELL, ToolGroup.CODE)))
        assertEquals(autoGroups, manualGroups)
    }

    @Test fun codeModeForcesExecutionFromTheFirstMessage() {
        assertTrue(shouldForceExecution(ChatMode.CODE, TaskKind.FAST, false))
        assertTrue(shouldForceExecution(ChatMode.GENERAL, TaskKind.DEV, false))
        assertFalse(shouldForceExecution(ChatMode.GENERAL, TaskKind.FAST, false))
    }

}
