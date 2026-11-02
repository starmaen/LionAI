package com.lion.ai

import org.junit.Assert.*
import org.junit.Test

class ContinuityRoutingTest {
    private fun prefs() = FakePrefs().also { it.edit().putBoolean("auto_route",true)
        .putString("provider","gemini").putString("api_key_openai","test-key")
        .putString("api_key_gemini","test-key").apply() }
    @Test fun healthyProviderAndModelRemainPinnedAcrossTaskTypesAndCompletion() {
        val p=prefs();ProviderStick.set(p,"chatA","openai","pinned-model")
        val ranked=listOf(Route("gemini","new-choice"),Route("openai","different-model"))
        for (kind in listOf(TaskKind.DEV,TaskKind.GENERAL,TaskKind.FAST,TaskKind.LONG_DOC)) {
            val route=RoutePolicy.applySticky(p,kind,"chatA",ranked).first()
            assertEquals("openai",route.provider);assertEquals("pinned-model",route.model)
        }
        AgentSession("chatA").markCompleted()
        assertEquals("openai",ProviderStick.get(p,"chatA"))
    }
    @Test fun pinsArePerConversationNotGlobalActiveChat() {
        val p=prefs();ProviderStick.set(p,"chatA","openai","a")
        assertNull(ProviderStick.get(p,"chatB"))
    }
    @Test fun explicitUserProviderChangeReleasesPin() {
        val p=prefs();ProviderStick.set(p,"chatA","openai","a")
        p.edit().putString("provider","deepseek").apply()
        assertNull(ProviderStick.get(p,"chatA"))
    }
    @Test fun anExplicitModelSettingOverridesSavedModel() {
        val p=prefs();ProviderStick.set(p,"chatA","openai","old")
        p.edit().putString("model_openai","user-selected").apply()
        assertEquals("user-selected",RoutePolicy.applySticky(p,TaskKind.DEV,"chatA",listOf(Route("openai","old"))).first().model)
    }
    @Test fun hiddenMutationPathsAreClassifiedBeforeExecution() {
        assertTrue(AiTools.isMutation(ToolCall("web_api",org.json.JSONObject().put("method","POST"),"")))
        assertFalse(AiTools.isMutation(ToolCall("web_api",org.json.JSONObject().put("method","GET"),"")))
        assertTrue(AiTools.isMutation(ToolCall("db_query",org.json.JSONObject(),"")))
        assertTrue(AiTools.isMutation(ToolCall("mcp_call",org.json.JSONObject(),"")))
        assertFalse(AiTools.isMutation(ToolCall("github_read_file",org.json.JSONObject(),"")))
    }
}
