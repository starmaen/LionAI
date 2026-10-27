package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات وحدة للعقل المركزي والتطبيع النصي — تعمل على JVM دون أندرويد.
 */
class SmartRouterTest {

    // ——— SmartRouter.classify ———

    @Test
    fun shortQuestionIsFast() {
        assertEquals(TaskKind.FAST, SmartRouter.classify("قل مرحبا", false, 0, null))
    }

    @Test
    fun greetingsNeverNeedDeepThinking() {
        listOf("مرحبا", "كيف حالك؟", "السلام عليكم", "شكراً على المساعدة", "شكرا على الكود").forEach {
            assertEquals("«$it» يجب أن تكون سريعة", TaskKind.FAST, SmartRouter.classify(it, false, 0, null))
        }
    }

    @Test
    fun devTaskWithRepoActionIsDev() {
        assertEquals(
            TaskKind.DEV,
            SmartRouter.classify("أصلح فشل البناء في مستودع GitHub ثم ارفع التعديل", false, 0, null)
        )
        assertEquals(
            TaskKind.DEV,
            SmartRouter.classify("راجع هذا المستودع github.com/starmaen/LionAI وطور الكود", false, 0, null)
        )
    }

    @Test
    fun simpleCodeQuestionIsNotDev() {
        assertEquals(TaskKind.CODE, SmartRouter.classify("اكتب لي دالة kotlin لجمع رقمين", false, 0, null))
        assertEquals(TaskKind.CODE, SmartRouter.classify("راجع هذا الملف", false, 500, "Main.kt"))
    }

    @Test
    fun capitalQuestionIsFactual() {
        assertEquals(TaskKind.FACTUAL, SmartRouter.classify("ما عاصمة سوريا؟", false, 0, null))
    }

    @Test
    fun filmQuestionIsFactual() {
        assertEquals(TaskKind.FACTUAL, SmartRouter.classify("أفلام عادل إمام الأكثر شهرة", false, 0, null))
    }

    @Test
    fun factualCandidatesStartWithTrustedProvider() {
        assertEquals("gemini", SmartRouter.candidates(TaskKind.FACTUAL).first().provider)
        assertTrue(SmartRouter.isTrustedFactual("gemini"))
        assertFalse(SmartRouter.isTrustedFactual("llm7"))
    }

    @Test
    fun shortNonSmalltalkQuestionIsGeneral() {
        assertEquals(TaskKind.GENERAL, SmartRouter.classify("أعطني نصيحة للسفر", false, 0, null))
    }

    @Test
    fun weakFastModelsAreNotReliableForFacts() {
        assertTrue(SmartRouter.isReliableModel("gemini", "gemini-3.8-flash"))
        assertTrue(SmartRouter.isReliableModel("groq", "openai/gpt-oss-120b"))
        assertFalse(SmartRouter.isReliableModel("groq", "openai/gpt-oss-20b"))
        assertFalse(SmartRouter.isReliableModel("llm7", "default"))
        assertFalse(SmartRouter.isReliableModel("kilo", "kilo-auto/free"))
    }

    @Test
    fun answerModeFallsBackToBalanced() {
        assertEquals(AnswerMode.BALANCED, AnswerMode.fromId("unknown"))
        assertEquals(AnswerMode.PRECISE, AnswerMode.fromId("precise"))
    }

    @Test
    fun longQuestionIsGeneral() {
        val long = "سؤال طويل جداً ".repeat(40)
        assertEquals(TaskKind.GENERAL, SmartRouter.classify(long, false, 0, null))
    }

    @Test
    fun codeKeywordIsCode() {
        assertEquals(TaskKind.CODE, SmartRouter.classify("اكتب لي دالة بلغة kotlin", false, 0, null))
    }

    @Test
    fun codeFileIsCode() {
        assertEquals(TaskKind.CODE, SmartRouter.classify("راجع هذا الملف", false, 500, "Main.kt"))
    }

    @Test
    fun mathExpressionIsReasoning() {
        assertEquals(TaskKind.REASONING, SmartRouter.classify("احسب 2 + 2", false, 0, null))
    }

    @Test
    fun translateRequestIsWriting() {
        assertEquals(TaskKind.WRITING, SmartRouter.classify("ترجم هذه الفقرة", false, 0, null))
    }

    @Test
    fun imageWithEditVerbIsImageEdit() {
        assertEquals(TaskKind.IMAGE_EDIT, SmartRouter.classify("قص الصورة", true, 0, null))
    }

    @Test
    fun imageWithoutEditVerbIsVision() {
        assertEquals(TaskKind.VISION, SmartRouter.classify("ما في هذه الصورة؟", true, 0, null))
    }

    @Test
    fun longAttachmentIsLongDoc() {
        assertEquals(TaskKind.LONG_DOC, SmartRouter.classify("لخص", false, 15_000, "book.txt"))
    }

    // ——— ImageIntent ———

    @Test
    fun imageIntentDetectsEditVerb() {
        assertTrue(ImageIntent.looksLikeEdit("دور الصورة 90 درجة"))
    }

    @Test
    fun imageIntentIgnoresDescription() {
        assertFalse(ImageIntent.looksLikeEdit("صف لي هذه الصورة"))
    }

    // ——— TextNorm.digits ———

    @Test
    fun digitsConvertsArabicIndicNumerals() {
        assertEquals("0951", TextNorm.digits("٠٩٥١"))
    }

    // ——— RepeatFmt.label ———

    @Test
    fun repeatFmtDailyLabel() {
        assertEquals("كل يوم", RepeatFmt.label(86_400_000L))
    }

    @Test
    fun repeatFmtZeroIsEmpty() {
        assertEquals("", RepeatFmt.label(0L))
    }
}
