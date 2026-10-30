package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderTest {

    @Test
    fun normalizesBaseUrlsAndFullEndpoints() {
        assertEquals(
            "http://localhost:4000/v1/chat/completions",
            LlmClient.normalizeCustomUrl("http://localhost:4000")
        )
        assertEquals(
            "http://localhost:4000/v1/chat/completions",
            LlmClient.normalizeCustomUrl("http://localhost:4000/v1")
        )
        assertEquals(
            "https://gateway.example.com/v1/chat/completions",
            LlmClient.normalizeCustomUrl("https://gateway.example.com/v1/chat/completions/")
        )
        assertEquals(
            "http://192.168.1.5:11434/v1/chat/completions",
            LlmClient.normalizeCustomUrl("http://192.168.1.5:11434")
        )
    }

    @Test
    fun acceptsHttpsAndPrivateLanButRejectsPublicPlainHttp() {
        assertTrue(LlmClient.isSafeEndpoint("https://gateway.example.com/v1"))
        assertTrue(LlmClient.isSafeEndpoint("http://localhost:4000"))
        assertTrue(LlmClient.isSafeEndpoint("http://127.0.0.1:4000"))
        assertTrue(LlmClient.isSafeEndpoint("http://192.168.1.5:4000"))
        assertTrue(LlmClient.isSafeEndpoint("http://10.0.2.2:4000"))
        assertTrue(LlmClient.isSafeEndpoint("http://172.20.0.5:4000"))
        assertFalse(LlmClient.isSafeEndpoint("http://example.com/v1"))
        assertFalse(LlmClient.isSafeEndpoint("ftp://example.com"))
    }

    @Test
    fun customProviderIsDiscoverableAndKeyless() {
        val info = providerInfo("custom")
        assertEquals("custom", info.id)
        assertTrue(info.custom)
        assertTrue(info.keyless)
    }

    @Test
    fun addsSchemeForBareHosts() {
        assertEquals(
            "http://192.168.1.5:11434/v1/chat/completions",
            LlmClient.normalizeCustomUrl("192.168.1.5:11434")
        )
        assertEquals(
            "https://gateway.example.com/v1/chat/completions",
            LlmClient.normalizeCustomUrl("gateway.example.com")
        )
    }

    @Test
    fun rejectsDocumentationUrlsWithGuidance() {
        val docs = LlmClient.customUrlProblem("https://docs.litellm.ai")
        assertTrue(docs != null && docs.contains("توثيق"))
        assertTrue(LlmClient.customUrlProblem("https://github.com/berriai/litellm") != null)
        assertTrue(LlmClient.customUrlProblem("http://example.com/v1") != null)
        assertTrue(LlmClient.customUrlProblem("") != null)
        assertNull(LlmClient.customUrlProblem("http://192.168.1.5:4000/v1"))
        assertNull(LlmClient.customUrlProblem("https://gateway.example.com/v1"))
    }
}
