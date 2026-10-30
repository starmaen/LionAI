package com.lion.ai

import android.content.Context
import android.content.SharedPreferences
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * خادم HTTP محلي بسيط يعمل على 127.0.0.1 داخل التطبيق.
 * يُعرض نقطة نهاية متوافقة مع OpenAI:
 *   POST http://127.0.0.1:<port>/v1/chat/completions
 * ويمرر الطلب إلى مزود الذكاء الاصطناعي النشط في الإعدادات (Gemini، DeepSeek، إلخ)
 * عبر الإنترنت، ثم يعيد الاستجابة بصيغة OpenAI حتى يمكن استخدام المزوّد "المخصص"
 * في التطبيق للتوجيه إلى هذا الخادم المحلي.
 */
object LocalAIProxy {

    private var server: ServerSocket? = null
    private var port = 0
    private var running = false

    /** يبدأ الخادم وي regrese المنفذ المستخدم. إن كان يعمل déjà يعيد المنفذ الحالي. */
    fun start(context: Context, prefs: SharedPreferences): Int {
        synchronized(this) {
            if (running) return port
            try {
                val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                port = socket.localPort
                server = socket
                running = true
                thread(name = "LocalAIProxy", isDaemon = true) {
                    while (running) {
                        try {
                            val client = socket.accept()
                            thread(name = "LocalAIProxy-Client", isDaemon = true) {
                                handleClient(client, prefs)
                            }
                        } catch (e: Exception) {
                            if (running) e.printStackTrace()
                        }
                    }
                }
                publishEndpoint(prefs)
                return port
            } catch (e: Exception) {
                e.printStackTrace()
                return -1
            }
        }
    }

    fun stop() {
        synchronized(this) {
            running = false
            try { server?.close() } catch (_: Exception) {}
            server = null
            port = 0
        }
    }

    fun isRunning(): Boolean = running

    /** العنوان الأساسي للخادم (بدون مسار). */
    fun getBaseUrl(): String = if (running) "http://127.0.0.1:$port" else ""

    /** رابط OpenAI المتوافق الكامل الجاهز للاستخدام. */
    fun getUrl(): String = if (running) "http://127.0.0.1:$port/v1/chat/completions" else ""

    /**
     * يكتب العنوان الكامل تلقائياً في حقول الإعدادات (custom + local_proxy)
     * حتى لا يحتاج المستخدم لنسخه يدوياً. لا يمس عنوان custom إذا كان مضبوطاً
     * على خادم خارجي حقيقي.
     */
    private fun publishEndpoint(prefs: SharedPreferences) {
        if (!running) return
        val full = getUrl()
        val base = getBaseUrl()
        // لا نلمس مزود «مخصص»: للبروكسي بطاقة واحدة مستقلة، فلا ننشئ مساراً ثانياً.
        prefs.edit()
            .putString("local_proxy_url", full)
            .putString("local_proxy_base_url", base)
            .apply()
    }

    /** المزود الخلفي الفعلي الذي يوجّه إليه الخادم (لا يكون أبداً local_proxy/custom). */
    fun backendProvider(prefs: SharedPreferences): String {
        val active = (prefs.getString("provider", "gemini") ?: "gemini").trim()
        val stored = (prefs.getString("local_proxy_backend", "") ?: "").trim()
        val candidate = stored.ifBlank {
            if (active == "local_proxy" || active == "custom") "gemini" else active
        }
        return if (candidate == "local_proxy" || candidate == "custom" || candidate.isBlank()) "gemini" else candidate
    }

