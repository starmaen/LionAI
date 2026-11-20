package com.lion.ai

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * بث حي لردود مزودات API المتوافقة مع OpenAI (SSE).
 * النص يصل إلى الواجهة تدريجياً عبر [sink]، والنتيجة النهائية تعود كاملة كما كانت،
 * فلا يتغير بروتوكول الأدوات ولا أي منطق يعتمد على الرد الكامل.
 * أي إخفاق قبل وصول نص صالح يرجع بـnull فيعود الطلب إلى المسار العادي (مع إعادة المحاولة والأخطاء).
 */
internal object LiveStream {
    @Volatile var enabled: Boolean = true
    @Volatile var sink: ((chatId: String, text: String) -> Unit)? = null
    private val chat = ThreadLocal<String?>()

    fun bind(chatId: String) { chat.set(chatId.takeIf { it.isNotBlank() }) }
    fun unbind() { chat.remove() }

    /** معرّف المحادثة إن كان البث مفعّلاً وهناك من يستمع، وإلا null. */
    fun active(): String? = if (enabled && sink != null) chat.get() else null

    fun emit(chatId: String, text: String) { sink?.invoke(chatId, text) }

    private fun payload(line: String): String? {
        val t = line.trim()
        return if (t.startsWith("data:")) t.removePrefix("data:").trim() else null
    }

    fun isDone(line: String): Boolean = payload(line) == "[DONE]"

    /** نص الدلتا من سطر SSE واحد، أو null إن لم يحمل نصاً (فارغ/DONE/تعليق/محتوى null). */
    fun parseLine(line: String): String? {
        val p = payload(line) ?: return null
        if (p.isEmpty() || p == "[DONE]") return null
        val delta = runCatching { JSONObject(p) }.getOrNull()
            ?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return null
        return (delta.opt("content") as? String)?.takeIf { it.isNotEmpty() }
    }
}

/**
 * يطلب الرد بصيغة البث ويدفع النص المتراكم إلى [LiveStream.emit].
 * يرجع null عند أي عطل قبل نص صالح (حالة HTTP خاطئة، انقطاع شبكة، رد فارغ) ليتولى المسار العادي الأمر.
 * مهلة القراءة وانقطاع الخيط تُرفع كما هي ليتصرف الإلغاء كالمعتاد.
 */
internal fun LlmClient.streamChat(
    url: String,
    headers: Map<String, String>,
    body: JSONObject,
    chatId: String
): String? {
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
        if (status !in 200..299) {
            val raw = runCatching {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull().orEmpty()
            // مفتاح/رصيد/صلاحية: سيعيد المسار العادي الخطأ نفسه، فنرميه الآن بدل طلب ثانٍ مهدور.
            if (status in 401..403) {
                throw LlmException(status, LlmClient.errorMessage(runCatching { JSONObject(raw) }.getOrNull(), raw))
            }
            // حد معدّل/عطل عابر: المسار العادي سيعيد المحاولة، فنمنحه مهلة (Retry-After) بدل قصفه فوراً.
            if (status == 429 || status in 500..599) {
                val wait = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000) ?: 2_000L
                Thread.sleep(wait.coerceIn(500L, 15_000L))
            }
            return null
        }
        val type = connection.contentType.orEmpty().lowercase()
        if (!type.contains("event-stream")) {
            // الخادم تجاهل البث وردّ بـJSON عادي (مثل الخادم المحلي): نقرأه كما هو.
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return runCatching { JSONObject(raw) }.getOrNull()
                ?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content", "").orEmpty().takeIf { it.isNotBlank() }
        }
        val text = StringBuilder()
        var lastEmit = 0L
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("cancelled")
                val line = reader.readLine() ?: break
                if (LiveStream.isDone(line)) break
                val delta = LiveStream.parseLine(line) ?: continue
                text.append(delta)
                val now = System.currentTimeMillis()
                if (now - lastEmit >= 70) {
                    lastEmit = now
                    LiveStream.emit(chatId, text.toString())
                }
            }
        }
        if (text.isNotBlank()) LiveStream.emit(chatId, text.toString())
        text.toString().takeIf { it.isNotBlank() }
    } catch (e: java.io.InterruptedIOException) {
        throw e
    } catch (e: java.io.IOException) {
        null
    } finally {
        connection.disconnect()
    }
}
