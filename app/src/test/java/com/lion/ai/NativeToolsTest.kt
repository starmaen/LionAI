package com.lion.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeToolsTest {

    @Test
    fun buildsJsonSchemaFromSignature() {
        val props = AiTools.schemaFrom(
            """{"path":"src/Main.kt","lines":10,"ratio":0.5,"deep":true,"tags":["a"],"opts":{"x":1}}"""
        )
        assertEquals("string", props.getJSONObject("path").getString("type"))
        assertEquals("number", props.getJSONObject("lines").getString("type"))
        assertEquals("number", props.getJSONObject("ratio").getString("type"))
        assertEquals("boolean", props.getJSONObject("deep").getString("type"))
        assertEquals("array", props.getJSONObject("tags").getString("type"))
        assertEquals("object", props.getJSONObject("opts").getString("type"))
    }

    @Test
    fun emptySignatureYieldsEmptyProperties() {
        assertEquals(0, AiTools.schemaFrom("{}").length())
        assertEquals(0, AiTools.schemaFrom("").length())
        assertEquals(0, JSONObject("{}").length())
    }
}
