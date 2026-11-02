package com.lion.ai

import org.junit.Assert.*
import org.junit.Test

class WebRouteIsolationTest {
    @Test fun explicitWebsiteSelectionNeverFallsBackToApiInAnyRoutingMode() {
        for (auto in listOf(false, true)) for (assist in listOf(false, true)) {
            val prefs = FakePrefs()
            prefs.edit().putString("provider", "arena").putBoolean("auto_route", auto)
                .putBoolean("manual_assist", assist).putString("api_key_gemini", "configured").apply()
            val plan = RoutePolicy.appPlan(prefs, TaskKind.GENERAL, AnswerMode.BALANCED, chatId = "test")
            assertEquals(listOf("arena"), plan.map { it.provider })
            assertEquals(Channel.WEB, plan.single().via)
            assertTrue(CentralBrain.rescueRoutes(prefs, TaskKind.GENERAL, plan, false).isEmpty())
            assertNull(CentralBrain.judgeRoute(prefs, TaskKind.GENERAL, plan.single(), plan))
        }
    }
    @Test fun localProxyUsesTheSameWebOnlyRule() {
        val prefs = FakePrefs()
        prefs.edit().putString("provider", "local_proxy").putString("local_proxy_backend", "arena")
            .putBoolean("auto_route", true).apply()
        val plan = RoutePolicy.serverPlan(prefs, TaskKind.GENERAL, AnswerMode.BALANCED, ChatMode.GENERAL, "test")
        assertEquals(listOf("arena"), plan.map { it.provider })
        assertEquals(Channel.WEB, plan.single().via)
    }
}
