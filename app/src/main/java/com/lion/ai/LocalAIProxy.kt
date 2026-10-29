package com.lion.ai

import android.content.Context
import android.content.SharedPreferences
import java.io.BufferedReader
import java.io.InputStreamReader
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

    fun getUrl(): String = if (running) "http://127.0.0.1:$port" else ""

    private fun handleClient(client: Socket, prefs: SharedPreferences) {
        var reader: BufferedReader? = null
        var out: OutputStream? = null
        try {
            client.soTimeout = 60_000
            reader = BufferedReader(InputStreamReader(client.inputStream))
            out = client.outputStream

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            // قراءة الرؤوس
            var contentLength = 0
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isBlank()) break
                val lower = header.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            // قراءة الجسم
            val body = if (contentLength > 0) {
                val chars = CharArray(contentLength)
                reader.read(chars, 0, contentLength)
                String(chars)
            } else ""

            if (method == "POST" && path == "/v1/chat/completions") {
                val response = processChatCompletion(body, prefs)
                val bytes = response.toByteArray(Charsets.UTF_8)
                out.write("HTTP/1.1 200 OK\r\n".toByteArray(Charsets.US_ASCII))
                out.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray(Charsets.US_ASCII))
                out.write("Content-Length: ${bytes.size}\r\n".toByteArray(Charsets.US_ASCII))
                out.write("Connection: close\r\n".toByteArray(Charsets.US_ASCII))
                out.write("\r\n".toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
            } else {
                val msg = "404 Not Found"
                val bytes = msg.toByteArray(Charsets.US_ASCII)
                out.write("HTTP/1.1 404 Not Found\r\n".toByteArray(Charsets.US_ASCII))
                out.write("Content-Length: ${bytes.size}\r\n".toByteArray(Charsets.US_ASCII))
                out.write("Connection: close\r\n".toByteArray(Charsets.US_ASCII))
                out.write("\r\n".toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { reader?.close() } catch (_: Exception) {}
            try { out?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun processChatCompletion(body: String, prefs: SharedPreferences): String {
        return try {
            val json = org.json.JSONObject(body)
            val model = json.optString("model", "").trim()
            val messages = json.optJSONArray("messages")

            // نأخذ آخر رسالة من المستخدم
            var userText = ""
            if (messages != null) {
                for (i in 0 until messages.length()) {
                    val msg = messages.optJSONObject(i)
                    if (msg != null && msg.optString("role") == "user") {
                        userText = msg.optString("content", "")
                    }
                }
            }

            val provider = prefs.getString("provider", "gemini").trim()
            val apiKey = prefs.getString("api_key_$provider", "").trim()
            val modelName = if (model.isNotBlank()) model else prefs.getString("model_$provider", "").trim()

            val reply = LlmClient.ask(
                message = userText,
                apiKey = apiKey,
                provider = provider,
                history = emptyList(),
                modelOverride = modelName,
                systemPrompt = LlmConfig.LLM_SYSTEM_PROMPT
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
