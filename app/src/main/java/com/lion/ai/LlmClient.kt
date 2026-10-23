package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ChatTurn(val role: String, val content: String)

class LlmException(val status: Int, message: String) : Exception(message)

object LlmClient {
    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 120_000
    private const val MAX_TOKENS = 2048
    private const val GEMINI_MAX_TOKENS = 8192
    private const val CLAUDE_MAX_TOKENS = 8192
    private const val OPENAI_MAX_TOKENS = 8192
    private const val TEMPERATURE = 0.3

    private const val SYSTEM_PROMPT =
        "أنت Lion AI، مساعد ذكي عربي متعدد الاستخدامات. أجب بالعربية الفصحى المبسطة، " +
            "وكن دقيقاً ومباشراً. عند كتابة الكود استخدم كتل Markdown مع تحديد اللغة، " +
            "واشرح الخطوات باختصار. لا تخترع معلومات؛ إن لم تكن متأكداً فقل ذلك."

    private const val TITLE_PROMPT =
        "أنت تكتب عناوين قصيرة للمحادثات. أجب بالعنوان فقط: من 2 إلى 5 كلمات بلغة المستخدم، " +
            "بدون علامات ترقيم أو اقتباس أو إيموجي أو شرح."

    private const val INTENT_PROMPT = """أنت محلّل أوامر لتطبيق هاتف. حوّل طلب المستخدم إلى JSON فقط، بدون شرح وبدون Markdown.
الوقت الحالي: {NOW}
الصيغة:
{"intent":"reminder|sms|call|none","recipient":"الاسم أو الرقم كما ذكره المستخدم أو فارغ","message":"النص النهائي","delay_minutes":رقم أو null,"at":"yyyy-MM-dd HH:mm" أو null}
القواعد:
- reminder: تذكير المستخدم بشيء. message = ما يجب تذكيره به بصيغة المخاطب (مثل: اشرب الدواء).
- sms: إرسال رسالة لشخص. message = نص الرسالة كما سيقرؤه المستلم مباشرة بصيغة المخاطب (حوّل الغائب إلى مخاطب، مثل: "اخبر احمد ان ياتي هنا الساعة 5" يصبح "تعال إلى هنا الساعة 5"). لا تضف معلومات لم يذكرها المستخدم.
- call: اتصال هاتفي بشخص. message فارغ.
- الوقت النسبي: ربع ساعة=15، نصف ساعة=30، ساعة=60، دقيقتان=2، ساعتان=120 ... ضعه في delay_minutes.
- الوقت المحدد (الساعة 7 صباحاً، غداً الساعة 9 مساءً ...) ضعه في at بتوقيت الجهاز وبنظام 24 ساعة. إذا لم يُذكر صباحاً أو مساءً فاختر أقرب وقت قادم.
- إن لم يُذكر وقت فاجعل delay_minutes و at يساويان null (التنفيذ فوري للرسالة والاتصال).
- إن لم يكن الطلب تذكيراً أو رسالة أو اتصالاً فعلياً (مثل سؤال عن كيفية الإرسال) فاجعل intent=none.
- الرد JSON واحد فقط."""

    fun isErrorReply(reply: String): Boolean =
        reply.startsWith("⚠️") || reply.startsWith("🔑") || reply.startsWith("⏳") ||
            reply.startsWith("لم يصل رد")

    private fun stripThinking(text: String): String =
        text.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    fun generateTitle(
        userText: String,
        assistantText: String,
        apiKey: String,
        provider: String,
        model: String,
        accountId: String
    ): String? {
        val prompt = "اكتب عنواناً لهذه المحادثة.\n\nالمستخدم: ${userText.take(400)}\nالمساعد: ${assistantText.take(400)}"
        val reply = ask(prompt, apiKey, provider, emptyList(), model, accountId, null, TITLE_PROMPT)
        if (isErrorReply(reply)) return null
        val line = stripThinking(reply).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val clean = line.trim('"', '\'', '«', '»', '*', '#', '`', '.', '،', ':', ' ').take(40)
        return clean.ifBlank { null }
    }

