package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMethodologyTest {
    private fun call(name: String, json: String = "{}") = ToolCall(name, JSONObject(json), "")

    // ---- الدمج بطلب المستخدم فقط
    @Test fun mergeNeedsExplicitUserRequest() {
        val risk = RiskPolicy.assess(call("github_merge_pr", """{"number":7}"""))
        assertNotNull(risk)
        assertFalse(RiskPolicy.requestNames(risk!!, "أصلح الخطأ في المشروع وارفعه"))
        assertTrue(RiskPolicy.requestNames(risk, "ادمج الـ PR بعد نجاح البناء"))
        assertTrue(RiskPolicy.requestNames(risk, "merge it when green"))
    }

    // ---- مسارات الاستدعاء الأصلي مفتوحة لكل مزود API، مجانياً أو مدفوعاً، والنص يبقى للبقية
    @Test fun nativeRoutesCoverFreeAndPaidApiProviders() {
        assertNotNull(NativeToolRoutes.route("groq"))
        assertNotNull(NativeToolRoutes.route("openrouter"))
        assertNotNull(NativeToolRoutes.route("deepseek"))
        // OpenAI: Function Calling الأصلي مفعّل (max_completion_tokens بلا temperature) مع رجوع تلقائي للنص عند الرفض.
        assertNotNull(NativeToolRoutes.route("openai"))
        listOf("gemini", "claude", "cohere", "custom", "local_proxy").forEach {
            assertNull("$it must stay on the text protocol", NativeToolRoutes.route(it))
        }
    }

    @Test fun webProvidersNeverUseNativeRoute() {
        PROVIDERS.filter { it.webviewUrl != null }.forEach {
            assertNull("${it.id} is a web provider", NativeToolRoutes.route(it.id))
        }
    }

    @Test fun brokenNativeIsRememberedPerProviderAndModel() {
        NativeToolRoutes.resetForTests()
        assertFalse(NativeToolRoutes.isBroken("groq", "m1"))
        NativeToolRoutes.markBroken("groq", "m1")
        assertTrue(NativeToolRoutes.isBroken("groq", "m1"))
        assertFalse(NativeToolRoutes.isBroken("groq", "m2"))
        assertTrue(400 in NativeToolRoutes.UNSUPPORTED_STATUS && 422 in NativeToolRoutes.UNSUPPORTED_STATUS)
        assertFalse(429 in NativeToolRoutes.UNSUPPORTED_STATUS)
        NativeToolRoutes.resetForTests()
    }

    // ---- قراءات متوازية: القراءة فقط
    @Test fun onlyReadOnlyToolsRunInParallel() {
        val calls = listOf(call("file_read", """{"path":"a"}"""), call("file_tree"), call("file_write", """{"path":"b","content":"x"}"""), call("github_merge_pr", """{"number":1}"""))
        assertEquals(listOf("file_read", "file_tree"), ToolAgent.parallelReads(calls).map { it.name })
    }

    // ---- خطة المهام
    @Test fun todoPlanParsesAndRenders() {
        val items = JSONArray()
            .put(JSONObject().put("text", "قراءة الملف").put("status", "done"))
            .put(JSONObject().put("text", "التعديل").put("status", "in_progress"))
            .put("التحقق")
        val list = TodoPlan.parse(items)
        assertEquals(listOf("done", "in_progress", "pending"), list.map { it.status })
        val text = TodoPlan.render(list)
        assertTrue(text.contains("1/3") && text.contains("✅ قراءة الملف") && text.contains("🔄 التعديل") && text.contains("⬜ التحقق"))
    }

    @Test fun todoPlanRejectsEmptyAndPersists() {
        var failed = false
        try { TodoPlan.parse(JSONArray()) } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
        val prefs = FakePrefs()
        TodoPlan.save(prefs, "c1", listOf(TodoPlan.Item("x", "done")))
        assertEquals(listOf(TodoPlan.Item("x", "done")), TodoPlan.load(prefs, "c1"))
        assertTrue(TodoPlan.load(prefs, "other").isEmpty())
    }

    @Test fun todoToolIsInLeanSetAndMetaGroup() {
        assertTrue("todo_write" in ToolAgent.LEAN_TOOLS)
        val spec = AiTools.spec(setOf(ToolGroup.META), FakePrefs())
        assertTrue(spec.contains("todo_write"))
    }

    // ---- البوابة: أفعال بصياغات عملية تفتح الأدوات في وضع المطوّر دون ابتلاع النص العادي
    @Test fun dialectEditVerbsOpenToolsInCodeMode() {
        assertTrue(AgentGate.shouldUseAgent("أضف شاشة إعدادات للتطبيق", false, ChatMode.CODE))
        assertTrue(AgentGate.shouldUseAgent("فعّل الوضع الليلي في التطبيق", false, ChatMode.CODE))
        for (mode in ChatMode.values()) {
            assertFalse(AgentGate.shouldUseAgent("اكتب لي رسالة شكر لصديقي", false, mode))
            assertFalse(AgentGate.shouldUseAgent("مرحبا", false, mode))
        }
    }
}
