package com.lion.ai

import org.junit.Assert.*
import org.junit.Test

class WebRouteIsolationTest {
    @Test fun explicitWebsiteSelectionStaysFirstAndApiOnlyBacksItUpOnFailure() {
        for (auto in listOf(false, true)) for (assist in listOf(false, true)) {
            val prefs = FakePrefs()
            prefs.edit().putString("provider", "arena").putBoolean("auto_route", auto)
                .putBoolean("manual_assist", assist).putString("api_key_gemini", "configured").apply()
            val plan = RoutePolicy.appPlan(prefs, TaskKind.GENERAL, AnswerMode.BALANCED, chatId = "test")
            // الصفحة المختارة هي الأولى دائماً؛ أي بديل يأتي بعدها ولا يتقدّم عليها أبداً.
            assertEquals("arena", plan.first().provider)
            assertEquals(Channel.WEB, plan.first().via)
            assertTrue(plan.drop(1).none { it.provider == "arena" })
            assertTrue(CentralBrain.rescueRoutes(prefs, TaskKind.GENERAL, plan, false).isEmpty())
            assertNull(CentralBrain.judgeRoute(prefs, TaskKind.GENERAL, plan.first(), plan))
        }
    }
    @Test fun websiteSelectionStaysAloneWhenFailoverIsOff() {
        val prefs = FakePrefs()
        prefs.edit().putString("provider", "arena").putBoolean("auto_route", false)
            .putBoolean(FailoverPolicy.PREF, false).putString("api_key_gemini", "configured").apply()
        val plan = RoutePolicy.appPlan(prefs, TaskKind.GENERAL, AnswerMode.BALANCED, chatId = "test")
        assertEquals(listOf("arena"), plan.map { it.provider })
        assertEquals(Channel.WEB, plan.single().via)
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
