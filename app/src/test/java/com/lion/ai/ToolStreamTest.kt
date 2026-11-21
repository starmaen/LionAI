package com.lion.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** مُجمّع تدفق tool_calls: يجب أن يعيد نفس رسالة المساعد التي يعيدها الرد غير المتدفق. */
class ToolStreamTest {
    private fun sse(delta: String) = """data: {"choices":[{"index":0,"delta":$delta}]}"""

    private fun feed(vararg lines: String): ToolStreamAccumulator =
        ToolStreamAccumulator().also { acc -> lines.forEach { acc.accept(it) } }

    @Test
    fun assemblesFragmentedArgumentsInOrder() {
        val msg = feed(
            sse("""{"role":"assistant","content":null}"""),
            sse("""{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"file_read","arguments":""}}]}"""),
            sse("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"pa"}}]}"""),
            sse("""{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"a.kt\"}"}}]}"""),
            "data: [DONE]"
        ).toMessage()
        val call = msg.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("c1", call.getString("id"))
        assertEquals("file_read", call.getJSONObject("function").getString("name"))
        assertEquals("a.kt", JSONObject(call.getJSONObject("function").getString("arguments")).getString("path"))
    }

    @Test
    fun keepsTextAndParallelCallsSeparate() {
        val acc = feed(
            sse("""{"content":"سأقرأ "}"""),
            sse("""{"content":"الملفين"}"""),
            sse("""{"tool_calls":[{"index":0,"id":"a","function":{"name":"file_read","arguments":"{\"path\":\"x\"}"}},{"index":1,"id":"b","function":{"name":"file_tree","arguments":"{}"}}]}"""),
            "data: [DONE]"
        )
        val msg = acc.toMessage()
        assertEquals("سأقرأ الملفين", msg.getString("content"))
        val calls = msg.getJSONArray("tool_calls")
        assertEquals(2, calls.length())
        assertEquals("file_read", calls.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("file_tree", calls.getJSONObject(1).getJSONObject("function").getString("name"))
    }

    @Test
    fun toleratesProvidersThatOmitIndexAndRepeatName() {
        val msg = feed(
            sse("""{"tool_calls":[{"id":"a","function":{"name":"file_read","arguments":"{\"path\":"}}]}"""),
            sse("""{"tool_calls":[{"function":{"name":"file_read","arguments":"\"x\"}"}}]}"""),
            sse("""{"tool_calls":[{"id":"b","function":{"name":"file_tree","arguments":"{}"}}]}""")
        ).toMessage()
        val calls = msg.getJSONArray("tool_calls")
        assertEquals(2, calls.length())
        val first = calls.getJSONObject(0).getJSONObject("function")
        assertEquals("file_read", first.getString("name"))
        assertEquals("x", JSONObject(first.getString("arguments")).getString("path"))
        assertEquals("file_tree", calls.getJSONObject(1).getJSONObject("function").getString("name"))
    }

    @Test
    fun plainTextHasNoToolCallsAndIgnoresNoise() {
        val acc = feed(": keep-alive", "", "event: ping", sse("""{"content":"مرحبا"}"""), "data: [DONE]")
        assertEquals("مرحبا", acc.text.toString())
        assertFalse(acc.hasCalls)
        assertFalse(acc.toMessage().has("tool_calls"))
    }

    @Test
    fun emptyStreamIsEmpty_soCallerFallsBackToNormalRequest() {
        val acc = feed(": ping", "data: [DONE]")
        assertTrue(acc.isEmpty)
        assertNull(acc.accept("data: {not json"))
    }

    @Test
    fun nonStringContentAndCallsWithoutNameAreDropped() {
        val msg = feed(
            sse("""{"content":null,"tool_calls":[{"index":0,"id":"z","function":{"arguments":"{}"}}]}""")
        ).toMessage()
        assertFalse(msg.has("tool_calls"))
    }
}
