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
    fun healthUrlFromWorkersBaseDoesNotKeepV1() {
        assertEquals(
            "https://lionai-gateway.starsyria2500.workers.dev/health",
            WebGateway.healthUrl(WebGateway.DEFAULT_URL)
        )
        assertEquals(
            "https://lionai-gateway.starsyria2500.workers.dev/v1/chat/completions",
            WebGateway.chatUrl(WebGateway.DEFAULT_URL)
        )
    }

    @Test
    fun describeHttpExplainsHtmlChallengeAndMissingToken() {
        val html = WebGateway.HttpResult(200, "<html><title>Just a moment</title></html>", "https://x/health")
        assertTrue(html.html)
        assertTrue(WebGateway.describeHttp(html).contains("HTML"))
        val auth = WebGateway.HttpResult(401, "{\"error\":{\"message\":\"Invalid relay token\"}}", "https://x/v1/models")
        val text = WebGateway.describeHttp(auth)
        assertTrue(text.contains("401"))
        assertTrue(text.contains("RELAY_TOKEN"))
    }

    @Test
    fun savePersistsUrlAndTokenBeforeTestCanReadThem() {
        val prefs = FakePrefs()
        val saved = WebGateway.save(prefs, "lionai-gateway.starsyria2500.workers.dev", " secret ", WebGateway.MODE_AUTO)
        assertEquals(WebGateway.DEFAULT_URL, saved.url)
        assertEquals("secret", saved.token)
        assertEquals(WebGateway.MODE_AUTO, saved.mode)
        assertEquals(WebGateway.DEFAULT_URL, prefs.getString(WebGateway.KEY_URL, ""))
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
