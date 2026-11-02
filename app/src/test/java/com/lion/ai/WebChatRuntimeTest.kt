package com.lion.ai

import org.junit.Assert.*
import org.junit.Test

class WebChatRuntimeTest {
    private fun ready() = FakePrefs().apply {
        edit().putBoolean("web_chat_enabled", true)
            .putBoolean("web_chat_enabled_claude_web", true)
            .putString("web_chat_model_claude_web", "claude/actual-model")
            .putString(WebGateway.KEY_URL, "https://example.invalid/v1")
            .putString(WebGateway.KEY_TOKEN, "test-only")
            .putString(WebGateway.KEY_MODE, WebGateway.MODE_ONLY)
            .apply()
    }

    @Test fun webPagesAreNotAutomaticallyChatBackends() {
        val prefs = FakePrefs()
        assertFalse(WebChatRuntime.available(prefs, "claude_web"))
        assertFalse(WebChatRuntime.available(prefs, WebChatRuntime.HUB))
        assertTrue(WebChatRuntime.members(prefs).isEmpty())
    }

    @Test fun configuredProvidersParticipateIndividuallyAndAsHub() {
        val prefs = ready()
        assertTrue(WebChatRuntime.available(prefs, "claude_web"))
        assertTrue(WebChatRuntime.available(prefs, WebChatRuntime.HUB))
        assertFalse(WebChatRuntime.available(prefs, "chatgpt_web"))
        assertEquals(listOf("claude_web"), WebChatRuntime.routes(prefs).map { it.provider })
    }

    @Test fun allKillSwitchesAreRespected() {
        for (key in listOf("web_chat_enabled", "web_chat_enabled_claude_web", "enabled_web_gateway", "enabled_claude_web", "channel_gateway")) {
            val prefs = ready()
            prefs.edit().putBoolean(key, false).apply()
            assertFalse(key, WebChatRuntime.available(prefs, "claude_web"))
        }
    }

    @Test fun centralOptOutDoesNotDisableManualSelection() {
        val prefs = ready()
        prefs.edit().putBoolean(WebChatRuntime.CENTRAL, false).apply()
        assertTrue(WebChatRuntime.routes(prefs).isEmpty())
        assertTrue(WebChatRuntime.available(prefs, "claude_web"))
    }

    @Test fun selectedWebIdentitySurvivesAndCannotLoopIntoLocalProxy() {
        val prefs = ready()
        prefs.edit().putString("provider", "claude_web").putBoolean("auto_route", false).apply()
        assertEquals("claude_web", RoutePolicy.selectedProvider(prefs))
        assertEquals("claude_web", LocalAIProxy.backendProvider(prefs))
        assertEquals("claude/actual-model", RoutePolicy.modelFor(prefs, "claude_web"))
        assertEquals(listOf(Route("claude_web", "claude/actual-model", Channel.GATEWAY)),
            RoutePolicy.channelVariants(prefs, Route("claude_web", "claude/actual-model")))
    }

    @Test fun modelIdsKeepFullNamespaceAndRejectMissingNames() {
        assertTrue(WebChatRuntime.validModel("cfai/@cf/meta/llama-example"))
        assertFalse(WebChatRuntime.validModel(""))
        assertFalse(WebChatRuntime.validModel("claude"))
        assertFalse(WebChatRuntime.validModel("https://claude.ai"))
        assertFalse(WebChatRuntime.validModel("claude/"))
        assertFalse(WebChatRuntime.validModel("/model"))
        assertFalse(WebChatRuntime.validModel("provider/invalid model"))
    }

    @Test fun registryUsesTheSameIdsAsBrowserPages() {
        assertEquals(PROVIDERS.filter { it.group == ProviderGroup.WEB }.map { it.id },
            com.lion.ai.core.WebProviderAdapter.providers.map { it.id })
        assertTrue(providerInfo("lion_chat").supportsAppChat)
        assertTrue(providerInfo("claude_web").supportsAppChat)
        assertFalse(PROVIDERS.first { it.group == ProviderGroup.MEDIA }.supportsAppChat)
    }
}