    private fun handleClient(client: Socket, prefs: SharedPreferences) {
        var out: OutputStream? = null
        try {
            client.soTimeout = 60_000
            val input = client.inputStream
            out = client.outputStream

            // قراءة الرؤوس كبايتات (وليس محارف) حتى لا يتشوّه UTF-8 العربي.
            val headerBuffer = java.io.ByteArrayOutputStream()
            var tail = 0
            while (headerBuffer.size() < 64_000) {
                val byte = input.read()
                if (byte < 0) break
                headerBuffer.write(byte)
                tail = ((tail shl 8) or byte) and 0xFFFFFFFF.toInt()
                if (tail == 0x0D0A0D0A) break
            }
            val headers = headerBuffer.toString("ISO-8859-1")
            val requestLine = headers.substringBefore("\r\n")
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            var contentLength = 0
            var chatModeHeader = ""
            headers.substringAfter("\r\n", "").lineSequence().forEach { line ->
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                } else if (lower.startsWith("x-lion-chat-mode:")) {
                    chatModeHeader = line.substringAfter(":").trim()
                }
            }

            val bodyBytes = ByteArray(contentLength.coerceIn(0, 4_000_000))
            var read = 0
            while (read < bodyBytes.size) {
                val count = input.read(bodyBytes, read, bodyBytes.size - read)
                if (count < 0) break
                read += count
            }
            val body = String(bodyBytes, 0, read, Charsets.UTF_8)

            val response: String = when {
                method == "POST" && path.startsWith("/v1/chat/completions") ->
                    processChatCompletion(body, prefs, chatModeHeader)
                method == "GET" && path.startsWith("/v1/models") -> {
                    val provider = backendProvider(prefs)
                    val model = (prefs.getString("model_$provider", "") ?: "").trim()
                        .ifBlank { LlmClient.defaultModel(provider) }
                    org.json.JSONObject().apply {
                        put("object", "list")
                        put("data", org.json.JSONArray().put(
                            org.json.JSONObject().put("id", model).put("object", "model")
                        ))
                    }.toString()
                }
                method == "GET" && (path.startsWith("/health") || path == "/") ->
                    org.json.JSONObject().put("status", "ok").put("server", "LionAI local proxy").toString()
                else -> ""
            }

            if (response.isEmpty()) {
                val msg = "404 Not Found"
                val bytes = msg.toByteArray(Charsets.US_ASCII)
                out.write("HTTP/1.1 404 Not Found\r\n".toByteArray(Charsets.US_ASCII))
                out.write("Content-Length: ${bytes.size}\r\n".toByteArray(Charsets.US_ASCII))
                out.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
            } else {
                val bytes = response.toByteArray(Charsets.UTF_8)
                out.write("HTTP/1.1 200 OK\r\n".toByteArray(Charsets.US_ASCII))
                out.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray(Charsets.US_ASCII))
                out.write("Content-Length: ${bytes.size}\r\n".toByteArray(Charsets.US_ASCII))
                out.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { out?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** اختبار شامل من طرف إلى طرف: يرسل طلباً عبر HTTP إلى الخادم المحلي نفسه. */
    fun selfTest(prefs: SharedPreferences): String {
        val url = getUrl()
        if (url.isBlank()) return "❌ الخادم المحلي غير مفعّل. أعد فتح التطبيق أو شغّله من الإعدادات."
        return try {
            val payload = org.json.JSONObject().apply {
                put("model", "self-test")
                put("messages", org.json.JSONArray().put(
                    org.json.JSONObject().put("role", "user").put("content", "أجب بكلمة واحدة فقط: تم")
                ))
            }.toString()
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            val json = runCatching { org.json.JSONObject(raw) }.getOrNull()
            val content = json?.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "").orEmpty()
            if (status !in 200..299) {
                "❌ الخادم رد HTTP $status: " + (json?.optJSONObject("error")?.optString("message") ?: raw.take(300))
            } else {
                "✅ الخادم المحلي يعمل (HTTP $status). الرد: " + content.take(120)
            }
        } catch (e: Exception) {
            "❌ تعذّر الوصول للخادم المحلي: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** المسار اليدوي: المزود الخلفي المختار وموديله المخزّن (أو الافتراضي). */
    private fun manualRoute(prefs: SharedPreferences): Route {
        val provider = backendProvider(prefs)
        val stored = (prefs.getString("model_$provider", "") ?: "").trim()
        return Route(provider, stored.ifBlank { LlmClient.defaultModel(provider) })
    }

    private fun autoProviders(prefs: SharedPreferences): List<ProviderInfo> =
        PROVIDERS.filter {
            it.group == ProviderGroup.API && it.id != "local_proxy" && it.id != "custom" &&
                SmartRouter.available(prefs, it.id)
        }

    private fun httpGet(url: String, timeoutMs: Int = 8000): Pair<Int, String> {
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
        }
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            c.disconnect()
        }
    }

    /** الخطوة 1: هل الخادم المحلي نفسه حيّ؟ (بدون أي مزود). */
    fun healthCheck(): String {
        val base = getBaseUrl()
        if (base.isBlank()) return "❌ الخادم المحلي متوقف. أعد فتح التطبيق."
        return try {
            val t = System.currentTimeMillis()
            val (code, body) = httpGet("$base/health")
            val ms = System.currentTimeMillis() - t
            if (code in 200..299) "✅ الخادم يعمل على $base (HTTP $code، ${ms}ms)\n" + body.take(120)
            else "❌ الخادم رد HTTP $code: " + body.take(160)
        } catch (e: Exception) {
            "❌ تعذّر الوصول للخادم: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** الخطوة 2: فحص المزودات. تلقائي: كل المزودات المفعّلة (ويغذّي قياس السرعة للعقل المركزي). يدوي: المزود المختار. */
    fun providerCheck(prefs: SharedPreferences): String {
        val account = (prefs.getString("cf_account_id", "") ?: "").trim()
        val auto = prefs.getBoolean("auto_route", true)
        val targets = if (auto) autoProviders(prefs).take(10)
        else listOfNotNull(PROVIDERS.firstOrNull { it.id == backendProvider(prefs) })
        if (targets.isEmpty()) return "⚠️ لا يوجد أي مزود مفعّل. أدخل مفتاح مزود واحد على الأقل."
        val sb = StringBuilder(
            if (auto) "🧠 تلقائي: أفحص ${targets.size} مزود مفعّل…\n" else "✋ يدوي: ${targets[0].shortName}\n"
        )
        val speeds = mutableListOf<Pair<String, Long>>()
        for (info in targets) {
            val key = (prefs.getString("api_key_${info.id}", "") ?: "").trim()
            val model = (prefs.getString("model_${info.id}", "") ?: "").trim().ifBlank { LlmClient.defaultModel(info.id) }
            val t = System.currentTimeMillis()
            val reply = runCatching {
                LlmClient.ask("أجب بكلمة واحدة فقط: تم", key, info.id, emptyList(), model, account, readTimeoutMs = 15_000)
            }.getOrElse { "❌ " + (it.message ?: "خطأ") }
            val ms = System.currentTimeMillis() - t
            val ok = !LlmClient.isErrorReply(reply)
            SmartRouter.record(Route(info.id, model), ok, ms)
            sb.append(if (ok) "✅ " else "❌ ").append(info.shortName).append(" — ").append(model).append(" — ").append(ms).append("ms")
            if (!ok) sb.append("\n    ").append(reply.replace("\n", " ").take(90))
            sb.append("\n")
            if (ok) speeds.add(info.shortName to ms)
        }
        speeds.minByOrNull { it.second }?.let { sb.append("⚡ الأسرع الآن: ").append(it.first).append("\n") }
        sb.append(selfTest(prefs))
        return sb.toString()
    }

    private fun fetchModels(prefs: SharedPreferences, info: ProviderInfo): String {
        val key = (prefs.getString("api_key_${info.id}", "") ?: "").trim()
        val account = (prefs.getString("cf_account_id", "") ?: "").trim()
        val t = System.currentTimeMillis()
        return try {
            val list = LlmClient.listModels(info.id, key, account)
            if (list.isEmpty()) {
                "⚠️ ${info.shortName}: رد بلا موديلات (تحقق من المفتاح/الحصة)."
            } else {
                ModelCatalog.saveCache(prefs, info.id, list)
                val current = (prefs.getString("model_${info.id}", "") ?: "").trim()
                val note = if (current.isNotBlank() && current !in list) " ⚠️ موديلك «$current» غير موجود في القائمة" else ""
                "✅ ${info.shortName}: ${list.size} موديل (${System.currentTimeMillis() - t}ms) — " +
                    list.take(3).joinToString("، ") + note
            }
        } catch (e: Exception) {
            "❌ ${info.shortName}: " + (e.message ?: e.javaClass.simpleName).take(90)
        }
    }

    /** الخطوة 3: تحديث النماذج من الإنترنت. تلقائي: لكل مزود مفعّل. يدوي: للمزود المختار. */
    fun refreshModels(prefs: SharedPreferences): String {
        val auto = prefs.getBoolean("auto_route", true)
        val targets = if (auto) autoProviders(prefs).take(14)
        else listOfNotNull(PROVIDERS.firstOrNull { it.id == backendProvider(prefs) })
        if (targets.isEmpty()) return "⚠️ لا يوجد أي مزود مفعّل. أدخل مفتاح مزود واحد على الأقل."
        return targets.joinToString("\n") { fetchModels(prefs, it) }
    }

    /** وضع المحادثة: من ترويسة التطبيق، أو من تعليمات النظام، أو الافتراضي المحفوظ. */
    private fun chatModeOf(header: String, systemPrompt: String, prefs: SharedPreferences): ChatMode {
        ChatMode.entries.firstOrNull { it.id == header.trim().lowercase() }?.let { return it }
        return when {
            systemPrompt.contains("وضع الهندسة والبرمجة") -> ChatMode.CODE
            systemPrompt.contains("وضع التعلّم") -> ChatMode.LEARN
            systemPrompt.contains("وضع الدردشة العامة") -> ChatMode.GENERAL
            else -> ContextStore.defaultMode(prefs)
        }
    }

    /** مفتاح المزود الفعلي مع دعم المزودين المستوردين (imp_*). */
    private fun keyOf(prefs: SharedPreferences, provider: String): String =
        (prefs.getString("api_key_$provider", "") ?: "").trim()

    private fun processChatCompletion(
        body: String,
        prefs: SharedPreferences,
        chatModeHeader: String = ""
    ): String {
        return try {
            val json = org.json.JSONObject(body)
            val messages = json.optJSONArray("messages")

            // نمرر سياق الوكيل كما هو: system (تعليمات الأدوات/الوضع) + التاريخ + آخر رسالة مستخدم.
            val systemParts = mutableListOf<String>()
            val history = mutableListOf<ChatTurn>()
            var lastUserIndex = -1
            if (messages != null) {
                for (i in 0 until messages.length()) {
                    if (messages.optJSONObject(i)?.optString("role") == "user") lastUserIndex = i
                }
                for (i in 0 until messages.length()) {
                    val msg = messages.optJSONObject(i) ?: continue
                    val role = msg.optString("role")
                    val content = msg.optString("content", "")
                    when (role) {
                        "system" -> if (content.isNotBlank()) systemParts.add(content)
                        "user" -> if (i != lastUserIndex && content.isNotBlank()) history.add(ChatTurn("user", content))
                        "assistant" -> if (content.isNotBlank()) history.add(ChatTurn("assistant", content))
                    }
                }
            }
            val userText = if (lastUserIndex >= 0) {
                messages?.optJSONObject(lastUserIndex)?.optString("content", "").orEmpty()
            } else ""
            val systemPrompt = systemParts.joinToString("\n\n").ifBlank { LLM_SYSTEM_PROMPT }
            val chatMode = chatModeOf(chatModeHeader, systemPrompt, prefs)
            val taskKind = SmartRouter.classify(userText, false, 0, null)
            val answerMode = AnswerMode.read(prefs)
            val accountId = (prefs.getString("cf_account_id", "") ?: "").trim()

            // نفس سياسة التطبيق: يدوي يبقى يدوياً، والتلقائي يستخدم العقل المركزي،
            // والمساعدة تضيف بديلاً عند فشل المزود المختار.
            val routes = RoutePolicy.serverPlan(prefs, taskKind, answerMode, chatMode)
            val deadline = System.currentTimeMillis() + SmartRouter.timeoutMs(taskKind)
            val tried = mutableListOf<String>()
            var reply = ""
            var used: Route? = null
            var lastError = ""

            for (route in routes) {
                if (System.currentTimeMillis() >= deadline) break
                val started = System.currentTimeMillis()
                val cap = minOf(
                    (deadline - System.currentTimeMillis()).coerceAtLeast(5_000L),
                    LlmClient.attemptTimeoutMs(taskKind).toLong()
                ).toInt()
                val candidate = LlmClient.ask(
                    message = userText,
                    apiKey = keyOf(prefs, route.provider),
                    provider = route.provider,
                    history = history,
                    modelOverride = route.model,
                    accountId = accountId,
                    systemPrompt = systemPrompt,
                    readTimeoutMs = cap,
                    chatModeHint = chatMode
                )
                val ok = !LlmClient.isErrorReply(candidate)
                SmartRouter.record(route, ok, System.currentTimeMillis() - started)
                if (ok) {
                    reply = candidate
                    used = route
                    LlmClient.recordSuccess(route.provider, route.model)
                    break
                }
                lastError = candidate
                tried.add(RoutePolicy.shortName(route.provider))
            }

            if (used == null) {
                reply = lastError.ifBlank {
                    "⚠️ تعذر الوصول إلى مزود يرد حالياً. تحقق من الاتصال أو جرّب مزوداً آخر."
                }
                if (tried.isNotEmpty()) {
                    reply += "\n\n(جرّبت بالترتيب: " + tried.distinct().joinToString("، ") + ")"
                }
                if (RoutePolicy.isManual(prefs) && !RoutePolicy.assistEnabled(prefs)) {
                    reply += "\n\n🔒 الوضع اليدوي: بقيت على المزود المختار ولم أبدّل تلقائياً. " +
                        "تحقق من المفتاح/الموديل أو فعّل «مساعدة العقل المركزي في الوضع اليدوي»."
                }
            }

            val usedRoute = used
            val modelName = usedRoute?.model?.takeIf { it.isNotBlank() }
                ?: LlmClient.defaultModel(usedRoute?.provider ?: backendProvider(prefs))

            org.json.JSONObject().apply {
                put("id", "chatcmpl-local-${System.currentTimeMillis()}")
                put("object", "chat.completion")
                put("created", System.currentTimeMillis() / 1000)
                put("model", modelName.ifBlank { usedRoute?.provider ?: backendProvider(prefs) })
                put("choices", org.json.JSONArray().apply {
                    put(org.json.JSONObject().apply {
                        put("index", 0)
                        put("message", org.json.JSONObject().apply {
                            put("role", "assistant")
                            put("content", reply)
                        })
                        put("finish_reason", "stop")
                    })
                })
            }.toString()
        } catch (e: Exception) {
            org.json.JSONObject().apply {
                put("error", org.json.JSONObject().apply {
                    put("message", e.message ?: "خطأ داخلي في الخادم المحلي")
                    put("type", "local_proxy_error")
                })
            }.toString()
        }
    }
}
