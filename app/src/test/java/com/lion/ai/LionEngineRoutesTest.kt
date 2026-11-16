package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات منطق Lion-1 المدمج: الكتالوج، توجيه المهام، وترتيب المسارات.
 * كل ما يُختبر هنا دالّات صرفة لا تحتاج سياق أندرويد أو محرّكاً شغّالاً.
 */
class LionEngineRoutesTest {

    @Test
    fun catalogHasDefaultModelWithRealGgufMetadata() {
        val model = LionCatalog.find(null)
        assertEquals(LionCatalog.DEFAULT_ID, model.id)
        assertTrue(model.url.startsWith("https://"))
        assertTrue(model.file.endsWith(".gguf"))
        assertTrue(model.bytes > 100L * 1024 * 1024)
        assertEquals(1, LionCatalog.MODELS.count { it.id == LionCatalog.DEFAULT_ID })
    }

    @Test
    fun unknownModelIdFallsBackToDefault() {
        assertEquals(LionCatalog.DEFAULT_ID, LionCatalog.find("لا-يوجد").id)
        assertEquals("lion-1-base", LionCatalog.find("lion-1-base").id)
    }

    @Test
    fun engineIsAlwaysTheFirstRouteWhenAvailable() {
        val routes = LionOne.buildRoutes("", emptyList(), engineAvailable = true)
        assertEquals("engine", routes.first().key)
        assertEquals(LionEngine.CHAT_URL, routes.first().url)
    }

    @Test
    fun localServerRouteExistsWhenEngineIsUnavailableAndIsNotDuplicated() {
        val routes = LionOne.buildRoutes("", emptyList(), engineAvailable = false)
        assertEquals("local", routes.first().key)
        assertEquals(1, routes.count { it.url == LionOne.LOCAL_URL })
    }

    @Test
    fun userEndpointComesBeforeGatewaysAndDuplicatedLocalIsCollapsed() {
        val gateway = LionOne.Route("gw:https://x/v1", "https://x/v1/chat/completions", "t", "auto", "البوابة")
        val routes = LionOne.buildRoutes("http://192.168.1.7:8090/v1", listOf(gateway), engineAvailable = true)
        val keys = routes.map { it.key }
        // المحرّك يشارك الخادم المحلي المنفذ نفسه (44045)، فلا يُكرَّر مسار «local».
        assertEquals(listOf("engine", "user", "gw:https://x/v1"), keys)
        assertTrue(routes.first { it.key == "user" }.url.endsWith("/chat/completions"))
    }

    @Test
    fun unsafeUserEndpointIsIgnoredButEngineStillRuns() {
        val routes = LionOne.buildRoutes("ftp://example.com/v1", emptyList(), engineAvailable = true)
        assertFalse(routes.any { it.key == "user" })
        assertEquals("engine", routes.first().key)
    }

    @Test
    fun recommendationFollowsRealRamNotVirtualRam() {
        // هواتف 4GB (مثل Tecno Spark 8 Pro): Mini بسياق 4096 — الرام الافتراضية لا تُحسب.
        val fourGb = LionEngine.recommendFor(3_800L)
        assertEquals("lion-1-mini", fourGb.modelId)
        assertEquals(4096, fourGb.ctx)
        // 6–8GB: سياق أوسع بالنموذج نفسه.
        assertEquals(8192, LionEngine.recommendFor(6_000L).ctx)
        assertEquals("lion-1-mini", LionEngine.recommendFor(8_000L).modelId)
        // 12GB+: النموذج 3B.
        assertEquals("lion-1-base", LionEngine.recommendFor(12_000L).modelId)
        // 3GB أو أقل: النموذج الصغير.
        assertEquals("lion-1-nano", LionEngine.recommendFor(2_500L).modelId)
        // قياس غير متاح: افتراضي آمن لا ينهار.
        assertEquals(LionCatalog.DEFAULT_ID, LionEngine.recommendFor(0L).modelId)
    }

    @Test
    fun everyRecommendedModelExistsInCatalog() {
        listOf(2_000L, 4_000L, 8_000L, 16_000L).forEach { ram ->
            val pick = LionEngine.recommendFor(ram)
            assertTrue(pick.modelId, LionCatalog.MODELS.any { it.id == pick.modelId })
            assertTrue(pick.ctx in LionEngine.CTX_CHOICES)
        }
    }

