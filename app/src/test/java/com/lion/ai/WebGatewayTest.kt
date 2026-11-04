package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebGatewayTest {
    @Test
    fun normalizeBaseAddsSchemeAndV1() {
        assertEquals("https://name.workers.dev/v1", WebGateway.normalizeBase("name.workers.dev"))
        assertEquals("https://x.dev/v1", WebGateway.normalizeBase("https://x.dev/v1/"))
    }

    @Test
    fun gatewayModelPrefixesProviderOnce() {
        assertEquals("groq/openai/gpt-oss-120b", WebGateway.gatewayModel("groq", "openai/gpt-oss-120b"))
        assertEquals("groq/llama", WebGateway.gatewayModel("groq", "groq/llama"))
    }

    @Test
    fun newFreeProvidersAreSupportedAndHaveDefaults() {
        assertTrue(WebGateway.supports("unorouter"))
        assertTrue(WebGateway.supports("cerebras"))
        assertEquals("deepseek-v4-pro:free", WebGateway.defaultModel("unorouter"))
        assertTrue(PROVIDERS.any { it.id == "unorouter" && it.endpoint.startsWith("https://api.unorouter.com") })
        assertTrue(PROVIDERS.any { it.id == "cerebras" })
    }
}
