package com.lion.ai

import org.junit.Test
import org.junit.Assert.*

class SlugifyTest {
    @Test
    fun testHelloWorld() {
        assertEquals("hello-world", Slugify.toSlug("Hello World"))
    }

    @Test
    fun testSpacesAndExclamations() {
        assertEquals("lion-ai", Slugify.toSlug("  Lion   AI!! "))
    }

    @Test
    fun testArabicAndNumbers() {
        assertEquals("متصفح-الأسد-2", Slugify.toSlug("متصفح الأسد 2"))
    }

    @Test
    fun testEmptyString() {
        assertEquals("", Slugify.toSlug(""))
    }
}