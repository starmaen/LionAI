package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverPolicyTest {
    @Test fun enabledByDefaultAndSwitchableOff() {
        val prefs = FakePrefs()
        assertTrue(FailoverPolicy.enabled(prefs))
        prefs.edit().putBoolean(FailoverPolicy.PREF, false).apply()
        assertFalse(FailoverPolicy.enabled(prefs))
    }

    @Test fun backupsAreEmptyWhenDisabled() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean(FailoverPolicy.PREF, false).putString("api_key_gemini", "configured").apply()
        assertTrue(FailoverPolicy.backupsFor(prefs, TaskKind.DEV, Route("arena", "")).isEmpty())
    }

    @Test fun backupsNeverContainPrimaryOrLocalRoutes() {
        val prefs = FakePrefs()
        prefs.edit().putString("api_key_gemini", "configured").putString("api_key_groq", "configured").apply()
        val primary = Route("gemini", "")
        val backups = FailoverPolicy.backupsFor(prefs, TaskKind.DEV, primary, limit = 3)
        assertTrue(backups.none { it.provider == "gemini" })
        assertTrue(backups.none { it.provider == RoutePolicy.LOCAL_PROXY || it.provider == RoutePolicy.CUSTOM })
        assertTrue(backups.size <= 3)
    }

    @Test fun manualModeAssistFollowsFailoverPreference() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean("auto_route", false).apply()
        assertTrue(RoutePolicy.assistEnabled(prefs))
        prefs.edit().putBoolean(FailoverPolicy.PREF, false).apply()
        assertEquals(false, RoutePolicy.assistEnabled(prefs))
    }
}
