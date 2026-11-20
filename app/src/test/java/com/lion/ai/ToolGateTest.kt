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

/** الجولة 7: «لا عملية بناء» ليست فشلاً، والفشل الأقدم من الدفع فشل قديم لا يحظر الختام. */
class StaleBuildGateTest {

    private val noRun = "❌ لم أجد عملية بناء حديثة على الفرع bench/smoke بعد الدفع. تذكّر: build.yml على الفرع الأساسي وطلبات السحب فقط."
    private val freshFail = "📛 Build failure · id=1 · commit=abc1234 · 2026-11-20T14:00:00Z"
    private val oldFail = "📛 Build failure · id=608 · commit=old0000 · 2026-11-19T10:00:00Z"

    @Test fun noRunFoundIsNotABuildFailure() {
        assertTrue(ToolAgent.noRunFound(noRun))
        assertFalse(ToolAgent.noRunFound(freshFail))
        // المفتاح: «لم أجد» لا يفعّل بوابة البناء الفاشل (كانت توقف مهام ناجحة زائفاً)
        assertTrue(ToolAgent.buildFailed(noRun))         // نصياً يحمل ❌
        assertTrue(ToolAgent.noRunFound(noRun))          // لكنه يُستثنى قبل البوابة
    }

    @Test fun runCreatedAtParsesIsoFromOutput() {
        val t = ToolAgent.runCreatedAt(freshFail)
        assertNotNull(t)
        assertTrue(t!! > 0L)
        assertNull(ToolAgent.runCreatedAt("لا يوجد وقت هنا"))
    }

    @Test fun staleFailureIsOlderThanLastPush() {
        // دفعنا بعد فشل freshFail بساعة: freshFail يصبح قديماً بالنسبة للدفع الجديد
        val pushAt = ToolAgent.runCreatedAt(freshFail)!! + 3_599_000L
        val old = ToolAgent.runCreatedAt(oldFail)!!
        assertTrue("فشل الأمس أقدم من الدفع", old < pushAt)
        val stale = old < pushAt && ToolAgent.buildFailed(oldFail)
        assertTrue("فشل قديم على فرع معاد استخدامه لا يُفعّل البوابة", stale)
        // فشل حقيقي بعد الدفع يبقى مفعلًا للبوابة
        val afterPush = "📛 Build failure · id=2 · commit=new0001 · 2026-11-20T15:00:00Z"
        val rt = ToolAgent.runCreatedAt(afterPush)!!
        assertTrue(rt > pushAt)
        assertTrue(!((rt < pushAt) && ToolAgent.buildFailed(afterPush)))
        assertTrue(ToolAgent.buildFailed(afterPush))
    }
}