    @Test
    fun memoryRequirementGrowsWithModelAndContext() {
        val mini = LionCatalog.MODELS.first { it.id == "lion-1-mini" }
        val nano = LionCatalog.MODELS.first { it.id == "lion-1-nano" }
        assertTrue(LionEngine.neededRamMb(nano.bytes, 4096) < LionEngine.neededRamMb(mini.bytes, 4096))
        assertTrue(LionEngine.neededRamMb(mini.bytes, 4096) < LionEngine.neededRamMb(mini.bytes, 8192))
        // 1.5B بسياق 4096 يجب أن يبقى داخل هاتف 4GB مع Alpine.
        val need = LionEngine.neededRamMb(mini.bytes, 4096)
        assertTrue("الحاجة $need MB", need in 1_200L..2_000L)
    }

    @Test
    fun forcedModelSelectsTheTask() {
        assertEquals(LionOne.Task.CODE, LionOne.task("lion-1-code", "مرحباً"))
        assertEquals(LionOne.Task.TRANSLATE, LionOne.task("lion-1-translate", "أي نص"))
        assertEquals(LionOne.Task.SUMMARY, LionOne.task("lion-1-summary", ""))
    }

    @Test
    fun freeTextSelectsTaskByIntent() {
        assertEquals(LionOne.Task.CODE, LionOne.task("lion-1", "أصلح هذا الخطأ في Kotlin"))
        assertEquals(LionOne.Task.TRANSLATE, LionOne.task("lion-1", "ترجم هذه الفقرة"))
        assertEquals(LionOne.Task.GENERAL, LionOne.task("lion-1", "كيف حالك اليوم؟"))
    }

    @Test
    fun identityIsInjectedInBothPromptModes() {
        val plain = LionOne.systemFor(LLM_SYSTEM_PROMPT, LionOne.Task.GENERAL)
        assertTrue(plain.contains("Lion-1"))
        assertTrue(plain.contains(LionOne.Task.GENERAL.prompt))

        // تعليمات الوكيل (أدوات) لا تُلوَّث بتعليمات المهمة، لكن الهوية تبقى.
        val agent = LionOne.systemFor("تعليمات وكيل الأدوات", LionOne.Task.CODE)
        assertTrue(agent.contains("Lion-1"))
        assertFalse(agent.contains(LionOne.Task.CODE.prompt))
    }

    @Test
    fun failedRouteIsDeferredNotDropped() {
        LionOne.resetHealth()
        val routes = LionOne.buildRoutes("", emptyList(), engineAvailable = true)
        val engine = routes.first { it.key == "engine" }
        LionOne.markBad(engine)
        val reordered = LionOne.order(routes)
        assertEquals(routes.size, reordered.size)
        assertTrue(reordered.last().key == "engine")
        LionOne.resetHealth()
    }
    @Test
    fun remoteGatewayWithTokenIsPreferredOverLocalEngine() {
        LionOne.resetHealth()
        val gw = LionOne.Route("gw:https://x/v1", "https://x/v1/chat/completions", "tok", "auto", "البوابة")
        val routes = LionOne.buildRoutes("", listOf(gw), engineAvailable = true)
        val out = LionOne.preferRemote(LionOne.order(routes))
        assertEquals("gw:https://x/v1", out.first().key)
        assertTrue(out.any { it.key == "engine" })
        assertEquals(routes.size, out.size)
    }

    @Test
    fun gatewayWithoutTokenOrCoolingDoesNotJumpTheQueue() {
        LionOne.resetHealth()
        val noToken = LionOne.Route("gw:https://x/v1", "https://x/v1/chat/completions", "", "auto", "البوابة")
        val a = LionOne.preferRemote(LionOne.order(LionOne.buildRoutes("", listOf(noToken), engineAvailable = true)))
        assertEquals("engine", a.first().key)

        val gw = LionOne.Route("gw:https://y/v1", "https://y/v1/chat/completions", "tok", "auto", "البوابة")
        LionOne.markBad(gw)
        val b = LionOne.preferRemote(LionOne.order(LionOne.buildRoutes("", listOf(gw), engineAvailable = true)))
        assertEquals("engine", b.first().key)
        LionOne.resetHealth()
    }
}
