package com.lion.ai

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** Real ToolAgent + HTTP parsing + tool dispatch; synthetic backend, no external credentials. */
class AgentContinuityIntegrationTest {
    private class Backend(private val replies: List<Pair<Int,String>>) : AutoCloseable {
        val socket=ServerSocket(0)
        val requests=CopyOnWriteArrayList<String>()
        val url="http://127.0.0.1:${socket.localPort}/v1/chat/completions"
        private val thread=Thread {
            try { replies.forEach { (code,body) -> socket.accept().use { client ->
                val input=client.getInputStream();val header=StringBuilder()
                while (!header.endsWith("\r\n\r\n")) { val c=input.read();if(c<0) break;header.append(c.toChar()) }
                val length=Regex("(?i)Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                val bytes=ByteArray(length);var pos=0
                while(pos<length){val n=input.read(bytes,pos,length-pos);if(n<0)break;pos+=n}
                requests.add(String(bytes,Charsets.UTF_8))
                val data=body.toByteArray(Charsets.UTF_8)
                client.getOutputStream().write(("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n").toByteArray()+data)
            } } } catch (_: Exception) { }
        }.also { it.isDaemon=true;it.start() }
        override fun close(){socket.close();thread.join(2000)}
    }
    private fun reply(text:String)=JSONObject().put("model","fixture-model").put("choices",org.json.JSONArray().put(JSONObject().put("message",JSONObject().put("role","assistant").put("content",text)))).toString()
    private fun context():Context {
        // Tools exercised here never access Android services or the filesystem.
        val type=Class.forName("sun.misc.Unsafe")
        val field=type.getDeclaredField("theUnsafe");field.isAccessible=true
        return type.getMethod("allocateInstance",Class::class.java).invoke(field.get(null),ContextWrapper::class.java) as Context
    }
    private suspend fun execute(session:AgentSession,prefs:FakePrefs,prompt:String):ToolAgent.Result = ToolAgent.run(
        context(),prefs,prompt,"","custom","fixture-model","",emptyList(),"test system",
        setOf(ToolGroup.META,ToolGroup.UTIL,ToolGroup.FILES),session=session,chatMode=ChatMode.CODE,
        forceExecution=false,userRequest=prompt,timeoutMs=30000)

    @Test fun serverFailureThenRestartPreservesActualToolOutputWithoutReexecutingTool()=runBlocking {
        val prefs=FakePrefs();val original=LlmClient.endpointOverrides
        Backend(listOf(200 to reply("[[TOOL]]{\"name\":\"util_uuid\",\"args\":{}}[[/TOOL]]"),503 to "{\"error\":{\"message\":\"test outage\"}}",
                        503 to "{\"error\":{\"message\":\"test outage\"}}",
            503 to "{\"error\":{\"message\":\"test outage\"}}",
            200 to reply("النتيجة السابقة محفوظة؛ اكتمل الفحص."))).use { server ->
            try {
                LlmClient.endpointOverrides=mapOf("custom" to server.url)
                val session=AgentSession("integration_a");session.beginTurn("افحص المعرف")
                val first=execute(session,prefs,"افحص المعرف")
                assertFalse(first.complete);assertTrue(first.responded);assertEquals(listOf("util_uuid"),first.tools)
                val saved=CheckpointStore.load(prefs,"integration_a")!!
                val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").find(saved.continuity.history.joinToString { it.content })!!.value
                saved.beginTurn("أكمل")
                val second=execute(saved,prefs,"أكمل")
                assertTrue(second.complete);assertTrue(second.tools.isEmpty())
                                // 1) طلب الأداة، 2-4) ثلاث محاولات على 503 (محاولة + إعادتان)، 5) طلب الاستئناف الناجح.
                assertEquals(5,server.requests.size)
                assertTrue(server.requests.last().contains(uuid))
                assertTrue(server.requests.all { JSONObject(it).optString("model")=="fixture-model" })
            } finally { LlmClient.endpointOverrides=original }
        }
    }
    @Test fun modelCannotGrantItselfWritePermissionDuringReadTask()=runBlocking {
        val prefs=FakePrefs();val original=LlmClient.endpointOverrides
        Backend(listOf(200 to reply("[[TOOL]]{\"name\":\"file_write\",\"args\":{\"path\":\"forbidden.txt\",\"content\":\"x\"}}[[/TOOL]]"),
            200 to reply("لم أنفذ تعديلاً لأن الطلب للفحص فقط."))).use { server ->
            try {
                LlmClient.endpointOverrides=mapOf("custom" to server.url)
                val session=AgentSession("integration_read");session.beginTurn("افحص الملفات فقط")
                execute(session,prefs,"افحص الملفات فقط")
                assertEquals(2,server.requests.size)
                assertTrue(server.requests.last().contains("لا يوجد إذن صريح"))
                assertEquals(0,session.continuity.json().getJSONObject("receipts").length())
            } finally { LlmClient.endpointOverrides=original }
        }
    }
}
