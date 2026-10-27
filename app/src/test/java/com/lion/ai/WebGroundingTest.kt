package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebGroundingTest {

    @Test
    fun buildQueryRemovesQuestionPrefix() {
        assertEquals("ابن سينا", WebGrounding.buildQuery("من هو ابن سينا؟"))
        assertEquals("عاصمة سوريا", WebGrounding.buildQuery("ما هي عاصمة سوريا؟"))
    }

    @Test
    fun buildQueryKeepsEnglishAndCapsLength() {
        assertEquals("who is avicenna", WebGrounding.buildQuery("who is avicenna"))
        assertEquals(10, WebGrounding.buildQuery("abcdefghijklmnop", max = 10).length)
    }

    @Test
    fun searchOnlyForFactualText() {
        assertTrue(WebGrounding.shouldSearch(TaskKind.FACTUAL, "من هو ابن سينا؟"))
        assertFalse(WebGrounding.shouldSearch(TaskKind.GENERAL, "من هو ابن سينا؟"))
        assertFalse(WebGrounding.shouldSearch(TaskKind.FACTUAL, "محتوى الملف (book.txt): نص"))
        assertFalse(WebGrounding.shouldSearch(TaskKind.FACTUAL, "لماذا"))
    }

    @Test
    fun greetingsAndSmallTalkNeverTriggerWebSearch() {
        assertFalse(WebGrounding.shouldSearch(TaskKind.FAST, "كيف حالك يا صديقي؟"))
        assertFalse(WebGrounding.shouldSearch(TaskKind.FAST, "مرحبا"))
    }

    @Test
    fun devAndBrokenCodeTriggerWebSearch() {
        assertTrue(WebGrounding.shouldSearch(TaskKind.DEV, "أصلح فشل البناء في المستودع"))
        assertTrue(WebGrounding.shouldSearch(TaskKind.CODE, "لماذا يظهر هذا exception في gradle؟"))
        assertFalse(WebGrounding.shouldSearch(TaskKind.CODE, "اكتب لي دالة لجمع رقمين"))
    }

    @Test
    fun explicitSourceRequestWorksForGeneral() {
        assertTrue(WebGrounding.shouldSearch(TaskKind.GENERAL, "تأكد من المصدر: كم سعر هذا الجهاز اليوم؟"))
        assertTrue(WebGrounding.explicitlyRequested("ابحث عن أحدث إصدار"))
        assertFalse(WebGrounding.explicitlyRequested("اكتب لي نصيحة للسفر"))
    }

    @Test
    fun duckDuckGoHtmlParsesTitleUrlAndSnippet() {
        val html = """
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fgithub.com%2FJetBrains%2Fkotlin&amp;rut=abc">GitHub - <b>JetBrains/kotlin</b></a>
            <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fgithub.com%2FJetBrains%2Fkotlin">The Kotlin programming language.</a>
        """.trimIndent()
        val sources = WebGrounding.parseDuckDuckGoHtml(html)
        assertEquals(1, sources.size)
        assertEquals("GitHub - JetBrains/kotlin", sources[0].title)
        assertEquals("https://github.com/JetBrains/kotlin", sources[0].url)
        assertTrue(sources[0].snippet.contains("Kotlin programming"))
    }

    @Test
    fun contextBlockIsNumberedAndIncludesUrl() {
        val block = WebGrounding.contextBlock(
            listOf(
                WebSource("ابن سينا", "https://ar.wikipedia.org/wiki/ابن_سينا", "طبيب وفيلسوف."),
                WebSource("Avicenna", "https://en.wikipedia.org/wiki/Avicenna", "Persian polymath.")
            )
        )
        assertTrue(block.contains("[1] ابن سينا"))
        assertTrue(block.contains("https://ar.wikipedia.org/wiki/ابن_سينا"))
        assertTrue(block.contains("[2] Avicenna"))
    }

    @Test
    fun emptyContextIsEmpty() {
        assertEquals("", WebGrounding.contextBlock(emptyList()))
    }
}
