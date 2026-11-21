package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamTest {
    @Test
    fun parsesContentDelta() {
        val line = """data: {"choices":[{"delta":{"content":"مرحبا"}}]}"""
        assertEquals("مرحبا", LiveStream.parseLine(line))
        assertEquals("x", LiveStream.parseLine("""data:{"choices":[{"delta":{"content":"x"}}]}"""))
    }

    @Test
    fun ignoresNonTextEvents() {
        assertNull(LiveStream.parseLine("data: [DONE]"))
        assertNull(LiveStream.parseLine(""))
        assertNull(LiveStream.parseLine(": keep-alive"))
        assertNull(LiveStream.parseLine("data: not json"))
        assertNull(LiveStream.parseLine("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertNull(LiveStream.parseLine("""data: {"choices":[{"delta":{"content":null}}]}"""))
        assertNull(LiveStream.parseLine("""data: {"choices":[{"delta":{"content":""}}]}"""))
        assertNull(LiveStream.parseLine("""data: {"choices":[{"delta":{"reasoning_content":"فكرة"}}]}"""))
    }

    @Test
    fun parsesGeminiStreamLines() {
        val line = """data: {"candidates":[{"content":{"parts":[{"text":"مرحبا "},{"text":"بك"}]}}]}"""
        assertEquals("مرحبا بك", LiveStream.parseGeminiLine(line))
        assertNull(LiveStream.parseGeminiLine("""data: {"candidates":[{"content":{"parts":[{"text":"فكرة","thought":true}]}}]}"""))
        assertNull(LiveStream.parseGeminiLine(""))
        assertNull(LiveStream.parseGeminiLine("data: not json"))
        assertNull(LiveStream.parseGeminiLine("""data: {"candidates":[{"finishReason":"STOP"}]}"""))
    }

    @Test
    fun detectsDoneMarker() {
        assertTrue(LiveStream.isDone("data: [DONE]"))
        assertTrue(LiveStream.isDone("  data:[DONE]  "))
        assertFalse(LiveStream.isDone("""data: {"choices":[]}"""))
        assertFalse(LiveStream.isDone("[DONE]"))
    }

    @Test
    fun activeOnlyWhenBoundAndListening() {
        val saved = LiveStream.sink
        try {
            LiveStream.enabled = true
            LiveStream.sink = { _, _ -> }
            LiveStream.bind("chat1")
            assertEquals("chat1", LiveStream.active())
            LiveStream.bind("")
            assertNull(LiveStream.active())
            LiveStream.bind("chat1")
            LiveStream.enabled = false
            assertNull(LiveStream.active())
            LiveStream.enabled = true
            LiveStream.sink = null
            assertNull(LiveStream.active())
        } finally {
            LiveStream.enabled = true
            LiveStream.sink = saved
            LiveStream.unbind()
        }
    }
}
