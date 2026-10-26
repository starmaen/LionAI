package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRagTest {

    @Test
    fun shortDocumentIsReturnedUnchanged() {
        val doc = "نص قصير عن أندرويد."
        assertEquals(doc, LocalRag.select(doc, "أندرويد", maxChars = 1_000))
    }

    @Test
    fun relevantChunkIsSelectedFromLongDocument() {
        val filler = "هذا نص عام لا علاقة له بالسؤال. ".repeat(200)
        val target = "معلومات مهمة: مدينة تدمر تقع في محافظة حمص السورية."
        val tail = "خاتمة عامة. ".repeat(50)
        val doc = filler + "\n\n" + target + "\n\n" + tail
        val result = LocalRag.select(doc, "أين تقع تدمر؟", maxChars = 4_000)
        assertTrue(result.contains("تدمر"))
        assertTrue(result.length <= 4_000)
    }

    @Test
    fun summarizationSpreadsAcrossDocument() {
        val part = "فقرة رقم ١ عن التاريخ. ".repeat(40)
        val doc = (1..20).joinToString("\n\n") { "$part $it" }
        val result = LocalRag.select(doc, "لخص الملف", maxChars = 3_000)
        assertTrue(result.length <= 3_000)
        assertFalse(result.isBlank())
        assertTrue(result.contains("…"))
    }
}
