package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {
    private fun convo(n: Int): List<ChatTurn> = (1..n).map {
        if (it % 2 == 1) ChatTurn("user", "اطلب $it عدّل الملف src/File$it.kt") else ChatTurn("assistant", "تم تنفيذ $it بنجاح. التفاصيل الباقية طويلة.")
    }

    @Test
    fun thinkingIsPrunedIncludingDanglingTag() {
        assertEquals("الجواب", ContextBuilder.pruneThinking("<think>تفكير طويل</think>الجواب"))
        assertEquals("الجواب", ContextBuilder.pruneThinking("الجواب<think>لم يُغلق"))
    }

    @Test
    fun shortConversationIsSentWholeWithoutSummary() {
        val built = ContextBuilder.build(convo(8), null)
        assertNull(built.state)
        assertEquals(8, built.turns.size)
    }

    @Test
    fun longConversationUsesSummaryAndBoundedWindow() {
        val built = ContextBuilder.build(convo(40), null)
        assertNotNull(built.state)
        assertEquals(30, built.state!!.covered)
        assertTrue(built.turns.first().content.startsWith("<summary>"))
        assertTrue(built.turns.size <= ContextBuilder.WINDOW_TURNS + ContextBuilder.SUMMARY_STEP)
        assertTrue(built.state!!.text.length <= ContextBuilder.SUMMARY_MAX_CHARS)
        assertTrue(built.state!!.text.contains("src/File29.kt"))
    }

    @Test
    fun summaryStaysIdenticalBetweenBlockUpdates() {
        val first = ContextBuilder.build(convo(41), null).state!!
        assertEquals(35, first.covered)
        val next = ContextBuilder.build(convo(45), first).state!!
        assertEquals(first.covered, next.covered)
        assertEquals(first.text, next.text)
        val later = ContextBuilder.build(convo(46), next).state!!
        assertEquals(40, later.covered)
        assertTrue(later.text.length <= ContextBuilder.SUMMARY_MAX_CHARS)
    }

    @Test
    fun staleStateFromShorterChatIsRebuilt() {
        val stale = ContextBuilder.State(90, "- طلب: قديم")
        val built = ContextBuilder.build(convo(20), stale)
        assertEquals(10, built.state!!.covered)
        assertFalse(built.state!!.text.contains("قديم"))
    }

    @Test
    fun stateRoundTripsThroughJson() {
        val s = ContextBuilder.State(15, "- طلب: س")
        val back = ContextBuilder.State.parse(s.toJson())!!
        assertEquals(15, back.covered)
        assertEquals("- طلب: س", back.text)
        assertNull(ContextBuilder.State.parse("not json"))
    }

    @Test
    fun roleAlternationIsKeptWhenSummaryIsInjected() {
        val turns = ContextBuilder.build(convo(41), null).turns
        for (i in 1 until turns.size) assertTrue(turns[i].role != turns[i - 1].role)
    }

    @Test
    fun sliceLinesReturnsOnlyRequestedRange() {
        val text = (1..300).joinToString("\n") { "line$it" }
        val out = ContextBuilder.sliceLines("f.kt", text, 50, 52)!!
        assertTrue(out.contains("50: line50") && out.contains("52: line52"))
        assertFalse(out.contains("53: line53"))
        assertTrue(out.contains("من 300"))
        assertNull(ContextBuilder.sliceLines("f.kt", text, 0, 0))
        assertTrue(ContextBuilder.sliceLines("f.kt", text, 999, 0)!!.contains("300"))
    }
}

class ReadPagingTest {
    private val big = (1..3000).joinToString("\n") { "line $it " + "x".repeat(40) }

    @Test
    fun largeFileIsPagedOnLineBoundariesWithExplicitContinuation() {
        val out = ContextBuilder.pageLines("F.kt", big, 0, 0, 15_000, "file_read path=F.kt")
        val shown = Regex("الأسطر 1–(\\d+) من 3000").find(out)!!.groupValues[1].toInt()
        assertTrue(shown in 100..400)
        assertTrue(out.contains("start_line=${shown + 1}"))
        assertTrue(out.contains("$shown: line $shown "))
        assertTrue(!out.contains("${shown + 1}: line"))
    }

    @Test
    fun hugeRequestedRangeNeverClaimsMoreThanWasShown() {
        val out = ContextBuilder.pageLines("F.kt", big, 1, 3000, 15_000, "file_read path=F.kt")
        assertTrue(out.contains("لم تُعرض كل الأسطر المطلوبة"))
        assertTrue(!out.contains("الأسطر 1–3000"))
    }

    @Test
    fun continuingFromLastShownLineReachesEndOfFile() {
        var start = 0
        var guard = 0
        var last = ""
        while (guard++ < 50) {
            last = ContextBuilder.pageLines("F.kt", big, start, 0, 15_000, "file_read path=F.kt")
            val next = Regex("start_line=(\\d+)").find(last)?.groupValues?.get(1)?.toInt() ?: break
            start = next
        }
        assertTrue(last.contains("[نهاية الملف]") && last.contains("3000: line 3000"))
    }

    @Test
    fun smallFileReturnsEverythingAndSaysEnd() {
        val out = ContextBuilder.pageLines("S.kt", "a\nb\nc", 0, 0, 15_000, "file_read path=S.kt")
        assertTrue(out.contains("1: a") && out.contains("3: c") && out.contains("[نهاية الملف]"))
    }
}
