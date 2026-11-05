package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات قنوات التنفيذ (مباشر / خادم خارجي / خادم محلي):
 * اختيار المزود مستقل عن الطريق إليه، ويمكن تشغيل القنوات منفردة أو مجتمعة.
 */
class ChannelRoutingTest {

    private fun manualPrefs(
        provider: String = "gemini",
        gatewayConfigured: Boolean = false
    ): FakePrefs {
        val prefs = FakePrefs()
        prefs.edit()
            .putString("provider", provider)
            .putBoolean("auto_route", false)
            .apply()
        if (gatewayConfigured) {
            prefs.edit()
                .putString(WebGateway.KEY_URL, "https://gw.example.workers.dev/v1")
                .putString(WebGateway.KEY_TOKEN, "token")
                .putString(WebGateway.KEY_MODE, WebGateway.MODE_AUTO)
                .putBoolean("enabled_web_gateway", true)
                .apply()
            WebGateway.load(prefs)
        }
        return prefs
    }

    @Test
    fun directChannelIsOnByDefault() {
        val prefs = manualPrefs()
        assertTrue(RoutePolicy.channelEnabled(prefs, Channel.DIRECT))
        assertFalse("الخادم الخارجي يبقى موقوفاً ما لم يُضبَط", RoutePolicy.channelEnabled(prefs, Channel.GATEWAY))
        assertFalse("الخادم المحلي يُفعّل بطلب المستخدم", RoutePolicy.channelEnabled(prefs, Channel.LOCAL))
    }

    @Test
    fun gatewayChannelIsOnWhenGatewayIsConfigured() {
        val prefs = manualPrefs(gatewayConfigured = true)
        assertTrue(RoutePolicy.channelEnabled(prefs, Channel.GATEWAY))
    }

    @Test
    fun manualWithoutChannelsUsesDirectOnly() {
        val prefs = manualPrefs()
        val plan = RoutePolicy.appPlan(prefs, TaskKind.GENERAL, AnswerMode.BALANCED)
        assertEquals(1, plan.size)
        assertEquals("gemini", plan.first().provider)
        assertEquals(Channel.DIRECT, plan.first().via)
    }

    @Test
    fun manualWithGatewayAddsGatewayAttempt() {
        val prefs = manualPrefs(gatewayConfigured = true)
        RoutePolicy.setChannelEnabled(prefs, Channel.GATEWAY, true)
        val plan = RoutePolicy.appPlan(prefs, TaskKind.GENERAL, AnswerMode.BALANCED)
        assertEquals(listOf(Channel.DIRECT, Channel.GATEWAY), plan.map { it.via })
        assertTrue(plan.all { it.provider == "gemini" })
    }

    @Test
    fun manualWithEveryChannelTriesThemInOrder() {
        val prefs = manualPrefs(gatewayConfigured = true)
        RoutePolicy.setChannelEnabled(prefs, Channel.GATEWAY, true)
        RoutePolicy.setChannelEnabled(prefs, Channel.LOCAL, true)
        // الخادم المحلي متوقف في بيئة الاختبار، فتبقى القنوات المتاحة فعلاً.
        val plan = RoutePolicy.appPlan(prefs, TaskKind.GENERAL, AnswerMode.BALANCED)
        assertEquals(listOf(Channel.DIRECT, Channel.GATEWAY), plan.map { it.via })
    }

    @Test
    fun turningDirectOffKeepsTheOtherChannels() {
        val prefs = manualPrefs(gatewayConfigured = true)
        RoutePolicy.setChannelEnabled(prefs, Channel.DIRECT, false)
        RoutePolicy.setChannelEnabled(prefs, Channel.GATEWAY, true)
        val plan = RoutePolicy.appPlan(prefs, TaskKind.GENERAL, AnswerMode.BALANCED)
        assertEquals(listOf(Channel.GATEWAY), plan.map { it.via })
    }

    @Test
    fun closingEveryChannelFallsBackToDirect() {
        val prefs = manualPrefs()
        RoutePolicy.setChannelEnabled(prefs, Channel.DIRECT, false)
        val plan = RoutePolicy.appPlan(prefs, TaskKind.GENERAL, AnswerMode.BALANCED)
        assertEquals(listOf(Channel.DIRECT), plan.map { it.via })
    }

