package com.lion.ai

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * سيناريوهات حيّة بخادم HTTP حقيقي على الجهاز (بلا مفاتيح خارجية): الوكيل الحقيقي + مُحلّل SSE الحقيقي.
 * تغطي: تدفق tool_calls، سقوط المزود وسط المهمة ثم المتابعة بمزود آخر من نقطة الحفظ نفسها دون إعادة تنفيذ،
 * وتأكيد اكتمال المهمة.
 */
class LiveScenarioTest {
    private class Reply(val code: Int, val body: String, val type: String = "application/json", val headers: String = "")

    private class Backend(private val replies: List<Reply>) : AutoCloseable {
        val socket = ServerSocket(0)
        val requests = CopyOnWriteArrayList<String>()
        val url = "http://127.0.0.1:${socket.localPort}/v1/chat/completions"
        private val thread = Thread {
            try {
                replies.forEach { r ->
                    socket.accept().use { client ->
                        val input = client.getInputStream()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val c = input.read()
                            if (c < 0) break
                            header.append(c.toChar())
                        }
                        val length = Regex("(?i)Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                        val bytes = ByteArray(length)
                        var pos = 0
                        while (pos < length) {
                            val n = input.read(bytes, pos, length - pos)
                            if (n < 0) break
                            pos += n
                        }
                        requests.add(String(bytes, Charsets.UTF_8))
                        val data = r.body.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 ${r.code} Test\r\nContent-Type: ${r.type}\r\n${r.headers}" +
                            "Content-Length: ${data.size}\r\nConnection: close\r\n\r\n"
                        client.getOutputStream().write(head.toByteArray() + data)
                    }
                }
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true; it.start() }

        override fun close() {
            socket.close()
            thread.join(2000)
        }
    }

