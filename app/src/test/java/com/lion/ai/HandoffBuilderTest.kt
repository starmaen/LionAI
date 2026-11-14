package com.lion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffBuilderTest {
    private val msgs = listOf(
        true to "اصلح خطأ البناء في app/build.gradle.kts",
        false to "قرأت الملف [[FILE:app/build.gradle.kts]] ووجدت المشكلة",
        true to "ممتاز تابع",
        false to "عدّلت الملف بنجاح. المفتاح ghp_abcdefghijklmnopqrstuvwxyz0123456789AB"
    )

    @Test fun packageHasGoalStateAndRecentTurns() {
        val out = HandoffBuilder.build("إصلاح البناء", msgs, "المهمة الأصلية: x\nإذن الكتابة: ممنوح\nقواعد الاستئناف: داخلي\nملفات تغيّرت: a.kt", "2026-11-14 10:00")
        assertTrue(out.startsWith(HandoffBuilder.START) && out.endsWith(HandoffBuilder.END))
        assertTrue(out.contains("اصلح خطأ البناء"))
        assertTrue(out.contains("ملفات تغيّرت: a.kt"))
        assertTrue(out.contains("ما أريده الآن"))
    }

    @Test fun permissionsAndSecretsAndMarkersAreNotCarried() {
        val out = HandoffBuilder.build("t", msgs, "إذن الكتابة: ممنوح\nقواعد الاستئناف: داخلي", "now")
        assertFalse(out.contains("إذن الكتابة"))
        assertFalse(out.contains("قواعد الاستئناف"))
        assertFalse(out.contains("[[FILE:"))
        assertFalse(out.contains("ghp_abcdefghijklmnopqrstuvwxyz0123456789AB"))
    }

    @Test fun hugeChatStaysWithinLimit() {
        val big = (1..400).map { (it % 2 == 0) to "رسالة رقم $it " + "س".repeat(900) }
        val out = HandoffBuilder.build("كبيرة", big, "", "now", maxChars = 12_000)
        assertTrue(out.length <= 14_000)
        assertTrue(out.contains("رسالة رقم 400"))
        assertTrue(out.contains("حُذفت أقدم"))
    }

    @Test fun emptyChatDoesNotCrash() {
        val out = HandoffBuilder.build("", emptyList(), "", "now")
        assertTrue(out.contains("(غير متاح)"))
    }
}
