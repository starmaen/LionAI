package com.lion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerGuardTest {

    @Test
    fun factualTableIsLowQuality() {
        val table = "| اسم الفيلم | سنة الإصدار |\n|---|---|\n| المدرسة | 1979 |"
        assertTrue(AnswerGuard.isLowQuality(table, TaskKind.FACTUAL, "أفلام عادل إمام"))
    }

    @Test
    fun cleanFactualListIsAccepted() {
        val text = "أشهر أفلام عادل إمام:\n" +
            "• البيه البواب (1987)\n" +
            "• الإرهاب والكباب (1992)\n" +
            "• النوم في العسل (1996)"
        assertFalse(AnswerGuard.isLowQuality(text, TaskKind.FACTUAL, "أفلام عادل إمام"))
    }

    @Test
    fun futureDateIsLowQuality() {
        assertTrue(AnswerGuard.isLowQuality("صدر هذا الفيلم سنة 2050.", TaskKind.FACTUAL, "فيلم"))
    }

    @Test
    fun uncertaintyIsAccepted() {
        val text = "لست متأكداً من سنة الفيلم؛ أعرف أنه من الثمانينات."
        assertFalse(AnswerGuard.isLowQuality(text, TaskKind.FACTUAL, "فيلم"))
    }

    @Test
    fun generalTableIsAllowed() {
        val table = "| أ | ب |\n|---|---|\n| 1 | 2 |"
        assertFalse(AnswerGuard.isLowQuality(table, TaskKind.GENERAL, "قارن بينهما"))
    }

    @Test
    fun gibberishIsLowQuality() {
        assertTrue(AnswerGuard.isLowQuality("!!!???###$$$%%%%^^^^&&&&****++++====", TaskKind.GENERAL, "سؤال"))
    }

    @Test
    fun repeatedLoopIsLowQuality() {
        val text = "الفيلم جميل جدا والفيلم جميل جدا والفيلم جميل جدا والفيلم جميل جدا"
        assertTrue(AnswerGuard.isLowQuality(text, TaskKind.FACTUAL, "فيلم"))
    }

    @Test
    fun sanitizerConvertsTableToBullets() {
        val raw = "**أفلام**\n\n| الاسم | السنة |\n|---|---|\n| البيه البواب | 1987 |\n| الإرهاب والكباب | 1992 |"
        val out = AnswerGuard.sanitizeForDisplay(raw)
        assertFalse(out.contains("|"))
        assertFalse(out.contains("**"))
        assertTrue(out.contains("• البيه البواب — 1987"))
        assertTrue(out.contains("• الإرهاب والكباب — 1992"))
    }
}
