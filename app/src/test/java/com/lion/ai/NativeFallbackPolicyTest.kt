package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** يقفل سياسة الرجوع للبروتوكول النصي: لا طلب مكرر عند حد/مفتاح/مهلة/سياق، ويبقى الرجوع عند رفض الأدوات. */
class NativeFallbackPolicyTest {
    private fun d(e: Throwable) = NativeToolRoutes.decide(e)

    @Test
    fun rateKeyQuotaAndOverloadAreSurfacedNotResent() {
        listOf(401, 402, 403, 408, 413, 429, 502, 503, 504, 529).forEach {
            assertEquals("status $it", NativeToolRoutes.Fallback.SURFACE_ERROR, d(LlmException(it, "x")))
        }
    }

    @Test
    fun rejectionOfToolsFieldFallsBackToTextProtocol() {
        listOf(400, 404, 405, 415, 422, 500).forEach {
            assertEquals("status $it", NativeToolRoutes.Fallback.TEXT_PROTOCOL, d(LlmException(it, "unknown field tools")))
        }
    }

    @Test
    fun contextOverflowOn400IsSurfacedAndNeverMarksToolsBroken() {
        val e = LlmException(400, "This model's maximum context length is 8192 tokens")
        assertEquals(NativeToolRoutes.Fallback.SURFACE_ERROR, d(e))
        assertFalse(NativeToolRoutes.shouldMarkBroken(e))
        assertEquals(QuotaGuard.Kind.CONTEXT, QuotaGuard.classify(NativeToolRoutes.errorReply(e, "m")))
    }

    @Test
    fun onlyExplicitRejectionMarksNativeBroken() {
        assertTrue(NativeToolRoutes.shouldMarkBroken(LlmException(422, "tools not supported")))
        assertFalse(NativeToolRoutes.shouldMarkBroken(LlmException(429, "slow down")))
        assertFalse(NativeToolRoutes.shouldMarkBroken(LlmException(500, "boom")))
        assertFalse(NativeToolRoutes.shouldMarkBroken(java.io.IOException("x")))
    }

    @Test
    fun networkAndTimeoutAreSurfaced_parsingErrorsFallBack() {
        assertEquals(NativeToolRoutes.Fallback.SURFACE_ERROR, d(java.net.SocketTimeoutException("Read timed out")))
        assertEquals(NativeToolRoutes.Fallback.SURFACE_ERROR, d(java.net.UnknownHostException("h")))
        assertEquals(NativeToolRoutes.Fallback.TEXT_PROTOCOL, d(IllegalStateException("bad json shape")))
    }

    @Test
    fun errorRepliesAreClassifiedByQuotaGuard() {
        assertEquals(QuotaGuard.Kind.RATE, QuotaGuard.classify(NativeToolRoutes.errorReply(LlmException(429, "slow"), "m")))
        assertEquals(QuotaGuard.Kind.QUOTA, QuotaGuard.classify(NativeToolRoutes.errorReply(LlmException(402, "pay"), "m")))
        assertEquals(QuotaGuard.Kind.AUTH, QuotaGuard.classify(NativeToolRoutes.errorReply(LlmException(401, "bad"), "m")))
        assertEquals(QuotaGuard.Kind.TRANSIENT, QuotaGuard.classify(NativeToolRoutes.errorReply(java.net.SocketTimeoutException("t"), "m")))
        assertEquals(QuotaGuard.Kind.TRANSIENT, QuotaGuard.classify(NativeToolRoutes.errorReply(LlmException(503, "busy"), "m")))
        assertTrue(LlmClient.isErrorReply(NativeToolRoutes.errorReply(LlmException(429, "slow"), "m")))
    }

    @Test
    fun openAiUsesNativeToolsWithModernParameters() {
        assertTrue(NativeToolRoutes.route("openai") != null)
        assertEquals("max_completion_tokens" to true, NativeToolRoutes.requestParams("openai"))
        assertEquals("max_tokens" to false, NativeToolRoutes.requestParams("groq"))
    }
}
