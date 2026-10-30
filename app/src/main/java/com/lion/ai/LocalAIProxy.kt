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
            headers.substringAfter("\r\n", "").lineSequence().forEach { line ->
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
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
                    processChatCompletion(body, prefs)
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

    private fun processChatCompletion(body: String, prefs: SharedPreferences): String {
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

            val provider = backendProvider(prefs)
            val apiKey = (prefs.getString("api_key_$provider", "") ?: "").trim()
            val storedModel = (prefs.getString("model_$provider", "") ?: "").trim()
            // تلقائي بالكامل: موديل المزود المخزّن، وإلا افتراضي المزود نفسه (لا نستخدم موديل البروكسي)
            val modelName = storedModel.ifBlank { LlmClient.defaultModel(provider) }

            val reply = LlmClient.ask(
                message = userText,
                apiKey = apiKey,
                provider = provider,
                history = history,
                modelOverride = modelName,
                systemPrompt = systemPrompt
            )

            org.json.JSONObject().apply {
                put("id", "chatcmpl-local-${System.currentTimeMillis()}")
                put("object", "chat.completion")
                put("created", System.currentTimeMillis() / 1000)
                put("model", modelName.ifBlank { provider })
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
