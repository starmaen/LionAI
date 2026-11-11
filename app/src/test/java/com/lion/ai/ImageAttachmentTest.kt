package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageAttachmentTest {
    private fun img(n: String) = ImageAttachment(n, "image/png", "QUJD")

    @Test fun mergeKeepsOrderAndFlattens() {
        val merged = ImageAttachment.merge(listOf(img("a"), ImageAttachment.merge(listOf(img("b"), img("c")))!!))!!
        assertEquals(listOf("a", "b", "c"), merged.all().map { it.name })
        assertEquals("a، b، c", merged.displayName())
    }

    @Test fun mergeCapsAtMaxImages() {
        val many = (1..10).map { img("i$it") }
        assertEquals(ImageAttachment.MAX_IMAGES, ImageAttachment.merge(many)!!.all().size)
    }

    @Test fun mergeOfEmptyIsNull() {
        assertNull(ImageAttachment.merge(emptyList()))
    }

    @Test fun singleImageAllIsItself() {
        assertEquals(listOf("x"), img("x").all().map { it.name })
    }
}
