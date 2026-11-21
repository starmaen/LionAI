package com.lion.ai

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** tools_schema: group الإلزامي لا يُسقط الاستدعاء بخطأ؛ والقيم الخاطئة الصريحة تبقى خطأً. */
class ToolSchemaGroupTest {
    private fun call(json: String) = ToolCall("tools_schema", JSONObject(json))

    @Test fun validGroupStillPasses() {
        assertNull(AiTools.validateArgs(call("{\"group\":\"files\"}")))
        assertNull(AiTools.validateArgs(call("{\"group\":\"firebase\"}")))
        assertNull(AiTools.validateArgs(call("{\"group\":\"mcp\"}")))
    }

    @Test fun missingGroupIsNotAnError() {
        assertNull(AiTools.validateArgs(call("{}")))
    }

    @Test fun knownToolNameResolvesToItsGroup() {
        assertNull(AiTools.validateArgs(call("{\"tool\":\"file_read\"}")))
    }

    @Test fun explicitlyWrongGroupIsStillRejectedWithAllGroups() {
        val msg = AiTools.validateArgs(call("{\"group\":\"nonsense\"}"))
        assertNotNull(msg)
        assertTrue(msg!!.contains("firebase") && msg.contains("shell") && msg.contains("meta"))
    }

    @Test fun signatureListsEveryGroup() {
        val sig = AiTools.signatures(listOf("tools_schema"))
        ToolGroup.values().forEach { assertTrue(it.id, sig.contains(it.id)) }
        assertFalse(sig.isBlank())
    }
}
