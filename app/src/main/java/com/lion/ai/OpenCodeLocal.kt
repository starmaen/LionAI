package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** جسر الواجهة مع خادم OpenCode الرسمي، لا مع Zen API؛ يستخدم auth وأدوات العميل نفسه. */
object OpenCodeLocal {
    private const val PORT = 4096
    private const val BASE = "http://127.0.0.1:$PORT"
    @Volatile private var server: Process? = null
    private val sessions = ConcurrentHashMap<String, String>()

    internal fun splitModel(model: String): Pair<String, String> {
        val clean = model.trim()
        require('/' in clean) { "اختر نموذج OpenCode بصيغة provider/model من زر تحديث" }
        return clean.substringBefore('/') to clean.substringAfter('/')
    }

    fun isRunning(): Boolean = runCatching { request("/config", "GET").first in 200..299 }.getOrDefault(false)

    @Synchronized
    fun start(): String {
        if (isRunning()) return BASE
        val context = ContextHolder.require()
        check(LinuxEnv.ready(context)) { "فعّل بيئة Linux أولاً ثم ثبّت OpenCode" }
        val probe = LinuxEnv.run(context, "command -v opencode", 20)
        check(probe.ok) { "OpenCode غير مثبت. ثبّته من البيئة المحلية أولاً." }
        server?.destroy()
        server = LinuxEnv.start(
            context,
            "opencode serve --hostname 127.0.0.1 --port $PORT",
            FileTools.root(context)
        )
        thread(name = "opencode-local-log", isDaemon = true) {
            runCatching { server?.inputStream?.bufferedReader()?.useLines { lines -> lines.forEach { } } }
        }
        repeat(40) {
            if (isRunning()) return BASE
            if (server?.isAlive == false) throw IllegalStateException("توقف opencode serve؛ راجع إعداداته من Terminal")
            Thread.sleep(250)
        }
        throw IllegalStateException("لم يستجب opencode serve خلال 10 ثوانٍ")
    }

    fun stop() {
        server?.destroy()
        server = null
        sessions.clear()
    }

    fun listModels(): List<String> {
        start()
        val root = request("/config/providers", "GET").second
        val defaults = root.optJSONObject("default")
        val result = mutableListOf<String>()
        root.optJSONArray("providers")?.let { providers ->
            for (i in 0 until providers.length()) {
                val provider = providers.optJSONObject(i) ?: continue
                val id = provider.optString("id").ifBlank { continue }
                val models = provider.optJSONObject("models") ?: continue
                models.keys().forEach { model -> result += "$id/$model" }
            }
        }
        return result.distinct().sortedWith(compareByDescending<String> { id ->
            defaults?.optString(id.substringBefore('/')) == id.substringAfter('/')
        }.thenBy { it })
    }

    fun chat(
        message: String,
        model: String,
        history: List<ChatTurn>,
        image: ImageAttachment?,
        systemPrompt: String,
        chatId: String = ""
    ): String {
        start()
        val (providerId, modelId) = splitModel(model)
        val key = chatId.ifBlank { "default" }
        var sessionId = sessions[key]
        if (sessionId == null) {
            val created = request("/session", "POST", JSONObject().put("title", "Lion AI — $key")).second
            sessionId = created.optString("id").ifBlank { throw IllegalStateException("لم ينشئ OpenCode جلسة") }
            sessions[key] = sessionId
        }
        val parts = JSONArray()
        val includeHistory = history.takeLast(6).joinToString("\n") { "${it.role}: ${it.content}" }
        parts.put(JSONObject().put("type", "text").put("text", if (includeHistory.isBlank()) message else "$includeHistory\nuser: $message"))
        if (image != null) {
            parts.put(JSONObject().put("type", "file").put("mime", image.mime).put("url", "data:${image.mime};base64,${image.base64}"))
        }
        val body = JSONObject()
            .put("model", JSONObject().put("providerID", providerId).put("modelID", modelId))
            .put("system", systemPrompt)
            .put("parts", parts)
        val response = request("/session/$sessionId/message", "POST", body).second
        val texts = mutableListOf<String>()
        val tools = mutableListOf<String>()
        response.optJSONArray("parts")?.let { array ->
            for (i in 0 until array.length()) {
                val part = array.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text" -> part.optString("text").takeIf { it.isNotBlank() }?.let(texts::add)
                    "tool" -> tools += part.optString("tool").ifBlank { "tool" } + ": " + part.optJSONObject("state")?.optString("status").orEmpty()
                }
            }
        }
        val answer = texts.joinToString("\n").trim()
        if (answer.isNotBlank()) return answer
        if (tools.isNotEmpty()) return "نفّذ OpenCode أدواته:\n" + tools.joinToString("\n")
        return "⚠️ لم يُرجع OpenCode نصًا. قد ينتظر إذنًا أو يكون النموذج غير متصل؛ افتح Terminal وراجع opencode auth login والسجل."
    }

    private fun request(path: String, method: String, body: JSONObject? = null): Pair<Int, JSONObject> {
        val connection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = if (body == null) 15_000 else 180_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            doOutput = body != null
        }
        return try {
            body?.let { connection.outputStream.use { out -> out.write(it.toString().toByteArray()) } }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            if (status !in 200..299) throw IllegalStateException("OpenCode HTTP $status: ${raw.take(500)}")
            status to json
        } finally {
            connection.disconnect()
        }
    }
}
