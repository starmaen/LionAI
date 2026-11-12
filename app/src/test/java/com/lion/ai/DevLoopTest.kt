package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevLoopTest {
    @Test fun toolLoopIsNotCappedAtSixSteps() {
        assertEquals(40, ToolAgent.stepBudget(ChatMode.CODE, false))
        assertEquals(40, ToolAgent.stepBudget(ChatMode.GENERAL, true))
        assertTrue(ToolAgent.stepBudget(ChatMode.GENERAL, false) > 6)
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
    @Test fun codeProfileCarriesBuildFixLoopAndReportFormat() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean("agent_tools", true).putBoolean("tools_everywhere", true).apply()
        val suffix = ContextProfiles.of(ChatMode.CODE, prefs, "أصلح Main.kt").promptSuffix
        assertTrue(suffix.contains("أول سطر خطأ"))
        assertTrue(suffix.contains("المراجعة"))
        assertTrue(suffix.contains("ما لم تتحقق منه"))
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

    @Test fun simpleQuestionsUseFastDirectResponseButCommandsStillUseTools() {
        assertFalse(shouldUseAgentForPrompt("هل تستطيع قراءة ملف؟", false))
        assertFalse(shouldUseAgentForPrompt("اشرح ما هي غرفة العمليات", false))
        assertTrue(shouldUseAgentForPrompt("اقرأ الملف src/Main.kt", false))
        assertTrue(shouldUseAgentForPrompt("أصلح الخطأ في المشروع", false))
        assertTrue(shouldUseAgentForPrompt("هل تستطيع قراءة ملف؟", true))
    }

    @Test fun codeModeForcesExecutionFromTheFirstMessage() {
        assertTrue(shouldForceExecution(ChatMode.CODE, TaskKind.FAST, false))
        assertTrue(shouldForceExecution(ChatMode.GENERAL, TaskKind.DEV, false))
        assertFalse(shouldForceExecution(ChatMode.GENERAL, TaskKind.FAST, false))
    }

}

class PromiseWithoutActionTest {
    @org.junit.Test fun detectsPromiseOnlyReplies() {
        org.junit.Assert.assertTrue(ToolAgent.isIntentOnly("حاضر، سأعمل وحدة ماجيسك لترجمة يوتيوب وأخبرك بالنتيجة."))
        org.junit.Assert.assertTrue(ToolAgent.isIntentOnly("سأكمل المهمة الآن."))
        org.junit.Assert.assertTrue(ToolAgent.isIntentOnly("Sure, I'll start building the module now."))
    }

    @org.junit.Test fun acceptsRealResultsQuestionsAndToolCalls() {
        org.junit.Assert.assertFalse(ToolAgent.isIntentOnly("✅ اكتمل البناء #12 ونجح، وهذا رابط التنزيل."))
        org.junit.Assert.assertFalse(ToolAgent.isIntentOnly("هل تريد أن أبدأ بالوحدة أم بالواجهة؟"))
        org.junit.Assert.assertFalse(ToolAgent.isIntentOnly("[[TOOL]]{\"name\":\"file_tree\",\"args\":{}}[[/TOOL]]"))
        org.junit.Assert.assertFalse(ToolAgent.isIntentOnly("```kotlin\nfun main() {}\n```\nسأضيف الباقي لاحقاً"))
        org.junit.Assert.assertFalse(ToolAgent.isIntentOnly("x".repeat(900) + " سأكمل"))
    }
}

class DevTaskTest {
    @org.junit.Test fun detectsBuildRequestsButNotPlainQuestions() {
        org.junit.Assert.assertTrue(DevTask.matches("اعمل وحدة ماجيسك لترجمة اليوتيوب"))
        org.junit.Assert.assertTrue(DevTask.matches("أضف ميزة البحث إلى التطبيق"))
        org.junit.Assert.assertTrue(DevTask.matches("build a kotlin script"))
        org.junit.Assert.assertFalse(DevTask.matches("ما هي عاصمة فرنسا؟"))
        org.junit.Assert.assertFalse(DevTask.matches("اكمل"))
    }
    @Test fun developerModeUsesToolsOnDemandForProjectQuestions() {
        // سؤال عن مشروع/خطأ فعلي في «المطوّر» يقرأ الواقع بالأدوات قبل أن يجيب.
        assertTrue(shouldUseAgentForPrompt("لماذا فشل البناء؟", false, ChatMode.CODE))
        assertTrue(shouldUseAgentForPrompt("ما سبب الخطأ في LionApp.kt؟", false, ChatMode.CODE))
        assertTrue(shouldUseAgentForPrompt("كيف أصلح الاختبار في app/src/test؟", false, ChatMode.CODE))
        // أسئلة المفاهيم والقدرة تبقى سريعة بلا أدوات.
        assertFalse(shouldUseAgentForPrompt("اشرح ما هي غرفة العمليات", false, ChatMode.CODE))
        assertFalse(shouldUseAgentForPrompt("هل تستطيع قراءة ملف؟", false, ChatMode.CODE))
        // الوضع العام لا يتغير.
        assertFalse(shouldUseAgentForPrompt("لماذا فشل البناء؟", false, ChatMode.GENERAL))
    }

    @Test fun developerModeForcesToolsOnlyForActionsNotForQuestions() {
        assertTrue(shouldForceExecution(ChatMode.CODE, TaskKind.FAST, false, "أصلح الخطأ في المشروع"))
        assertFalse(shouldForceExecution(ChatMode.CODE, TaskKind.FAST, false, "لماذا فشل البناء؟"))
        assertTrue(shouldForceExecution(ChatMode.CODE, TaskKind.FAST, true, "لماذا فشل البناء؟"))
        assertTrue(shouldForceExecution(ChatMode.CODE, TaskKind.DEV, false, "ما الوضع؟"))
        assertFalse(shouldForceExecution(ChatMode.GENERAL, TaskKind.FAST, false, "أصلح الخطأ"))
    }

    @Test fun simpleQuestionsAndChatNeverOpenTheExecutionPath() {
        listOf(
            "ما هي عاصمة فرنسا", "هل هذا الملف pdf؟", "ماذا تعني كلمة تعويذة", "هل كلمة معلومات صحيحة",
            "أيمكن ضغط ملف pdf", "شكرا لك", "ما هو امتداد ملف الصور"
        ).forEach {
            assertFalse(it, shouldUseAgentForPrompt(it, false, ChatMode.GENERAL))
            assertFalse(it, shouldUseAgentForPrompt(it, false, ChatMode.CODE))
        }
        listOf("اضغط المجلد إلى zip", "أنشئ ملف notes.txt", "اعمل لي تطبيق ملاحظات", "اقرأ الملف Main.kt").forEach {
            assertTrue(it, shouldUseAgentForPrompt(it, false, ChatMode.GENERAL))
        }
    }


    @Test fun generalChatOpensToolsForRepoAndFileReferences() {
        assertTrue(shouldUseAgentForPrompt("ما محتوى README.md في المستودع؟", false, ChatMode.GENERAL))
        assertTrue(shouldUseAgentForPrompt("ماذا يوجد في مستودعي starmaen/LionAI على github", false, ChatMode.GENERAL))
        assertFalse(shouldUseAgentForPrompt("ما هي عاصمة فرنسا", false, ChatMode.GENERAL))
        assertFalse(shouldUseAgentForPrompt("هل بإمكانك قراءة ملف؟", false, ChatMode.GENERAL))
    }
}
