package com.lion.ai

import org.junit.Assert.*
import org.junit.Test

/** اختبارات إصلاحات تجارب المزودين: بوابة الفحص، تلميح git 128، وقاية الدفع الكبير. */
class ToolGateTest {

    @Test fun codeGateAppliesOnlyToRealCodeFiles() {
        assertTrue(ToolAgent.codePathTouched("app/src/main/java/com/lion/ai/AiTools.kt"))
        assertTrue(ToolAgent.codePathTouched("app/build.gradle.kts"))
        assertTrue(ToolAgent.codePathTouched("res/layout/main.xml"))
        assertTrue(ToolAgent.codePathTouched("script.py"))
        // مستندات وإعدادات لا تطلب فحص كود
        assertFalse(ToolAgent.codePathTouched("docs/smoke-test.md"))
        assertFalse(ToolAgent.codePathTouched("notes.txt"))
        assertFalse(ToolAgent.codePathTouched("README"))
        assertFalse(ToolAgent.codePathTouched("repos/starmaen/LionAI"))
    }

    @Test fun pushOutputYieldsCodePathsNotDocs() {
        val out = "starmaen/LionAI@bench/smoke\nمتغير/جديد: 2 — محذوف: 0\n" +
            "• ✏️ app/src/main/java/com/lion/ai/AiTools.kt\n• ✏️ docs/smoke-test.md\n"
        val paths = ToolAgent.CODE_PATH_IN_OUTPUT.findAll(out).map { it.value }.toList()
        assertTrue(paths.toString(), paths.any { it.endsWith("AiTools.kt") })
        assertFalse(paths.toString(), paths.any { it.endsWith("smoke-test.md") })
    }

    @Test fun largePushGuardDemandsConfirmation() {
        // دفع عادي: بلا مانع
        assertNull(GitHubClient.largePushGuard(brandNew = 5, codeChanged = 3, confirmLarge = false))
        // ملفات جديدة كثيرة (ملفات دخيلة)
        assertNotNull(GitHubClient.largePushGuard(brandNew = 40, codeChanged = 0, confirmLarge = false))
        // تعديل كود كثيف (نسخة أقدم من الفرع — حالة Numeron: 17 ملفاً بينها AiTools.kt)
        assertNotNull(GitHubClient.largePushGuard(brandNew = 1, codeChanged = 17, confirmLarge = false))
        // الحد الأدنى لا يمنع
        assertNull(GitHubClient.largePushGuard(brandNew = 1, codeChanged = 10, confirmLarge = false))
        // التأكيد الصريح يمرر
        assertNull(GitHubClient.largePushGuard(brandNew = 40, codeChanged = 17, confirmLarge = true))
    }

    @Test fun cdIsAllowedInShell() {
        assertTrue("cd" in ShellTools.ALLOWED)
    }
}
