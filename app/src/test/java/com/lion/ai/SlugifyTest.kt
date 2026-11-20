package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class SlugifyTest {

    @Test
    fun lowercasesEnglishAndJoinsWordsWithDash() {
        assertEquals("hello-world", Slugify.toSlug("Hello World"))
    }

    @Test
    fun collapsesRepeatedSeparatorsAndTrimsEdges() {
        assertEquals("lion-ai", Slugify.toSlug("  Lion   AI!! "))
    }

    @Test
    fun keepsArabicLettersAndDigits() {
        assertEquals("متصفح-الأسد-2", Slugify.toSlug("متصفح الأسد 2"))
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals("", Slugify.toSlug(""))
    }

    @Test
    fun symbolsOnlyInputYieldsEmptySlug() {
        assertEquals("", Slugify.toSlug("!!! ??? ..."))
    }

    @Test
    fun dashesInInputAreMergedNotDoubled() {
        assertEquals("lion-ai-agent", Slugify.toSlug("Lion--AI__agent"))
    }
}