    private fun nativeCall(name: String, args: String = "{}") = JSONObject()
        .put("model", "fixture-model")
        .put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put("role", "assistant").put("content", "")
                        .put(
                            "tool_calls",
                            JSONArray().put(
                                JSONObject().put("id", "call_1").put("type", "function").put(
                                    "function", JSONObject().put("name", name).put("arguments", args)
                                )
                            )
                        )
                )
            )
        ).toString()

    private fun text(t: String) = JSONObject().put("model", "fixture-model").put(
        "choices",
        JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", t)))
    ).toString()

    private fun sse(vararg events: String) = events.joinToString("\n\n", postfix = "\n\n")
    private fun delta(d: String) = """data: {"choices":[{"index":0,"delta":$d}]}"""

    private fun context(): Context {
        val type = Class.forName("sun.misc.Unsafe")
        val field = type.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return type.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), ContextWrapper::class.java) as Context
    }

    private suspend fun execute(session: AgentSession, prefs: FakePrefs, prompt: String, model: String): ToolAgent.Result =
        ToolAgent.run(
            context(), prefs, prompt, "", "custom", model, "", emptyList(), "test system",
            setOf(ToolGroup.META, ToolGroup.UTIL, ToolGroup.FILES), session = session, chatMode = ChatMode.CODE,
            forceExecution = false, userRequest = prompt, timeoutMs = 30000
        )

    private val readTool: JSONArray = JSONArray().put(
        JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", "file_read").put("description", "d")
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))
        )
    )

    private fun <T> withLive(sink: (String) -> Unit, block: () -> T): T {
        LiveStream.sink = { _, t -> sink(t) }
        LiveStream.bind("scenario")
        try {
            return block()
        } finally {
            LiveStream.unbind()
            LiveStream.sink = null
        }
    }

    // ---------- تدفق tool_calls ----------
    @Test
    fun streamsToolCallFragmentsAndAssemblesArguments() {
        val body = sse(
            delta("""{"role":"assistant","content":"سأقرأ الملف"}"""),
            delta("""{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"file_read","arguments":""}}]}"""),
            delta("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]}"""),
            delta("""{"tool_calls":[{"index":0,"function":{"arguments":"\"a.kt\"}"}}]}"""),
            "data: [DONE]"
        )
        Backend(listOf(Reply(200, body, "text/event-stream"))).use { server ->
            val seen = CopyOnWriteArrayList<String>()
            val reply = withLive({ seen.add(it) }) {
                LlmClient.callOpenAiTools(server.url, "", "m", emptyList(), "hi", "sys", readTool)
            }
            assertEquals(1, reply.calls.size)
            assertEquals("file_read", reply.calls[0].name)
            assertEquals("a.kt", reply.calls[0].args.getString("path"))
            assertTrue(seen.isNotEmpty() && seen.last().contains("سأقرأ"))
            assertTrue(JSONObject(server.requests[0]).getBoolean("stream"))
        }
    }

    @Test
    fun serverThatIgnoresStreamStillWorks() {
        Backend(listOf(Reply(200, nativeCall("file_read", """{"path":"b.kt"}""")))).use { server ->
            val reply = withLive({}) { LlmClient.callOpenAiTools(server.url, "", "m", emptyList(), "hi", "sys", readTool) }
            assertEquals("b.kt", reply.calls.single().args.getString("path"))
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun streamCutBeforeDoneWithToolCallIsNotExecutedAndRequestIsRepeatedWhole() {
        // الحجج مبتورة ({"path":"a) فلا تُنفَّذ؛ يُعاد الطلب كاملاً بالمسار العادي ويصل الاستدعاء سليماً.
        val cut = sse(
            delta("""{"tool_calls":[{"index":0,"id":"c1","function":{"name":"file_read","arguments":"{\"path\":\"a"}}]}""")
        )
        Backend(listOf(Reply(200, cut, "text/event-stream"), Reply(200, nativeCall("file_read", """{"path":"full.kt"}""")))).use { server ->
            val reply = withLive({}) { LlmClient.callOpenAiTools(server.url, "", "m", emptyList(), "hi", "sys", readTool) }
            assertEquals("full.kt", reply.calls.single().args.getString("path"))
            assertEquals(2, server.requests.size)
            assertTrue(JSONObject(server.requests[0]).getBoolean("stream"))
            assertFalse(JSONObject(server.requests[1]).has("stream"))
        }
    }

    @Test
    fun rateLimitInStreamFallsBackOnceAndBadKeyIsNotRepeated() {
        Backend(listOf(Reply(429, """{"error":{"message":"slow"}}""", headers = "Retry-After: 0\r\n"), Reply(200, nativeCall("file_read", """{"path":"r.kt"}""")))).use { server ->
            val reply = withLive({}) { LlmClient.callOpenAiTools(server.url, "", "m", emptyList(), "hi", "sys", readTool) }
            assertEquals("r.kt", reply.calls.single().args.getString("path"))
            assertEquals(2, server.requests.size)
        }
        Backend(listOf(Reply(401, """{"error":{"message":"bad key"}}"""))).use { server ->
            try {
                withLive({}) { LlmClient.callOpenAiTools(server.url, "", "m", emptyList(), "hi", "sys", readTool) }
                fail("expected LlmException")
            } catch (e: LlmException) {
                assertEquals(401, e.status)
            }
            assertEquals("مفتاح مرفوض لا يُعاد إرساله", 1, server.requests.size)
        }
    }

    // ---------- تبدّل المزود وسط المهمة ----------
    @Test
    fun providerDiesMidTaskThenAnotherProviderContinuesFromSavedPoint() = runBlocking {
        val prefs = FakePrefs()
        val original = LlmClient.endpointOverrides
        val serviceDown = Reply(503, """{"error":{"message":"outage"}}""")
        Backend(listOf(Reply(200, nativeCall("util_uuid")), serviceDown, serviceDown, serviceDown)).use { a ->
            Backend(listOf(Reply(200, text("اكتملت المهمة: تم فحص المعرّف السابق ولم يُعَد تنفيذه.")))).use { b ->
                try {
                    // المزود A: ينفّذ أداة ثم يسقط.
                    LlmClient.endpointOverrides = mapOf("custom" to a.url)
                    val session = AgentSession("scenario_switch")
                    session.beginTurn("افحص المعرف")
                    val first = execute(session, prefs, "افحص المعرف", "model-a")
                    assertFalse("المهمة لم تكتمل بعد سقوط المزود", first.complete)
                    assertEquals(listOf("util_uuid"), first.tools)
                    val saved = CheckpointStore.load(prefs, "scenario_switch")
                    assertNotNull("يجب حفظ نقطة الاستئناف عند سقوط المزود", saved)
                    val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                        .find(saved!!.continuity.history.joinToString { it.content })?.value
                    assertNotNull("نتيجة الأداة الفعلية محفوظة", uuid)

                    // المزود B (نقطة نهاية ونموذج مختلفان): يتابع من الحفظ ويؤكد الاكتمال.
                    LlmClient.endpointOverrides = mapOf("custom" to b.url)
                    saved.beginTurn("أكمل")
                    val second = execute(saved, prefs, "أكمل", "model-b")
                    assertTrue("المزود البديل يكمل المهمة", second.complete)
                    assertTrue("لا إعادة تنفيذ لأداة نُفّذت", second.tools.isEmpty())
                    assertTrue(second.answer.contains("اكتملت"))
                    val bRequest = JSONObject(b.requests.single())
                    assertEquals("model-b", bRequest.getString("model"))
                    assertTrue("B يستلم نتيجة الأداة المحفوظة", b.requests.single().contains(uuid!!))
                    assertTrue("B يتلقى الأدوات الأصلية", bRequest.has("tools"))
                    assertEquals("A لم يُقصَف بطلبات إضافية", 4, a.requests.size)
                } finally {
                    LlmClient.endpointOverrides = original
                }
            }
        }
    }
}
