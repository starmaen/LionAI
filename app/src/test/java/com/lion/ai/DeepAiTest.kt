package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepAiTest {
    @Test fun defaultModelIsTextToImage() {
        val p = DeepAi.plan("", "قطة على القمر")
        assertEquals("text2img", p.endpoint)
        assertEquals("قطة على القمر", p.fields["text"])
        assertFalse(p.needsImage)
    }

    @Test fun versionSuffixSetsGeneratorVersion() {
        assertEquals("genius", DeepAi.plan("text2img:genius", "x").fields["image_generator_version"])
        assertFalse(DeepAi.plan("text2img", "x").fields.containsKey("image_generator_version"))
    }

    @Test fun imageOnlyModelsNeedAnImageAndSendNoText() {
        val p = DeepAi.plan("colorizer", "https://example.com/a.jpg")
        assertTrue(p.needsImage)
        assertTrue(p.fields.isEmpty())
        assertEquals("https://example.com/a.jpg", DeepAi.imageUrl("لوّنها https://example.com/a.jpg."))
    }

    @Test fun unknownModelIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { DeepAi.plan("gpt-4o", "x") }
    }

    @Test fun outputUrlIsReturnedOnSuccess() {
        assertEquals("https://x/y.jpg", DeepAi.output(200, """{"id":"1","output_url":"https://x/y.jpg"}"""))
    }

    @Test fun unauthorizedExplainsProRequirement() {
        val e = assertThrows(LlmException::class.java) { DeepAi.output(401, """{"err":"bad key"}""") }
        assertEquals(401, e.status)
        assertTrue(e.message.orEmpty().contains("Pro"))
    }

    @Test fun multipartCarriesFieldsAndClosingBoundary() {
        val body = String(DeepAi.multipart("B", mapOf("text" to "hi"), null), Charsets.UTF_8)
        assertTrue(body.contains("name=\"text\"\r\n\r\nhi"))
        assertTrue(body.endsWith("--B--\r\n"))
    }
}
