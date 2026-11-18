package com.lion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAccessTest {
    @Test
    fun detectsProvidersThatDenyHavingTools() {
        listOf(
            "عذراً، لا أستطيع الوصول إلى الأدوات أو المستودع من هنا.",
            "I don't have access to tools or your repository.",
            "كنموذج ذكاء اصطناعي لا يمكنني تصفح الإنترنت أو تنفيذ الملفات."
        ).forEach { assertTrue(it, ToolAgent.isCapabilityDenial(it)) }
    }

    @Test
    fun ignoresNormalAnswersAndToolCalls() {
        assertFalse(ToolAgent.isCapabilityDenial("هذه الدالة تعيد قائمة الملفات المحدثة."))
        assertFalse(ToolAgent.isCapabilityDenial("[[TOOL]]{\"name\":\"file_list\"}[[/TOOL]] لا أستطيع الوصول"))
        assertFalse(ToolAgent.isCapabilityDenial("x".repeat(1_000) + " I cannot access tools"))
    }

    @Test
    fun heavyCommandsGetLongTimeouts() {
        listOf("./gradlew assembleDebug", "git clone https://github.com/a/b", "npm ci", "pip install requests", "pkg install git")
            .forEach { assertTrue(it, ShellTools.isHeavy(it)) }
        listOf("ls -la", "cat README.md", "git status", "grep -rn foo .")
            .forEach { assertFalse(it, ShellTools.isHeavy(it)) }
    }
}