    @Test
    fun unsupportedGatewayProviderSkipsGatewayChannel() {
        // مزود لا تدعمه البوابة (مزود مخصص محلي) يتجاوز قناة الخادم الخارجي.
        val prefs = manualPrefs(provider = "custom", gatewayConfigured = true)
        RoutePolicy.setChannelEnabled(prefs, Channel.GATEWAY, true)
        val plan = RoutePolicy.appPlan(prefs, TaskKind.GENERAL, AnswerMode.BALANCED)
        assertTrue(plan.none { it.via == Channel.GATEWAY })
    }

    @Test
    fun automaticPreferenceUsesTheSameKeyAsTheUi() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean("auto_route", true).apply()
        assertTrue(RoutePolicy.PREF_AUTO == "auto_route")
        assertFalse(RoutePolicy.isManual(prefs))
        prefs.edit().putBoolean("auto_route", false).apply()
        assertTrue(RoutePolicy.isManual(prefs))
    }

    @Test
    fun executionMergeKeepsEveryChannelOfTheSameProvider() {
        val plan = listOf(
            Route("gemini", "m", Channel.DIRECT),
            Route("gemini", "m", Channel.GATEWAY),
            Route("gemini", "m", Channel.LOCAL)
        )
        val merged = RoutePolicy.mergeAttempts(plan, emptyList(), "gemini")
        assertEquals(listOf(Channel.DIRECT, Channel.GATEWAY, Channel.LOCAL), merged.map { it.via })
    }

    @Test
    fun localServerManualPlanDoesNotReenableDirect() {
        val prefs = manualPrefs(gatewayConfigured = true)
        RoutePolicy.setChannelEnabled(prefs, Channel.DIRECT, false)
        RoutePolicy.setChannelEnabled(prefs, Channel.GATEWAY, true)
        val plan = RoutePolicy.serverPlan(prefs, TaskKind.GENERAL, AnswerMode.BALANCED, ChatMode.GENERAL)
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.none { it.via == Channel.DIRECT })
        assertTrue(plan.any { it.via == Channel.GATEWAY })
    }

    @Test
    fun lastRouteRemembersTheChannelThatAnswered() {
        val prefs = manualPrefs()
        LastRoute.set(prefs, "chat-1", Route("groq", "openai/gpt-oss-120b", Channel.GATEWAY))
        val route = LastRoute.get(prefs, "chat-1")
        assertEquals("groq", route?.provider)
        assertEquals("openai/gpt-oss-120b", route?.model)
        assertEquals(Channel.GATEWAY, route?.via)
    }

    @Test
    fun lastRouteReadsOldValuesWithoutChannel() {
        val prefs = manualPrefs()
        // قيمة قديمة مخزّنة بصيغة provider|model قبل إضافة القناة.
        prefs.edit().putString("route_last_chat-2", "gemini|gemini-3.8-flash").apply()
        val route = LastRoute.get(prefs, "chat-2")
        assertEquals("gemini", route?.provider)
        assertEquals("gemini-3.8-flash", route?.model)
        assertEquals(Channel.AUTO, route?.via)
    }

    @Test
    fun rankRoutesPutsFailingProviderLast() {
        val healthy = Route("qwen", "qwen-turbo")
        val broken = Route("aihorde", "auto")
        repeat(3) { SmartRouter.record(broken, false, 1_000) }
        SmartRouter.record(healthy, true, 1_000)
        val ranked = CentralBrain.rankRoutes(
            FakePrefs(), TaskKind.GENERAL, listOf(broken, healthy), ChatMode.GENERAL
        )
        assertEquals("qwen", ranked.first().provider)
        assertEquals("aihorde", ranked.last().provider)
    }

    @Test
    fun hubKindReflectsTheActiveChannel() {
        val prefs = manualPrefs()
        // نعيد تحميل إعدادات فارغة حتى لا تتسرّب حالة بوابة من اختبار آخر.
        WebGateway.load(prefs)
        assertEquals(HubCategory.INDEPENDENT, hubKindOf("gemini", prefs, Channel.DIRECT))
        assertEquals(HubCategory.EXTERNAL, hubKindOf("gemini", prefs, Channel.GATEWAY))
        assertEquals(HubCategory.LOCAL, hubKindOf("gemini", prefs, Channel.LOCAL))
    }
}
