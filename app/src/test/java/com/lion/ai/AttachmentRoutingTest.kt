package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentRoutingTest {
    @Test
    fun localProxyPreservesTextAndImageFromMultimodalMessage() {
        val message = JSONObject()
            .put("role", "user")
            .put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", "حلّل الصورة والملف"))
                .put(JSONObject().put("type", "image_url").put(
                    "image_url", JSONObject().put("url", "data:image/png;base64,AAECAw==")
                ))
            )
        val parsed = LocalAIProxy.incomingContent(message)
        assertEquals("حلّل الصورة والملف", parsed.text)
        assertEquals("image/png", parsed.image?.mime)
        assertEquals("AAECAw==", parsed.image?.base64)
    }

    @Test
    fun localProxyStillAcceptsPlainTextMessages() {
        val parsed = LocalAIProxy.incomingContent(
            JSONObject().put("role", "user").put("content", "نص عادي")
        )
        assertEquals("نص عادي", parsed.text)
        assertNull(parsed.image)
    }
}
