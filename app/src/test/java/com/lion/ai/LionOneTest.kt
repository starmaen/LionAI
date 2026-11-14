package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات توجيه مهام Lion-1 (دوال نقية بلا شبكة ولا أندرويد).
 * الهدف: التأكد أن اسم الموديل الصريح يتغلب على تخمين النص، وأن التخمين يختار
 * المهمة الصحيحة، وأن الهوية تُحقن دائماً دون كسر تعليمات الوكيل.
 */
class LionOneTest {

    @Test
    fun explicitModelNameOverridesTextGuess() {
        // نص يبدو برمجياً، لكن المستخدم اختار موديل الترجمة صراحةً.
        assertEquals(LionOne.Task.TRANSLATE, LionOne.task("lion-1-translate", "أصلح هذا الكود"))
        assertEquals(LionOne.Task.SUMMARY, LionOne.task("lion-1-summary", "اكتب مقالاً"))
        assertEquals(LionOne.Task.GENERAL, LionOne.task("lion-1", "مرحباً"))
    }

    @Test
    fun textGuessPicksExpectedTask() {
        assertEquals(LionOne.Task.CODE, LionOne.task("lion-1", "لماذا يفشل gradle في البناء؟"))
        assertEquals(LionOne.Task.TEACH, LionOne.task("lion-1", "اشرح لي الكوروتينات"))
        assertEquals(LionOne.Task.TRANSLATE, LionOne.task("lion-1", "ترجم هذه الفقرة"))
        assertEquals(LionOne.Task.SUMMARY, LionOne.task("lion-1", "لخص لي هذا الملف"))
        assertEquals(LionOne.Task.WRITE, LionOne.task("lion-1", "اكتب رسالة لمديري"))
        assertEquals(LionOne.Task.REASON, LionOne.task("lion-1", "حلل هذه النتائج"))
        assertEquals(LionOne.Task.GENERAL, LionOne.task("lion-1", "كيف حالك؟"))
    }

    @Test
    fun identityIsAlwaysInjected() {
        val base = "تعليمات الوكيل"
        val system = LionOne.systemFor(base, LionOne.Task.CODE)
        assertTrue(system.contains("Lion-1"))
        assertTrue(system.contains(base))
        // وصف المهمة لا يُضاف إلا للنظام العام، حتى لا نكسر بروتوكول الأدوات.
        assertFalse(system.contains(LionOne.Task.CODE.prompt))
    }

    @Test
    fun generalPromptGetsTaskDescription() {
        val system = LionOne.systemFor(LLM_SYSTEM_PROMPT, LionOne.Task.GENERAL)
        assertTrue(system.contains(LionOne.Task.GENERAL.prompt))
    }

    @Test
    fun modelListExposesEveryTask() {
        assertTrue(LionOne.MODELS.contains("lion-1"))
        LionOne.Task.values().forEach { task ->
            assertTrue(LionOne.MODELS.contains("lion-1-" + task.name.lowercase()))
        }
    }
}
