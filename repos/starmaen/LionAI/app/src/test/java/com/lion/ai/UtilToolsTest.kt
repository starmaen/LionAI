package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class UtilToolsTest {

    @Test
    fun nextCronFindsDailyNine() {
        val from = LocalDateTime.of(2026, 10, 26, 8, 30)
        val next = UtilTools.nextCron(listOf("0", "9", "*", "*", "*"), from)
        assertNotNull(next)
        assertTrue(next!!.startsWith("2026-10-26 09:00"))
    }

    @Test
    fun nextCronRollsToNextDay() {
        val from = LocalDateTime.of(2026, 10, 26, 9, 1)
        val next = UtilTools.nextCron(listOf("0", "9", "*", "*", "*"), from)
        assertTrue(next!!.startsWith("2026-10-27 09:00"))
    }

    @Test
    fun nextCronHandlesStepMinutes() {
        val from = LocalDateTime.of(2026, 10, 26, 10, 2)
        val next = UtilTools.nextCron(listOf("*/15", "*", "*", "*", "*"), from)
        assertTrue(next!!.startsWith("2026-10-26 10:15"))
    }

    @Test
    fun nextCronRespectsWeekday() {
        val from = LocalDateTime.of(2026, 10, 26, 0, 0)
        val next = UtilTools.nextCron(listOf("0", "8", "*", "*", "5"), from)
        assertTrue(next!!.startsWith("2026-10-30 08:00"))
    }

    @Test
    fun describeCronMentionsTime() {
        val text = UtilTools.describeCron(listOf("30", "7", "*", "*", "*"))
        assertTrue(text.contains("7:30"))
    }

    @Test
    fun diffShowsInsertAndDelete() {
        val diff = UtilTools.diffText("a\nb", "a\nc")
        assertTrue(diff.contains("- b"))
        assertTrue(diff.contains("+ c"))
    }

    @Test
    fun csvParsesQuotedCellsAndLimit() {
        val csv = "name,city\n\"Ali, A\",Riyadh\nSara,Jeddah\nOmar,Abha"
        val text = UtilTools.csv(csv, "SELECT * LIMIT 2")
        assertTrue(text.contains("الصفوف: 3"))
        assertTrue(text.contains("Ali, A"))
        assertTrue(text.contains("Sara"))
        assertTrue(!text.contains("Omar"))
    }

    @Test
    fun csvSelectsColumns() {
        val text = UtilTools.csv("name,age\nAli,30", "SELECT age")
        assertTrue(text.contains("age"))
        assertTrue(!text.contains("name"))
    }

    @Test
    fun colorConvertsHex() {
        val text = UtilTools.color("#FFB800")
        assertEquals(true, text.contains("rgb(255, 184, 0)"))
        assertTrue(text.contains("HEX: #FFB800"))
    }
}