    fun extractIntent(
        userText: String,
        apiKey: String,
        provider: String,
        model: String,
        accountId: String,
        nowMillis: Long
    ): String? {
        val now = SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date(nowMillis)) +
            " (" + TimeZone.getDefault().id + ")"
        val reply = ask(userText, apiKey, provider, emptyList(), model, accountId, null, INTENT_PROMPT.replace("{NOW}", now))
        if (isErrorReply(reply)) return null
        return stripThinking(reply)
    }

    fun defaultModel(provider: String): String = providerInfo(provider).defaultModel

    fun ask(
        message: String,
        apiKey: String,
        provider: String,
        history: List<ChatTurn>,
        modelOverride: String = "",
        accountId: String = "",
        image: ImageAttachment? = null,
        systemPrompt: String = SYSTEM_PROMPT
    ): String {
        if (apiKey.isBlank()) {
            return "⚠️ يرجى إدخال مفتاح API للمزود من الإعدادات (⚙️)"
        }
        if (provider == "cloudflare" && accountId.isBlank()) {
            return "⚠️ أدخل Cloudflare Account ID من الإعدادات"
        }

        val model = modelOverride.trim().ifBlank { defaultModel(provider) }
        val turns = normalize(history)

        return try {
            when (provider) {
                "gemini" -> callGemini(message, apiKey, model, turns, image, systemPrompt)
                "opencode" -> {
                    if (model.startsWith("gpt-") || model.startsWith("claude-") || model.startsWith("gemini-")) {
                        "⚠️ الموديل «$model» يعمل عبر واجهة مختلفة في OpenCode. اختر موديلاً من نوع chat/completions مثل big-pickle."
                    } else {
                        callOpenAiCompatible(
                            "https://opencode.ai/zen/v1/chat/completions", apiKey, model, turns, message,
                            maxTokens = OPENAI_MAX_TOKENS, image = null, systemPrompt = systemPrompt
                        )
                    }
                }
                "groq" -> callOpenAiCompatible(
                    "https://api.groq.com/openai/v1/chat/completions", apiKey, model, turns, message,
                    image = image, systemPrompt = systemPrompt
                )
                "openrouter" -> callOpenAiCompatible(
                    "https://openrouter.ai/api/v1/chat/completions", apiKey, model, turns, message,
                    mapOf("HTTP-Referer" to "https://github.com/starmaen/LionAI", "X-Title" to "Lion AI"),
                    image = image, systemPrompt = systemPrompt
                )
                "deepseek" -> callOpenAiCompatible(
                    "https://api.deepseek.com/chat/completions", apiKey, model, turns, message,
                    omitTemperature = true, maxTokens = OPENAI_MAX_TOKENS, image = image, systemPrompt = systemPrompt
                )
                "openai" -> callOpenAiCompatible(
                    "https://api.openai.com/v1/chat/completions", apiKey, model, turns, message,
                    tokenParam = "max_completion_tokens", omitTemperature = true,
                    maxTokens = OPENAI_MAX_TOKENS, image = image, systemPrompt = systemPrompt
                )
                "cloudflare" -> callOpenAiCompatible(
                    "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1/chat/completions",
                    apiKey, model, turns, message, image = image, systemPrompt = systemPrompt
                )
                "cohere" -> callCohere(message, apiKey, model, turns, image, systemPrompt)
                "claude" -> callClaude(message, apiKey, model, turns, image, systemPrompt)
                else -> "⚠️ مزود غير معروف: $provider"
            }
        } catch (e: LlmException) {
            when (e.status) {
                401, 403 -> "🔑 المفتاح غير صالح أو لا يملك صلاحية لهذا الموديل (${e.status}).\n${e.message}"
                404 -> "⚠️ الموديل «$model» غير متاح لهذا المفتاح. جرّب موديلاً آخر من الإعدادات."
                429 -> "⏳ تجاوزت حد الاستخدام المجاني مؤقتاً. انتظر قليلاً ثم أعد المحاولة.\n${e.message}"
                else -> "⚠️ خطأ من المزود (${e.status}): ${e.message}"
            }
        } catch (e: Exception) {
            "⚠️ تعذر الاتصال بالمزود: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun callOpenAiCompatible(
        url: String,
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        message: String,
        extraHeaders: Map<String, String> = emptyMap(),
        tokenParam: String = "max_tokens",
        omitTemperature: Boolean = false,
        maxTokens: Int = MAX_TOKENS,
        image: ImageAttachment? = null,
        systemPrompt: String = SYSTEM_PROMPT
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.forEach { turn ->
            messages.put(JSONObject().put("role", turn.role).put("content", turn.content))
        }
        val userContent: Any = if (image == null) {
            message
        } else {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", message))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", imageDataUri(image)))
                )
        }
        messages.put(JSONObject().put("role", "user").put("content", userContent))

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put(tokenParam, maxTokens)
        if (!omitTemperature) body.put("temperature", TEMPERATURE)

        val headers = mutableMapOf("Authorization" to "Bearer $apiKey")
        headers.putAll(extraHeaders)

        val response = postJson(url, headers, body)
        val content = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            .orEmpty()
        return content.ifBlank { "لم يصل رد من المزود." }
    }

    private fun callGemini(
        message: String,
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        image: ImageAttachment?,
        systemPrompt: String
    ): String {
        val contents = JSONArray()
        history.forEach { turn ->
            contents.put(
                JSONObject()
                    .put("role", if (turn.role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.content)))
            )
        }
        val userParts = JSONArray().put(JSONObject().put("text", message))
        if (image != null) {
            userParts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", image.mime).put("data", image.base64)
                )
            )
        }
        contents.put(JSONObject().put("role", "user").put("parts", userParts))

        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject().put("maxOutputTokens", GEMINI_MAX_TOKENS)
            )

        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            model + ":generateContent"
        val response = postJson(url, mapOf("x-goog-api-key" to apiKey), body)

        val parts = response.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")

        val text = StringBuilder()
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (part.optBoolean("thought", false)) continue
                text.append(part.optString("text", ""))
            }
        }
        if (text.isNotBlank()) return text.toString()

        val blocked = response.optJSONObject("promptFeedback")
            ?.optString("blockReason", "")
            .orEmpty()
        return if (blocked.isNotBlank()) {
            "⚠️ حجب Gemini الطلب ($blocked). جرّب صياغة أخرى."
        } else {
            "لم يصل رد من Gemini."
        }
    }

    private fun callCohere(
        message: String,
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        image: ImageAttachment?,
        systemPrompt: String
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.forEach { turn ->
            messages.put(JSONObject().put("role", turn.role).put("content", turn.content))
        }
        val cohereContent: Any = if (image == null) {
            message
        } else {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", message))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", imageDataUri(image)))
                )
        }
        messages.put(JSONObject().put("role", "user").put("content", cohereContent))

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", TEMPERATURE)
            .put("max_tokens", MAX_TOKENS)

        val response = postJson(
            "https://api.cohere.com/v2/chat",
            mapOf("Authorization" to "Bearer $apiKey"),
            body
        )

        val parts = response.optJSONObject("message")?.optJSONArray("content")
        val text = StringBuilder()
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (part.optString("type") == "text") text.append(part.optString("text", ""))
            }
        }
        return text.toString().ifBlank { "لم يصل رد من Cohere." }
    }

    private fun callClaude(
        message: String,
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        image: ImageAttachment?,
        systemPrompt: String
    ): String {
        val messages = JSONArray()
        history.forEach { turn ->
            messages.put(JSONObject().put("role", turn.role).put("content", turn.content))
        }
        val claudeContent = JSONArray()
        if (image != null) {
            claudeContent.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", image.mime)
                            .put("data", image.base64)
                    )
            )
        }
        claudeContent.put(JSONObject().put("type", "text").put("text", message))
        messages.put(JSONObject().put("role", "user").put("content", claudeContent))

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", CLAUDE_MAX_TOKENS)
            .put("system", systemPrompt)
            .put("messages", messages)

        val response = postJson(
            "https://api.anthropic.com/v1/messages",
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01"
            ),
            body
        )

        val parts = response.optJSONArray("content")
        val text = StringBuilder()
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (part.optString("type") == "text") text.append(part.optString("text", ""))
            }
        }
        return text.toString().ifBlank { "لم يصل رد من Claude." }
    }

    private fun imageDataUri(image: ImageAttachment): String =
        "data:${image.mime};base64,${image.base64}"

    private fun normalize(history: List<ChatTurn>): List<ChatTurn> {
        val result = mutableListOf<ChatTurn>()
        history.forEach { turn ->
            if (turn.content.isBlank()) return@forEach
            val role = if (turn.role == "assistant") "assistant" else "user"
            val last = result.lastOrNull()
            if (last != null && last.role == role) {
                result[result.lastIndex] = last.copy(content = last.content + "\n" + turn.content)
            } else {
                result.add(ChatTurn(role, turn.content))
            }
        }
        while (result.isNotEmpty() && result.first().role != "user") {
            result.removeAt(0)
        }
        return result
    }

    private fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JSONObject
    ): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

        try {
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()

            if (status !in 200..299) {
                throw LlmException(status, errorMessage(json, raw))
            }
            return json ?: throw LlmException(status, "رد غير صالح من المزود")
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(json: JSONObject?, raw: String): String {
        if (json != null) {
            json.optJSONObject("error")
                ?.optString("message", "")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { return it }

            json.optString("message", "")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { return it }

            json.optJSONArray("errors")
                ?.optJSONObject(0)
                ?.optString("message", "")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { return it }

            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("error")
                ?.optString("message", "")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { return it }
        }
        return raw.take(300).ifBlank { "خطأ غير معروف" }
    }
}
