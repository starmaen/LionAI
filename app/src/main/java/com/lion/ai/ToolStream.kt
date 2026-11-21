package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap

/**
 * يجمّع دلتا SSE لمزودات OpenAI-compatible (نص + tool_calls) في رسالة واحدة بنفس شكل الرد غير المتدفق،
 * فيعيد مسار الأدوات استخدام مُحلّله الحالي كما هو ([parseToolMessage]) دون منطق موازٍ.
 *
 * يتحمّل اختلافات المزودات: غياب `index` (نميّز الاستدعاءات بـ`id`)، وتكرار `name` في كل شظية،
 * وتجزئة `arguments` على عدة شظايا، وأسطر التعليقات/الفارغة/[DONE].
 */
internal class ToolStreamAccumulator {
    private class Partial {
        var id = ""
        var name = ""
        val args = StringBuilder()
    }

    val text = StringBuilder()
    private val calls = TreeMap<Int, Partial>()
    private var lastIndex = -1

    private fun payload(line: String): String? {
        val t = line.trim()
        return if (t.startsWith("data:")) t.removePrefix("data:").trim() else null
    }

    /** يغذّي سطر SSE. يرجع النص الجديد في هذا السطر (للبث الحي) أو null. */
    fun accept(line: String): String? {
        val p = payload(line) ?: return null
        if (p.isEmpty() || p == "[DONE]") return null
        val delta = runCatching { JSONObject(p) }.getOrNull()
            ?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return null
        val rawCalls = delta.optJSONArray("tool_calls")
        if (rawCalls != null) {
            for (i in 0 until rawCalls.length()) rawCalls.optJSONObject(i)?.let { addCall(it) }
        }
        val piece = (delta.opt("content") as? String)?.takeIf { it.isNotEmpty() }
        if (piece != null) text.append(piece)
        return piece
    }

    private fun addCall(call: JSONObject) {
        val id = call.optString("id", "")
        val index = when {
            call.has("index") -> call.optInt("index", 0)
            id.isNotEmpty() && calls.values.none { it.id == id } -> lastIndex + 1
            lastIndex >= 0 -> lastIndex
            else -> 0
        }
        lastIndex = index
        val partial = calls.getOrPut(index) { Partial() }
        if (id.isNotEmpty() && partial.id.isEmpty()) partial.id = id
        val fn = call.optJSONObject("function") ?: return
        val name = fn.optString("name", "")
        // الاسم يصل عادة مرة واحدة؛ بعض المزودات تكرره في كل شظية فلا نلصقه.
        if (name.isNotEmpty() && !partial.name.endsWith(name)) partial.name += name
        val args = fn.opt("arguments")
        if (args is String) partial.args.append(args)
        else if (args is JSONObject) partial.args.append(args.toString())
    }

    val hasCalls: Boolean get() = calls.values.any { it.name.isNotBlank() }
    val isEmpty: Boolean get() = text.isBlank() && !hasCalls

    /** رسالة بنفس شكل choices[0].message غير المتدفق. */
    fun toMessage(): JSONObject {
        val message = JSONObject().put("role", "assistant").put("content", text.toString())
        val arr = JSONArray()
        calls.values.filter { it.name.isNotBlank() }.forEach { c ->
            arr.put(
                JSONObject().put("id", c.id).put("type", "function").put(
                    "function",
                    JSONObject().put("name", c.name).put("arguments", c.args.toString())
                )
            )
        }
        if (arr.length() > 0) message.put("tool_calls", arr)
        return message
    }
}

/** رسالة المساعد (غير المتدفقة أو المجمّعة) → ToolReply. نفس المنطق الذي كان داخل callOpenAiTools. */
internal fun parseToolMessage(reply: JSONObject?): LlmClient.ToolReply {
    val text = reply?.optString("content", "").orEmpty()
        .takeIf { it.isNotBlank() && it != "null" }.orEmpty()
    val calls = mutableListOf<ToolCall>()
    val rawCalls = reply?.optJSONArray("tool_calls")
    if (rawCalls != null) {
        for (index in 0 until rawCalls.length()) {
            val fn = rawCalls.optJSONObject(index)?.optJSONObject("function") ?: continue
            val name = fn.optString("name").trim()
            if (name.isBlank()) continue
            val args = ToolCallCodec.extractArgs(fn, fn, fn.opt("arguments"))
            // أسماء مثل read/write/bash (أسلوب Claude Code) تُترجم هنا، خارج حلقة الوكيل الضخمة.
            val canonical = ToolAliases.resolve(name, AiTools.knownToolNames()).ifEmpty { name }
            calls.add(ToolCall(canonical, args, fn.toString().take(2000)))
        }
    }
    return LlmClient.ToolReply(text, calls)
}

/**
 * طلب أدوات بصيغة البث. يرجع رسالة المساعد المجمّعة، أو null ليعود الطلب للمسار العادي
 * (HTTP خاطئ، انقطاع قبل أي محتوى، رد فارغ). أخطاء المفتاح/الرصيد تُرمى مباشرة بلا طلب ثانٍ.
 * انقطاع وسط الاستدعاء (JSON حجج مبتور) لا يُنفَّذ: نرجع null فيُعاد الطلب كاملاً بدل تنفيذ حجج ناقصة.
 */
internal fun LlmClient.streamToolsChat(
    url: String,
    headers: Map<String, String>,
    body: JSONObject,
    chatId: String
): JSONObject? {
    body.put("stream", true)
    throttleFor(url)
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.apply {
            requestMethod = "POST"
            connectTimeout = LLM_CONNECT_TIMEOUT
            readTimeout = currentReadTimeout()
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("User-Agent", LLM_USER_AGENT)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        if (status !in 200..299) return handleStreamHttpError(connection, status)
        val type = connection.contentType.orEmpty().lowercase()
        if (!type.contains("event-stream")) {
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return runCatching { JSONObject(raw) }.getOrNull()
                ?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        }
        readToolStream(connection, chatId)
    } catch (e: java.io.InterruptedIOException) {
        throw e
    } catch (e: java.io.IOException) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun readToolStream(connection: HttpURLConnection, chatId: String): JSONObject? {
    val acc = ToolStreamAccumulator()
    var lastEmit = 0L
    var finished = false
    connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("cancelled")
            val line = reader.readLine() ?: break
            if (LiveStream.isDone(line)) { finished = true; break }
            if (acc.accept(line) == null) continue
            val now = System.currentTimeMillis()
            if (now - lastEmit >= 70) {
                lastEmit = now
                LiveStream.emit(chatId, acc.text.toString())
            }
        }
    }
    // انقطع البث قبل [DONE] وفيه استدعاء أداة: حججه قد تكون مبتورة، فلا نخاطر بتنفيذها.
    if (!finished && acc.hasCalls) return null
    if (acc.text.isNotBlank()) LiveStream.emit(chatId, acc.text.toString())
    return if (acc.isEmpty) null else acc.toMessage()
}

/** يشترك فيه البث العادي والأدوات: مفتاح/رصيد يُرمى، وحد/عطل عابر ينتظر Retry-After ثم يرجع null. */
internal fun handleStreamHttpError(connection: HttpURLConnection, status: Int): JSONObject? {
    val raw = runCatching {
        connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.getOrNull().orEmpty()
    if (status in 401..403) {
        throw LlmException(status, LlmClient.errorMessage(runCatching { JSONObject(raw) }.getOrNull(), raw))
    }
    if (status == 429 || status in 500..599) {
        val wait = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000) ?: 2_000L
        Thread.sleep(wait.coerceIn(500L, 15_000L))
    }
    return null
}
