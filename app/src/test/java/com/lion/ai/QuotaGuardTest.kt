package com.lion.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QuotaGuardTest {
    private var now = 1_000_000L

    @Before fun setUp() { QuotaGuard.resetForTests(); QuotaGuard.clock = { now } }
    @After fun tearDown() { QuotaGuard.resetForTests() }

    @Test fun classifiesByPrefixAndText() {
        assertEquals(QuotaGuard.Kind.RATE, QuotaGuard.classify("⏳ تجاوزت حد الاستخدام المجاني مؤقتاً"))
        assertEquals(QuotaGuard.Kind.QUOTA, QuotaGuard.classify("💳 لا يوجد رصيد كافٍ"))
        assertEquals(QuotaGuard.Kind.AUTH, QuotaGuard.classify("🔑 المفتاح غير صالح (401)"))
        assertEquals(QuotaGuard.Kind.QUOTA, QuotaGuard.classify("⚠️ You exceeded your current quota"))
        assertEquals(QuotaGuard.Kind.TRANSIENT, QuotaGuard.classify("⚠️ timeout while reading"))
        assertEquals(QuotaGuard.Kind.OTHER, QuotaGuard.classify("⚠️ صيغة غير متوقعة"))
    }

    @Test fun retryOnlyForTransientAndShortRate() {
        assertTrue(QuotaGuard.retryable(QuotaGuard.Kind.TRANSIENT))
        assertTrue(QuotaGuard.retryable(QuotaGuard.Kind.RATE))
        assertFalse(QuotaGuard.retryable(QuotaGuard.Kind.QUOTA))
        assertFalse(QuotaGuard.retryable(QuotaGuard.Kind.AUTH))
    }

    @Test fun cooldownExpiresAndClears() {
        QuotaGuard.mark("groq", QuotaGuard.Kind.RATE)
        assertTrue(QuotaGuard.isCooling("groq"))
        now += 91_000L
        assertFalse(QuotaGuard.isCooling("groq"))
        QuotaGuard.mark("groq", QuotaGuard.Kind.QUOTA)
        assertTrue(QuotaGuard.isCooling("groq"))
        QuotaGuard.clear("groq")
        assertFalse(QuotaGuard.isCooling("groq"))
    }

    @Test fun otherErrorsNeverCool() {
        QuotaGuard.mark("x", QuotaGuard.Kind.OTHER)
        assertFalse(QuotaGuard.isCooling("x"))
    }

    @Test fun backoffGrowsAndIsCapped() {
        assertTrue(QuotaGuard.backoffMs(QuotaGuard.Kind.RATE, 2) > QuotaGuard.backoffMs(QuotaGuard.Kind.RATE, 1))
        assertTrue(QuotaGuard.backoffMs(QuotaGuard.Kind.RATE, 50) <= 20_000L)
    }
}
