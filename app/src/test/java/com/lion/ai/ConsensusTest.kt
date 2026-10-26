package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsensusTest {

    @Test
    fun identicalAnswersAgreeCompletely() {
        val text = "صدر الفيلم عام 1987 وهو من بطولة عادل إمام."
        assertEquals(100, Consensus.compare(text, text))
    }

    @Test
    fun unrelatedAnswersDisagree() {
        val a = "عاصمة سوريا هي دمشق."
        val b = "اللغة الكوتلينية تعمل على أندرويد."
        assertTrue(Consensus.compare(a, b) < 40)
    }

    @Test
    fun shortContainedAnswerScoresHigh() {
        val a = "دمشق"
        val b = "عاصمة سوريا هي دمشق"
        assertTrue(Consensus.compare(a, b) >= 70)
    }

    @Test
    fun stopWordsAreIgnored() {
        assertTrue(Consensus.tokens("من هو في على").isEmpty())
    }

    @Test
    fun labelReflectsScore() {
        assertEquals("اتفاق قوي", Consensus.label(80))
        assertEquals("اتفاق جزئي", Consensus.label(50))
        assertEquals("اختلاف", Consensus.label(20))
    }
}
