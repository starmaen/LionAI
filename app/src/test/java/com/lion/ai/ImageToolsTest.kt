package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageToolsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test fun sniffsRealFormatFromBytes() {
        assertEquals(ImageTools.Kind.JPEG, ImageTools.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(ImageTools.Kind.PNG, ImageTools.sniff(bytes(0x89) + ascii("PNG\r\n")))
        assertEquals(ImageTools.Kind.GIF, ImageTools.sniff(ascii("GIF89a")))
        assertEquals(ImageTools.Kind.WEBP, ImageTools.sniff(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP")))
        assertEquals(ImageTools.Kind.BMP, ImageTools.sniff(ascii("BM") + bytes(0, 0)))
        assertEquals(ImageTools.Kind.PDF, ImageTools.sniff(ascii("%PDF-1.7")))
        assertEquals(ImageTools.Kind.HEIF, ImageTools.sniff(bytes(0, 0, 0, 24) + ascii("ftypheic")))
        assertEquals(ImageTools.Kind.AVIF, ImageTools.sniff(bytes(0, 0, 0, 24) + ascii("ftypavif")))
        assertEquals(ImageTools.Kind.SVG, ImageTools.sniff(ascii("  <svg xmlns='http://www.w3.org/2000/svg'/>")))
        assertEquals(ImageTools.Kind.UNKNOWN, ImageTools.sniff(ascii("hello world")))
        assertEquals(ImageTools.Kind.UNKNOWN, ImageTools.sniff(ByteArray(0)))
    }

    @Test fun extensionMatchesKind() {
        assertTrue("jpg" in ImageTools.extensionsOf(ImageTools.Kind.JPEG))
        assertTrue("heic" in ImageTools.extensionsOf(ImageTools.Kind.HEIF))
        assertTrue("png" !in ImageTools.extensionsOf(ImageTools.Kind.JPEG))
    }

    @Test fun sampleSizeKeepsLongestSideAboveTarget() {
        assertEquals(1, ImageTools.sampleSizeFor(1000, 800, 1800))
        assertEquals(2, ImageTools.sampleSizeFor(4000, 3000, 1800))
        assertEquals(4, ImageTools.sampleSizeFor(8000, 6000, 1800))
        assertEquals(1, ImageTools.sampleSizeFor(1800, 100, 1800).coerceAtMost(1))
    }

    @Test fun pageListHonoursRangeAndLimit() {
        assertEquals(listOf(1), ImageTools.pageList("", 10, 6))
        assertEquals(listOf(1, 2, 3, 5), ImageTools.pageList("1-3,5", 10, 6))
        assertEquals(listOf(9, 10), ImageTools.pageList("9-20", 10, 6))
        assertEquals(listOf(1, 2), ImageTools.pageList("1-9", 10, 2))
        assertTrue(ImageTools.pageList("40", 10, 6).isEmpty())
    }

    @Test fun everyNonAutoModeHasDedicatedInstruction() {
        ImageTools.MODES.filter { it != "auto" }.forEach { assertNotNull(it, ImageTools.instructionFor(it)) }
        assertNull(ImageTools.instructionFor("auto"))
    }

    @Test fun visionCandidatesPreferActiveProviderThenFailOver() {
        val prefs = FakePrefs(linkedMapOf(
            "api_key_gemini" to "k1", "api_key_openai" to "k2", "api_key_deepseek" to "k3",
            "api_key_groq" to "k4", "model_groq" to "llama-3.3-70b-versatile"
        ))
        val order = ImageToText.candidates(prefs)
        assertEquals(listOf("gemini", "openai", "deepseek"), order)
        assertEquals(listOf("openai", "gemini", "deepseek"), ImageToText.candidates(prefs, preferred = "openai"))
        assertEquals(order, ImageToText.candidates(prefs, preferred = "groq"))
        assertTrue(ImageToText.candidates(FakePrefs()).isEmpty())
    }
}
