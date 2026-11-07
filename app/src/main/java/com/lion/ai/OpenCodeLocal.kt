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
        val tail = java.util.concurrent.ConcurrentLinkedDeque<String>()
        val process = LinuxEnv.start(
            context,
            "OPENCODE_DISABLE_AUTOUPDATE=1 opencode serve --hostname 127.0.0.1 --port $PORT",
            FileTools.root(context)
        )
        server = process
        thread(name = "opencode-local-log", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        tail.addLast(line.take(300))
                        while (tail.size > 30) tail.pollFirst()
                    }
                }
            }
        }
        // أول تشغيل تحت proot بطيء (فك الملف التنفيذي وجلب كتالوج النماذج)، فننتظر حتى دقيقتين.
        repeat(480) {
            if (isRunning()) return BASE
            if (!process.isAlive) {
                throw IllegalStateException("توقف opencode serve:\n" + tail.joinToString("\n").takeLast(1500))
            }
            Thread.sleep(250)
        }
        throw IllegalStateException("لم يستجب opencode serve خلال دقيقتين:\n" + tail.joinToString("\n").takeLast(1500))
    }

    fun stop() {
        server?.destroy()
        server = null
        sessions.clear()
    }

    /** يربط مزوداً بمفتاح API عبر خادم OpenCode المحلي (PUT /auth/{id}) ثم يزامن النماذج مع المنتقي. */
    fun setAuth(provider: String, key: String): String {
        val id = provider.trim().lowercase().filter { it.isLetterOrDigit() || it in "-_." }
        require(id.isNotBlank()) { "معرّف المزود غير صالح" }
        require(key.isNotBlank()) { "المفتاح فارغ" }
        start()
        // request يرمي استثناءً عند أي HTTP غير 2xx، فالفشل يظهر للمستخدم ولا يُعلن نجاح كاذب.
        request("/auth/$id", "PUT", JSONObject().put("type", "api").put("key", key.trim()))
        val count = runCatching { syncModels().count { it.startsWith("$id/") } }.getOrDefault(0)
        return if (count > 0) "✅ رُبط $id — $count موديل أُضيفت إلى قائمة الاختيار"
        else "⚠️ حُفظ المفتاح لكن لم تظهر موديلات لـ $id. تأكد أن المعرّف من معرّفات OpenCode المعروفة (openai, anthropic, openrouter…)."
    }

    /** يجلب موديلات الخادم ويحفظها في كتالوج المنتقي (opencode-local) فتظهر فوراً بلا ضغط «تحديث». */
    fun syncModels(): List<String> {
        val models = listModels()
        if (models.isNotEmpty()) {
            ModelCatalog.saveCache(SecurePrefs.get(ContextHolder.require()), "opencode-local", models)
        }
        return models
    }

    /** إعادة تشغيل الخادم (ليقرأ ما أُضيف من الطرفية) ثم مزامنة الموديلات. */
    fun restartAndSync(): String {
        stop()
        if (isRunning()) return "⚠️ الخادم يعمل خارج التطبيق (من الطرفية). أوقفه هناك ثم أعد المحاولة."
        start()
        val models = syncModels()
        val providers = models.map { it.substringBefore('/') }.distinct()
        return "✅ ${models.size} موديل من ${providers.size} منصة" +
            if (providers.isNotEmpty()) ":\n" + providers.joinToString("، ") else ""
    }

    internal fun formatMcpStatus(status: String, error: String): String = when (status) {
        "connected" -> "✅ متصل"
        "failed" -> "❌ فشل" + if (error.isNotBlank()) ": " + error.take(160) else ""
        "disabled" -> "⏸ معطّل"
        "needs_auth", "needs_client_registration" -> "🔑 يحتاج مصادقة"
        else -> status.ifBlank { "؟" }
    }

    /** حالة كل خادم MCP كما يراها OpenCode فعلاً (GET /mcp): متصل أو فشل مع السبب. */
    fun mcpStatus(): Map<String, String> {
        start()
        // GET /mcp ينتظر اتصال كل الخوادم (npx تحت proot بطيء)، فالمهلة الافتراضية 15ث تعطي «فشل» كاذباً.
        val root = request("/mcp", "GET", readMs = 120_000).second
        return root.keys().asSequence().associateWith { name ->
            val entry = root.optJSONObject(name)
            formatMcpStatus(entry?.optString("status").orEmpty(), entry?.optString("error").orEmpty())
        }
    }

    fun listModels(): List<String> {
        start()
        val root = request("/config/providers", "GET").second
        val defaults = root.optJSONObject("default")
        val result = mutableListOf<String>()
        val providers = root.optJSONArray("providers")
        if (providers != null) {
            for (i in 0 until providers.length()) {
                val provider = providers.optJSONObject(i)
                val id = provider?.optString("id").orEmpty()
                val models = provider?.optJSONObject("models")
                if (id.isNotBlank() && models != null) {
                    val names = models.keys()
                    while (names.hasNext()) {
                        val model = names.next()
                        result += "$id/$model"
                    }
                }
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
        val responseParts = response.optJSONArray("parts")
        if (responseParts != null) {
            for (i in 0 until responseParts.length()) {
                val part = responseParts.optJSONObject(i)
                if (part != null) when (part.optString("type")) {
                    "text" -> part.optString("text").takeIf { it.isNotBlank() }?.let(texts::add)
                    "tool" -> tools += part.optString("tool").ifBlank { "tool" } + ": " + part.optJSONObject("state")?.optString("status").orEmpty()
                }
            }
        }
        val answer = texts.joinToString("\n").trim()
        if (answer.isNotBlank()) return answer
        // OpenCode يضع خطأ المزود الحقيقي في info.error (مع أجزاء فارغة) فلا نُخفيه خلف رسالة عامة.
        response.optJSONObject("info")?.optJSONObject("error")?.let { return describeProviderError(it, model) }
        if (tools.isNotEmpty()) return "نفّذ OpenCode أدواته:\n" + tools.joinToString("\n")
        return "⚠️ لم يُرجع OpenCode نصًا. قد ينتظر إذنًا أو يكون النموذج غير متصل؛ افتح Terminal وراجع opencode auth login والسجل."
    }

    internal fun describeProviderError(error: JSONObject, model: String): String {
        val name = error.optString("name").ifBlank { "ProviderError" }
        val data = error.optJSONObject("data")
        val message = data?.optString("message").orEmpty()
        val status = data?.optInt("statusCode", 0) ?: 0
        val body = data?.optString("responseBody").orEmpty()
        val raw = (message + " " + body).take(600)
        val hint = when {
            raw.contains("tool", ignoreCase = true) && (raw.contains("support", ignoreCase = true) || raw.contains("endpoint", ignoreCase = true)) ->
                "\n💡 الموديل لا يدعم استدعاء الأدوات (OpenCode يرسلها دائماً). اختر موديلاً يدعمها."
            raw.contains("data policy", ignoreCase = true) || raw.contains("privacy", ignoreCase = true) ->
                "\n💡 فعّل خيار مشاركة البيانات للموديلات المجانية في openrouter.ai/settings/privacy."
            status == 401 || name.contains("Auth", ignoreCase = true) -> "\n💡 المفتاح غير صالح أو غير مربوط بهذا المزود."
            status == 402 -> "\n💡 الرصيد غير كافٍ."
            status == 429 -> "\n💡 تجاوزت حد الطلبات (الموديلات المجانية محدودة). انتظر قليلاً."
            else -> ""
        }
        val code = if (status > 0) " ($status)" else ""
        return "⚠️ $name$code — $model\n${message.ifBlank { body }.take(400)}$hint"
    }

    /** يختبر أمر MCP محلي داخل proot فعلياً (initialize عبر stdio) ويعرض سبب الفشل الحقيقي. */
    fun probeLocalMcp(context: android.content.Context, spec: OpenCodeConfig.LocalMcpSpec): String {
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val exe = spec.command.firstOrNull() ?: return "❌ أمر فارغ"
        val init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"lion-probe\",\"version\":\"1\"}}}"
        val envPrefix = spec.env.entries.joinToString(" ") { it.key + "=" + q(it.value) }
        val cmd = spec.command.joinToString(" ") { q(it) }
        val script = "if ! command -v ${q(exe)} >/dev/null 2>&1 && [ ! -x ${q(exe)} ]; then echo 'NOT_FOUND: ' ${q(exe)}; exit 127; fi; " +
            "rm -f /tmp/mcp-probe.err; " +
            "(printf '%s\\n' ${q(init)}; sleep 40) | $envPrefix timeout 45 $cmd 2>/tmp/mcp-probe.err | " +
            "{ IFS= read -r -t 40 line; if [ -n \"\$line\" ]; then echo \"OK stdout: \$line\" | head -c 400; else echo 'NO_RESPONSE within 40s'; fi; }; " +
            "echo; echo '--- stderr ---'; head -c 800 /tmp/mcp-probe.err"
        val r = LinuxEnv.run(context, script, 100)
        val verdict = when {
            r.output.contains("NOT_FOUND") -> "❌ الملف التنفيذي غير موجود داخل البيئة"
            r.output.contains("OK stdout") && r.output.contains("\"result\"") -> "✅ الخادم يعمل ويردّ على initialize"
            else -> "❌ لا رد صالح"
        }
        return "${spec.name}: $verdict\n" + r.output.trim().takeLast(900)
    }

    private fun request(path: String, method: String, body: JSONObject? = null, readMs: Int = if (body == null) 15_000 else 180_000): Pair<Int, JSONObject> {
        val connection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = readMs
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
