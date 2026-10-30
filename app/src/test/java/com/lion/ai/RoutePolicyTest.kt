package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات سياسة التوجيه المشتركة بين شاشة المحادثة والخادم المحلي. */
class RoutePolicyTest {

    private val chosen = Route("openai", "gpt-6-luna")
    private val central = listOf(
        Route("gemini", "gemini-3.8-flash"),
        Route("groq", "openai/gpt-oss-120b")
    )
    private val rescue = listOf(
        Route("deepseek", "deepseek-flash"),
        Route("gemini", "gemini-3.8-flash")
    )

    @Test
    fun manualWithoutAssistKeepsOnlyChosenProvider() {
        val plan = RoutePolicy.combine(
            manual = true,
            assist = false,
            chosen = chosen,
            central = central,
            rescue = rescue
        )
        assertEquals(listOf(chosen), plan)
    }

    @Test
    fun manualWithAssistStartsWithChosenThenAlternatives() {
        val plan = RoutePolicy.combine(
            manual = true,
            assist = true,
            chosen = chosen,
            central = central,
            rescue = rescue
        )
        assertEquals("openai", plan.first().provider)
        assertTrue(plan.any { it.provider == "gemini" })
        assertTrue(plan.any { it.provider == "deepseek" })
        assertEquals(plan.map { it.provider }.distinct().size, plan.size)
    }

    @Test
    fun automaticUsesCentralOrderThenRescue() {
        val plan = RoutePolicy.combine(
            manual = false,
            assist = false,
            chosen = chosen,
            central = central,
            rescue = rescue
        )
        assertEquals(listOf("gemini", "groq", "deepseek"), plan.map { it.provider })
    }

    @Test
    fun automaticFallsBackToChosenWhenCentralIsEmpty() {
        val plan = RoutePolicy.combine(
            manual = false,
            assist = false,
            chosen = chosen,
            central = emptyList(),
            rescue = emptyList()
        )
        assertEquals(listOf(chosen), plan)
    }

    @Test
    fun importedProviderKeepsItsOwnIdentity() {
        val info = providerInfo("imp_demo")
        assertEquals("imp_demo", info.id)
        assertTrue(info.keyless)
    }
}
