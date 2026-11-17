package com.lion.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class UnderstandingEchoTest {
    @Test
    fun labelsWorkKindAndQuotesRequest() {
        val cases = mapOf(
            "افحص الأدوات" to "فحص",
            "اختبر البوابة" to "اختبار",
            "ابدء الاصلاح" to "تنفيذ",
            "أصلح الخطأ في المشروع" to "إصلاح",
            "عدل ملف Main.kt" to "تعديل وتطوير"
        )
        for ((prompt, kind) in cases) {
            val line = UnderstandingEcho.line(prompt)
            assertTrue("$prompt -> $line", line.contains(kind))
            assertTrue(line.contains(prompt))
        }
    }

    @Test
    fun longRequestsAreTruncatedAndBlankHasFallback() {
        val line = UnderstandingEcho.line("افحص " + "ا".repeat(300))
        assertTrue(line.length < 160)
        assertTrue(line.contains("…"))
        assertTrue(UnderstandingEcho.line("   ").isNotBlank())
    }

    @Test
    fun unknownVerbStillEchoes() {
        assertTrue(UnderstandingEcho.line("المشروع بطيء جدا").contains("فهمت طلبك"))
    }
}
