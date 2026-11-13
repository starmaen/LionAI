package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRegistryTest {
    private fun configuredPrefs(): FakePrefs {
        val prefs = FakePrefs()
        prefs.edit()
            .putString(WebGateway.KEY_URL, "https://gw.example.workers.dev/v1")
            .putString(WebGateway.KEY_TOKEN, "token")
            .putString(WebGateway.KEY_MODE, WebGateway.MODE_AUTO)
            .commit()
        return prefs
    }

    @Test
    fun directModeHasNoRelays() {
        val prefs = FakePrefs()
        assertTrue(ServerRegistry.isOff(prefs))
        assertTrue(ServerRegistry.relays(prefs).isEmpty())
        WebGateway.load(prefs)
        assertFalse(WebGateway.shouldRoute("groq"))
    }

    @Test
    fun builtinGatewayIsRelayWhenConfiguredAndOn() {
        val prefs = configuredPrefs()
        val relays = ServerRegistry.relays(prefs)
        assertEquals(1, relays.size)
        assertEquals("https://gw.example.workers.dev/v1", relays.first().url)
        WebGateway.load(prefs)
        assertTrue(WebGateway.shouldRoute("groq"))
    }

    @Test
    fun addedServerIsNormalizedAndStartsDisabled() {
        val prefs = FakePrefs()
        assertNull(ServerRegistry.upsert(prefs, null, "Termux", ServerRegistry.KIND_LOCAL, "127.0.0.1:20128", ""))
        val server = ServerRegistry.list(prefs).single()
        assertEquals("http://127.0.0.1:20128/v1", server.url)
        assertEquals("Termux", server.name)
        assertFalse(server.enabled)
    }

    @Test
    fun emptyUrlIsRejected() {
        val prefs = FakePrefs()
        assertTrue(ServerRegistry.upsert(prefs, null, "x", ServerRegistry.KIND_EXTERNAL, "  ", "") != null)
        assertTrue(ServerRegistry.list(prefs).isEmpty())
    }

    @Test
    fun allScopeUsesEveryEnabledServerAndOneScopeKeepsSingle() {
        val prefs = configuredPrefs()
        ServerRegistry.upsert(prefs, null, "A", ServerRegistry.KIND_EXTERNAL, "a.example.com", "ka")
        ServerRegistry.upsert(prefs, null, "B", ServerRegistry.KIND_LOCAL, "10.0.0.5:4000", "")
        val ids = ServerRegistry.list(prefs).map { it.id }
        ServerRegistry.setScope(prefs, ServerRegistry.SCOPE_ALL)
        ids.forEach { ServerRegistry.setOn(prefs, it, true) }
        assertEquals(3, ServerRegistry.relays(prefs).size)

        ServerRegistry.setScope(prefs, ServerRegistry.SCOPE_ONE)
        assertEquals(1, ServerRegistry.relays(prefs).size)

        ServerRegistry.setOn(prefs, ids[1], true)
        val only = ServerRegistry.relays(prefs)
        assertEquals(1, only.size)
        assertEquals("B", only.first().name)
        assertFalse(ServerRegistry.list(prefs).first { it.id == ids[0] }.enabled)
    }

    @Test
    fun switchingToDirectClearsRelaysAndChannels() {
        val prefs = configuredPrefs()
        ServerRegistry.applyChannels(prefs)
        assertTrue(RoutePolicy.channelEnabled(prefs, Channel.GATEWAY))
        ServerRegistry.selectMode(prefs, off = true)
        assertTrue(ServerRegistry.relays(prefs).isEmpty())
        assertFalse(RoutePolicy.channelEnabled(prefs, Channel.GATEWAY))
        assertFalse(RoutePolicy.channelEnabled(prefs, Channel.LOCAL))
        assertTrue(RoutePolicy.channelEnabled(prefs, Channel.DIRECT))
        // العودة لا تفقد إعدادات الخوادم.
        ServerRegistry.selectMode(prefs, off = false)
        assertEquals(1, ServerRegistry.relays(prefs).size)
    }

    @Test
    fun directFirstOffMeansServersOnlyMode() {
        val prefs = configuredPrefs()
        ServerRegistry.setDirectFirst(prefs, false)
        assertEquals(WebGateway.MODE_ONLY, prefs.getString(WebGateway.KEY_MODE, ""))
        assertFalse(RoutePolicy.channelEnabled(prefs, Channel.DIRECT))
        ServerRegistry.setDirectFirst(prefs, true)
        assertEquals(WebGateway.MODE_AUTO, prefs.getString(WebGateway.KEY_MODE, ""))
        assertTrue(RoutePolicy.channelEnabled(prefs, Channel.DIRECT))
    }
}
