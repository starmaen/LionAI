package com.lion.ai

import org.junit.Assert.*
import org.junit.Test

class WebChatRuntimeTest {
    private fun ready() = FakePrefs().apply {
        edit().putBoolean(WebChatRuntime.ENABLED, true)
            .putBoolean("web_chat_enabled_claude_web", true).apply()
    }
    @Test fun browserTransportDoesNotRequireGatewayOrApiKey() {
        val prefs = ready()
        prefs.edit().putBoolean("enabled_web_gateway", false).putBoolean("channel_gateway", false).apply()
        assertTrue(WebChatRuntime.available(prefs, "claude_web"))
        assertTrue(WebChatRuntime.available(prefs, WebChatRuntime.HUB))
        assertEquals("web-session", WebChatRuntime.model(prefs, "claude_web"))
    }
    @Test fun oldGatewayTogglesDoNotConsentToSendingHistoryToWebsites() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean("web_chat_enabled", true)
            .putBoolean("web_chat_enabled_claude_web", true)
            .putString("web_chat_model_claude_web", "claude/model").apply()
        assertFalse(WebChatRuntime.available(prefs, "claude_web"))
    }
    @Test fun disabledSitesAndMasterSwitchBlockRequests() {
        for (key in listOf(WebChatRuntime.ENABLED, "web_chat_enabled_claude_web", "enabled_claude_web")) {
            val prefs = ready()
            prefs.edit().putBoolean(key, false).apply()
            assertFalse(WebChatRuntime.available(prefs, "claude_web"))
        }
    }
    @Test fun centralOnlyReceivesActuallyVerifiedSites() {
        val prefs = ready()
        assertTrue(WebChatRuntime.routes(prefs).isEmpty())
        prefs.edit().putBoolean("web_session_verified_claude_web", true).apply()
        assertEquals(listOf("claude_web"), WebChatRuntime.routes(prefs).map { it.provider })
        prefs.edit().putBoolean(WebChatRuntime.CENTRAL, false).apply()
        assertTrue(WebChatRuntime.routes(prefs).isEmpty())
        assertTrue(WebChatRuntime.available(prefs, "claude_web"))
    }
    @Test fun browserIdentitySurvivesRoutingAndLocalProxyCannotRecurse() {
        val prefs = ready()
        prefs.edit().putString("provider", "claude_web").putBoolean("auto_route", false).apply()
        assertEquals("claude_web", RoutePolicy.selectedProvider(prefs))
        assertEquals("claude_web", LocalAIProxy.backendProvider(prefs))
        assertEquals(listOf(Route("claude_web", "web-session", Channel.WEB)),
            RoutePolicy.channelVariants(prefs, Route("claude_web", "web-session")))
    }
    @Test fun everyChatWebsiteHasItsOwnAdapterAndExactOriginAllowlist() {
        val ids = PROVIDERS.filter { it.group == ProviderGroup.WEB }.map { it.id }.toSet()
        assertEquals(ids, WebSiteAdapters.all().map { it.id }.toSet())
        WebSiteAdapters.all().forEach {
            assertTrue(it.accepts(it.url))
            assertFalse(it.accepts("https://evil.example/"))
            assertFalse(it.accepts(it.url.replace("https:", "http:")))
            assertTrue(it.input.isNotBlank() && it.send.isNotBlank() && it.answer.isNotBlank())
        }
        val claude = requireNotNull(WebSiteAdapters.get("claude_web"))
        assertFalse(claude.accepts("https://claude.ai.evil.example/"))
        assertFalse(claude.accepts("https://claude.ai@evil.example/"))
        assertFalse(claude.accepts("https://evil@claude.ai/"))
        assertFalse(WebSiteAdapters.get("arena")!!.url.contains("submitPrompt"))
        assertTrue(WebSiteAdapters.get("arena")!!.url.contains("/text/direct"))
        assertTrue(requireNotNull(WebSiteAdapters.get("duckduckgo_web")).accepts("https://duck.ai/"))
        assertTrue(requireNotNull(WebSiteAdapters.get("chatgpt_web")).accepts("https://chat.openai.com/"))
        assertTrue(requireNotNull(WebSiteAdapters.get("you_web")).accepts("https://you.com/?chatMode=default"))
        assertTrue(claude.send.contains("send-button") || claude.send.contains("aria-label"))
        WebSiteAdapters.all().forEach { adapter ->
            assertTrue(adapter.id, adapter.send.contains("aria-label") || adapter.send.contains("send-button"))
            assertTrue(adapter.id, adapter.input.contains("textarea") || adapter.input.contains("contenteditable"))
            val host = java.net.URI(adapter.url).host.lowercase().removePrefix("www.")
            assertTrue(adapter.id, adapter.accepts("https://www.$host/"))
            assertTrue(adapter.id, adapter.accepts("https://$host/"))
        }
    }
    @Test fun pteroAndLumoAreBridgedLikeEveryOtherWebProvider() {
        listOf("ptero_web" to "https://ptero.pro/", "lumo_web" to "https://lumo.proton.me/guest").forEach { (id, url) ->
            assertTrue(id, PROVIDERS.any { it.id == id && it.group == ProviderGroup.WEB })
            assertTrue(id, WebChatRuntime.isWeb(id))
            val adapter = requireNotNull(WebSiteAdapters.get(id))
            assertEquals(url, adapter.url)
            assertTrue(adapter.accepts(url))
            assertFalse(adapter.accepts("https://evil.example/"))
        }
        assertTrue(requireNotNull(WebSiteAdapters.get("gemini_web")).accepts("https://gemini.google.com/app"))
        assertTrue(requireNotNull(WebSiteAdapters.get("copilot_web")).accepts("https://copilot.microsoft.com/chats"))
        assertTrue(requireNotNull(WebSiteAdapters.get("copilot_web")).accepts("https://copilot.com/"))
        assertFalse(requireNotNull(WebSiteAdapters.get("copilot_web")).accepts("https://www.bing.com/"))
    }
    @Test fun removedWebProvidersAreGoneEverywhereAndDeadBackendFallsBack() {
        val removed = listOf("hf_chat_web", "kimi_web", "phind_web", "blackbox_web", "pi_web")
        removed.forEach { id ->
            assertFalse(id, PROVIDERS.any { it.id == id })
            assertFalse(id, WebSiteAdapters.all().any { it.id == id })
            assertFalse(id, WebChatRuntime.isWeb(id))
        }
        val prefs = ready()
        prefs.edit().putString("provider", "kimi_web").apply()
        assertEquals("gemini", RoutePolicy.selectedProvider(prefs))
        assertEquals("gemini", LocalAIProxy.backendProvider(prefs))
        listOf("gemini_web", "copilot_web", "perplexity_web", "you_web", "ptero_web", "lumo_web").forEach {
            assertTrue(it, WebChatRuntime.isWeb(it))
        }
    }
}
