package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object LlmClient {
    /** عناوين وسيطة اختيارية (مثل وسيط Cloudflare Worker) تتجاوز العنوان الافتراضي لكل مزود. */
    @Volatile
    var endpointOverrides: Map<String, String> = emptyMap()

    /**
     * مهلة القراءة الخاصة بنداء واحد. تتيح للمنسّق فرض سقف زمني قصير على الردود السريعة
     * (المجاملات) من دون تغيير المهلة العامة لباقي النداءات.
     */
    private val threadReadTimeout = ThreadLocal<Int>()

    /** سقف زمني لمحاولة واحدة حسب نوع المهمة: البسيط سريع، والبرمجة تحتمل الانتظار. */
    internal fun attemptTimeoutMs(kind: TaskKind): Int = when (kind) {
        TaskKind.FAST -> 12_000
        TaskKind.GENERAL, TaskKind.WRITING -> 30_000
        TaskKind.FACTUAL -> 30_000
        TaskKind.REASONING -> 40_000
        TaskKind.VISION, TaskKind.IMAGE_EDIT -> 45_000
        TaskKind.CODE, TaskKind.DEV -> 60_000
        TaskKind.LONG_DOC -> 45_000
    }

    private inline fun <T> withReadTimeout(ms: Int, block: () -> T): T {
        val previous = threadReadTimeout.get()
        threadReadTimeout.set(ms)
        return try {
            block()
        } finally {
            if (previous == null) threadReadTimeout.remove() else threadReadTimeout.set(previous)
        }
    }

    private fun currentReadTimeout(): Int = threadReadTimeout.get() ?: LLM_READ_TIMEOUT

    fun reloadOverrides(prefs: android.content.SharedPreferences) {
        endpointOverrides = PROVIDERS.associate { it.id to (prefs.getString("endpoint_" + it.id, "") ?: "").trim() }
    }

    internal fun endpoint(provider: String, default: String): String {
        val custom = endpointOverrides[provider]?.trim().orEmpty()
        return if (custom.isNotEmpty() && isSafeEndpoint(custom)) custom else default
    }

    /** يسمح بـ HTTPS، أو HTTP للشبكات المحلية فقط (LiteLLM/Ollama على نفس الجهاز أو LAN). */
    internal fun isSafeEndpoint(url: String): Boolean {
        if (url.startsWith("https://")) return true
        if (!url.startsWith("http://")) return false
        val authority = url.removePrefix("http://").substringBefore('/')
        val host = authority.substringBefore(':').lowercase()
        if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") return true
        if (host.startsWith("192.168.") || host.startsWith("10.")) return true
        if (host.startsWith("172.")) {
            val second = host.split('.').getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    /**
     * يطبّع Base URL لخادم OpenAI-Compatible:
     * يقبل الرابط الكامل أو /v1 أو جذر الخادم، ويضيف /v1/chat/completions عند الحاجة.
     */
    internal fun normalizeCustomUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.isBlank()) return ""
        if (url.endsWith("/chat/completions")) return url
        if (url.endsWith("/models")) return url.removeSuffix("/models") + "/chat/completions"
        if (url.endsWith("/v1")) return "$url/chat/completions"
        return "$url/v1/chat/completions"
    }

    private fun openCodeId(prefix: String): String =
        prefix + java.util.UUID.randomUUID().toString().replace("-", "").take(26)

    /** معرّف جلسة ثابت يرسله OpenCode لتوجيه الطلبات وتخزين الـ prompt cache. */
    internal val openCodeSession: String by lazy { openCodeId("ses_") }

    /** معرّف ثابت للعميل يرسله OpenCode مع كل طلب (x-opencode-request). */
    internal val openCodeRequest: String by lazy { openCodeId("usr_") }

    /** هل الموديل الحالي يقرأ الصور؟ (OpenRouter يعتمد على الموديل، وخطأ المزود يُعرض إن لم يدعمها) */
    fun supportsVision(provider: String, model: String): Boolean = when (provider) {
        "gemini", "openai", "claude", "deepseek", "openrouter", "opencode", "opencode-go", "opencode-console",
        "mistral", "cloudflare", "zai", "ollama", "together", "nvidia", "ovh", "custom" -> true
        "groq" -> (model.ifBlank { providerInfo("groq").defaultModel }).contains("llama-4")
        else -> false
    }

    fun planImageEdit(
        userText: String,
        apiKey: String,
        provider: String,
        model: String,
        accountId: String
    ): String? {
        val reply = ask(userText, apiKey, provider, emptyList(), model, accountId, null, LLM_IMAGE_EDIT_PROMPT)
        if (isErrorReply(reply)) return null
        return stripThinking(reply)
    }

    fun isErrorReply(reply: String): Boolean =
        reply.startsWith("⚠️") || reply.startsWith("🔑") || reply.startsWith("⏳") ||
            reply.startsWith("💳") || reply.startsWith("🆓") || reply.startsWith("⛔") ||
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
        val arabic = userText.any { it in '\u0600'..'\u06FF' }
        val langNote = if (arabic) "اكتب العنوان بالعربية.\n" else ""
        val prompt = "اكتب عنواناً لهذه المحادثة.\n" + langNote +
            "\nالمستخدم: ${userText.take(400)}\nالمساعد: ${assistantText.take(400)}"
        val reply = ask(prompt, apiKey, provider, emptyList(), model, accountId, null, LLM_TITLE_PROMPT)
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
        val reply = ask(userText, apiKey, provider, emptyList(), model, accountId, null, LLM_INTENT_PROMPT.replace("{NOW}", now))
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
        systemPrompt: String = LLM_SYSTEM_PROMPT,
        readTimeoutMs: Int = 0
    ): String {
        // المفاتيح الملصوقة تحمل غالباً مسافة أو سطراً جديداً يُفسد الطلب في كل المزودات
        val apiKey = apiKey.filterNot { it.isWhitespace() }
        if (apiKey.isBlank() && !providerInfo(provider).keyless) {
            return "⚠️ يرجى إدخال مفتاح API للمزود من الإعدادات (⚙️)"
        }
        if (provider == "cloudflare" && accountId.isBlank()) {
            return "⚠️ أدخل Cloudflare Account ID من الإعدادات"
        }

        val model = modelOverride.trim().ifBlank { defaultModel(provider) }
        val turns = normalize(history)
        val cap = if (readTimeoutMs > 0) readTimeoutMs else currentReadTimeout()

        return withReadTimeout(cap) {
            try {
            when (provider) {
                "gemini" -> callGemini(message, apiKey, model, turns, image, systemPrompt)
                "opencode", "opencode-go", "opencode-console" -> callOpenCode(
                    provider, message, apiKey, model, turns, image, systemPrompt
                )
                "groq" -> callOpenAiCompatible(
                    endpoint("groq", "https://api.groq.com/openai/v1/chat/completions"), apiKey, model, turns, message,
                    maxTokens = LLM_OPENAI_MAX_TOKENS, image = image, systemPrompt = systemPrompt
                )
                "openrouter" -> callOpenAiCompatible(
                    endpoint("openrouter", "https://openrouter.ai/api/v1/chat/completions"), apiKey, model, turns, message,
                    mapOf("HTTP-Referer" to "https://github.com/starmaen/LionAI", "X-Title" to "Lion AI"),
                    image = image, systemPrompt = systemPrompt
                )
                "deepseek" -> callOpenAiCompatible(
                    endpoint("deepseek", "https://api.deepseek.com/chat/completions"), apiKey, model, turns, message,
                    omitTemperature = true, maxTokens = LLM_OPENAI_MAX_TOKENS, image = image, systemPrompt = systemPrompt
                )
                "openai" -> callOpenAiCompatible(
                    endpoint("openai", "https://api.openai.com/v1/chat/completions"), apiKey, model, turns, message,
                    tokenParam = "max_completion_tokens", omitTemperature = true,
                    maxTokens = LLM_OPENAI_MAX_TOKENS, image = image, systemPrompt = systemPrompt
                )
                "cloudflare" -> callOpenAiCompatible(
                    "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1/chat/completions",
                    apiKey, model, turns, message, image = image, systemPrompt = systemPrompt
                )
                "cohere" -> callCohere(message, apiKey, model, turns, image, systemPrompt)
                "claude" -> callClaude(message, apiKey, model, turns, image, systemPrompt)
                "aihorde" -> callAiHorde(message, apiKey, model, turns, image, systemPrompt)
                "custom" -> {
                    val raw = endpoint("custom", "")
                    if (raw.isBlank()) {
                        "⚠️ لم يُضبط مزود مخصص بعد. افتح الإعدادات ← المزودات الأساسية ← «مخصص»، وأدخل Base URL لخادم LiteLLM/Ollama/OpenAI-Compatible."
                    } else {
                        callOpenAiCompatible(
                            normalizeCustomUrl(raw), apiKey, model, turns, message,
                            maxTokens = LLM_OPENAI_MAX_TOKENS, image = image, systemPrompt = systemPrompt
                        )
                    }
                }
                else -> {
                    val info = PROVIDERS.firstOrNull { it.id == provider }
                    if (info == null || info.endpoint.isBlank()) {
                        "⚠️ مزود غير معروف: $provider"
                    } else {
                        callOpenAiCompatible(
                            endpoint(provider, info.endpoint), apiKey, model, turns, message,
                            maxTokens = LLM_OPENAI_MAX_TOKENS, image = image, systemPrompt = systemPrompt
                        )
                    }
                }
            }
        } catch (e: LlmException) {
            val raw = e.message.orEmpty()
            if (provider.startsWith("opencode") &&
                (raw.contains("FreeUsageLimit", ignoreCase = true) ||
                    raw.contains("Free usage limit", ignoreCase = true) ||
                    raw.contains("free-tier", ignoreCase = true))
            ) {
                return "🆓 الموديل «$model» مجاني، والموديلات المجانية في OpenCode مخصّصة لتطبيق OpenCode الرسمي فقط (FreeUsageLimitError).\n" +
                    "اختر موديلاً مدفوعاً برصيد Zen من قائمة «تحديث»، أو استخدم مزود OpenCode Go إن كان لديك اشتراك."
            }
            when (e.status) {
                402 -> if (provider.startsWith("opencode")) {
                    "💳 لا يوجد رصيد كافٍ في OpenCode لهذا الموديل المدفوع. أضف رصيداً في opencode.ai/console أو استخدم مزود OpenCode Go."
                } else {
                    "⚠️ خطأ من المزود (402): ${e.message}"
                }
                401, 403 -> "🔑 المفتاح غير صالح أو لا يملك صلاحية لهذا الموديل (${e.status}).\n${e.message}" +
                    regionHint(provider, e.message.orEmpty())
                404 -> "⚠️ الموديل «$model» غير متاح لهذا المفتاح، أو أن المزود رفض الطلب. جرّب موديلاً آخر من الإعدادات." +
                    regionHint(provider, e.message.orEmpty())
                429 -> "⏳ تجاوزت حد الاستخدام المجاني مؤقتاً. انتظر قليلاً ثم أعد المحاولة.\n${e.message}"
                else -> "⚠️ خطأ من المزود (${e.status}): ${e.message}"
            }
        } catch (e: Exception) {
            "⚠️ تعذر الاتصال بالمزود: ${e.message ?: e.javaClass.simpleName}"
        }
        }
    }

    private fun regionHint(provider: String, message: String): String {
        if (provider == "groq") {
            return "\n\n💡 Groq يحجب طلبات الدول الخاضعة للعقوبات (يرد 403 أو 404 حتى مع مفتاح صحيح). " +
                "جرّب تشغيل VPN بدولة أخرى، أو أدخل «عنوان وسيط» في الإعدادات، أو استخدم OpenCode / OpenRouter."
        }
        val blocked = message.contains("Access denied", ignoreCase = true) ||
            message.contains("network settings", ignoreCase = true) ||
            message.contains("not supported", ignoreCase = true) ||
            message.contains("1010")
        return if (blocked) {
            "\n\n💡 يبدو أن المزود يرفض الاتصال من شبكتك أو منطقتك. جرّب VPN أو «عنوان وسيط» من الإعدادات."
        } else {
            ""
        }
    }

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

    internal fun idsOf(array: JSONArray?, key: String): List<String> {
        val out = mutableListOf<String>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val value = array.optJSONObject(i)?.optString(key, "").orEmpty()
                if (value.isNotBlank()) out.add(value)
            }
        }
        return out
    }

    internal fun requireKey(apiKey: String) {
        if (apiKey.isBlank()) throw LlmException(401, "أدخل مفتاح المزود أولاً ثم اضغط تحديث")
    }

    internal fun getJson(url: String, headers: Map<String, String>): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = LLM_CONNECT_TIMEOUT
            readTimeout = minOf(currentReadTimeout(), 30_000)
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", LLM_USER_AGENT)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (status !in 200..299) throw LlmException(status, errorMessage(json, raw))
            return json ?: throw LlmException(status, "رد غير صالح من المزود")
        } finally {
            connection.disconnect()
        }
    }

    internal fun getJsonArray(url: String, headers: Map<String, String>): JSONArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = LLM_CONNECT_TIMEOUT
            readTimeout = minOf(currentReadTimeout(), 30_000)
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", LLM_USER_AGENT)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONArray(raw) }.getOrNull()
            if (status !in 200..299) throw LlmException(status, errorMessage(null, raw))
            return json ?: throw LlmException(status, "رد غير صالح من المزود")
        } finally {
            connection.disconnect()
        }
    }

    internal fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JSONObject
    ): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = LLM_CONNECT_TIMEOUT
            readTimeout = currentReadTimeout()
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", LLM_USER_AGENT)
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
