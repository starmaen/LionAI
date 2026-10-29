package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject

internal fun LlmClient.callOpenAiCompatible(
        url: String,
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        message: String,
        extraHeaders: Map<String, String> = emptyMap(),
        tokenParam: String = "max_tokens",
        omitTemperature: Boolean = false,
        maxTokens: Int = LLM_MAX_TOKENS,
        image: ImageAttachment? = null,
        systemPrompt: String = LLM_SYSTEM_PROMPT
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
        if (!omitTemperature) body.put("temperature", LLM_TEMPERATURE)

        val headers = mutableMapOf<String, String>()
        if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"
        headers.putAll(extraHeaders)

        val response = postJson(url, headers, body)
        val content = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            .orEmpty()
        return content.ifBlank { "لم يصل رد من المزود." }
    }

internal fun LlmClient.callGemini(
        message: String,
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        image: ImageAttachment?,
        systemPrompt: String,
        url: String = "https://generativelanguage.googleapis.com/v1beta/models/" +
            model + ":generateContent",
        headers: Map<String, String> = mapOf("x-goog-api-key" to apiKey)
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
                JSONObject().put("maxOutputTokens", LLM_GEMINI_MAX_TOKENS)
            )

        val response = postJson(url, headers, body)

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

internal fun LlmClient.callCohere(
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
            .put("temperature", LLM_TEMPERATURE)
            .put("max_tokens", LLM_MAX_TOKENS)

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

internal fun LlmClient.callClaude(
        message: String,
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        image: ImageAttachment?,
        systemPrompt: String,
        url: String = "https://api.anthropic.com/v1/messages",
        headers: Map<String, String> = mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to "2023-06-01"
        )
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
            .put("max_tokens", LLM_CLAUDE_MAX_TOKENS)
            .put("system", systemPrompt)
            .put("messages", messages)

        val response = postJson(
            url,
            headers,
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

/**
 * AI Horde واجهة غير متزامنة: نرسل الطلب ثم نستطلع حالته حتى يجهز الرد.
 * المفتاح اختياري؛ عند غيابه نستخدم المفتاح المجهول الرسمي.
 */
internal fun LlmClient.callAiHorde(
        message: String,
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        image: ImageAttachment?,
        systemPrompt: String
    ): String {
        val conversation = buildString {
            history.forEach { turn ->
                val role = if (turn.role == "assistant") "Assistant" else "User"
                append("\n").append(role).append(": ").append(turn.content.trim())
            }
            append("\nUser: ").append(message.trim()).append("\nAssistant:")
        }
        val prefix = systemPrompt.trim() + "\n"
        val prompt = prefix + conversation.takeLast((12_000 - prefix.length).coerceAtLeast(2_000))

        val body = JSONObject()
            .put("prompt", prompt)
            .put(
                "params",
                JSONObject()
                    .put("max_length", LLM_HORDE_MAX_TOKENS)
                    .put("max_context_length", LLM_HORDE_CONTEXT)
                    .put("temperature", LLM_TEMPERATURE)
                    .put("top_p", 0.9)
                    .put("rep_pen", 1.05)
            )
            .put("trusted_workers", false)
            .put("slow_workers", true)

        val chosen = model.trim()
        if (chosen.isNotEmpty() && chosen.lowercase() !in setOf("auto", "default")) {
            body.put("models", JSONArray().put(chosen))
        }

        val headers = mapOf("apikey" to apiKey.trim().ifBlank { LLM_HORDE_ANON_KEY })
        val submitted = postJson(
            "https://aihorde.net/api/v2/generate/text/async",
            headers,
            body
        )
        val id = submitted.optString("id", "")
        if (id.isBlank()) {
            throw LlmException(400, submitted.optString("message", "تعذر إنشاء طلب في AI Horde."))
        }

        val deadline = System.currentTimeMillis() + LLM_HORDE_MAX_WAIT
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(LLM_HORDE_POLL_INTERVAL)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return "⚠️ أُلغي انتظار AI Horde."
            }
            val status = getJson(
                "https://aihorde.net/api/v2/generate/text/status/$id",
                headers
            )
            val generation = status.optJSONArray("generations")?.optJSONObject(0)
            if (status.optBoolean("faulted", false)) {
                val fault = generation?.optString("text", "").orEmpty().trim()
                val reason = fault.ifBlank { status.optString("message", "").trim() }
                return "⚠️ فشل AI Horde في توليد الرد" +
                    (if (reason.isNotBlank()) ": $reason" else ".")
            }
            val done = status.optBoolean("done", false) ||
                status.optInt("finished", 0) > 0
            if (done) {
                val text = generation?.optString("text", "").orEmpty().trim()
                return text.ifBlank { "لم يصل رد من AI Horde." }
            }
        }
        return "⏳ طابور AI Horde مزدحم. أعد المحاولة أو اختر موديلاً أخف."
    }

private data class OpenCodeEndpoints(
    val chat: String,
    val responses: String,
    val anthropic: String,
    val google: String
)

private fun openCodeEndpoints(provider: String, model: String): List<OpenCodeEndpoints> {
    val zenRoot = "https://opencode.ai/zen/v1"
    val zenGoRoot = "https://opencode.ai/zen/go/v1"
    val consoleRoot = "https://opencode.ai/inference"
    val consoleGoRoot = "https://opencode.ai/inference/go"
    val encodedModel = model.trim()

    fun zen(root: String) = OpenCodeEndpoints(
        chat = "$root/chat/completions",
        responses = "$root/responses",
        anthropic = "$root/messages",
        google = "$root/models/$encodedModel:generateContent"
    )

    fun console(root: String) = OpenCodeEndpoints(
        chat = "$root/openai/v1/chat/completions",
        responses = "$root/openai/v1/responses",
        anthropic = "$root/anthropic/v1/messages",
        google = "$root/google/v1beta/models/$encodedModel:generateContent"
    )

    return when (provider) {
        "opencode" -> listOf(zen(zenRoot), console(consoleRoot))
        "opencode-go" -> listOf(zen(zenGoRoot), console(consoleGoRoot))
        "opencode-console" -> listOf(console(consoleRoot))
        else -> listOf(console(consoleRoot))
    }
}

private fun openCodeShouldFallback(error: LlmException): Boolean {
    val text = error.message.orEmpty()
    if (text.contains("FreeUsageLimit", ignoreCase = true)) return false
    return error.status in setOf(400, 401, 403, 404, 405, 410, 422, 429) ||
        text.contains("Invalid credential", ignoreCase = true) ||
        text.contains("not found", ignoreCase = true) ||
        text.contains("unknown endpoint", ignoreCase = true) ||
        text.contains("model not found", ignoreCase = true)
}

/** يوجّه طلب OpenCode إلى الواجهة الصحيحة حسب عائلة الموديل (OpenAI / Anthropic / Google / Responses). */
internal fun LlmClient.callOpenCode(
        provider: String,
        message: String,
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        image: ImageAttachment?,
        systemPrompt: String
    ): String {
        val go = provider == "opencode-go"
        // OpenCode يطلب من العملاء الخارجيين أن يعرّفوا أنفسهم بـ User-Agent خاص بهم (يُرسل تلقائياً: LionAI)
        // وأن يرسلوا معرّف جلسة ثابتاً. لا ننتحل صفة عميل OpenCode الرسمي.
        val session = mapOf(
            "x-opencode-session" to openCodeSession,
            "x-opencode-request" to openCodeRequest
        )
        val id = model.trim().lowercase()

        val custom = endpoint(provider, "")
        if (provider == "qwen") {
            return callOpenAiCompatible(
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                apiKey, model, history, message,
                image = image, systemPrompt = systemPrompt
            )
        }

        if (provider == "hf") {
            return callOpenAiCompatible(
                "https://router.huggingface.co/v1/chat/completions",
                apiKey, model, history, message,
                image = image, systemPrompt = systemPrompt
            )
        }

        if (provider == "fireworks") {
            return callOpenAiCompatible(
                "https://api.fireworks.ai/inference/v1/chat/completions",
                apiKey, model, history, message,
                image = image, systemPrompt = systemPrompt
            )
        }

        if (provider == "perplexity") {
            return callOpenAiCompatible(
                "https://api.perplexity.ai/chat/completions",
                apiKey, model, history, message,
                image = image, systemPrompt = systemPrompt
            )
        }

        if (provider == "grok") {
            return callOpenAiCompatible(
                "https://api.x.ai/v1/chat/completions",
                apiKey, model, history, message,
                image = image, systemPrompt = systemPrompt
            )
        }

        if (custom.isNotBlank()) {
            return callOpenAiCompatible(
                custom, apiKey, model, history, message,
                extraHeaders = session, maxTokens = LLM_OPENAI_MAX_TOKENS,
                image = image, systemPrompt = systemPrompt
            )
        }

        if (id.startsWith("jev-")) {
            return "⚠️ موديل Jev من OpenCode يستخدم واجهة System One ولا يدعم الدردشة النصية. اختر موديلاً آخر."
        }

        val anthropicModels = setOf(
            "qwen3.5-plus", "qwen3.6-plus", "qwen3.6-plus-free", "qwen3.8-flash",
            "minimax-m2.1-free", "minimax-m2.5-free", "minimax-m3-free"
        )
        val anthropicGoModels = setOf(
            "qwen3.7-plus", "qwen3.8-max", "minimax-m2.7", "minimax-m3"
        )
        val useAnthropic = id.startsWith("claude-") ||
            id in anthropicModels || (go && id in anthropicGoModels)
        val useResponses = id.startsWith("gpt-") || id.startsWith("muse-") ||
            (id.startsWith("grok-") && id != "grok-code")

        var lastError: LlmException? = null
        for (endpoints in openCodeEndpoints(provider, model)) {
            try {
                if (useAnthropic) {
                    return callClaude(
                        message, apiKey, model, history, image, systemPrompt,
                        url = endpoints.anthropic,
                        headers = session + mapOf(
                            "x-api-key" to apiKey,
                            "anthropic-version" to "2023-06-01"
                        )
                    )
                }
                if (id.startsWith("gemini-")) {
                    return callGemini(
                        message, apiKey, model, history, image, systemPrompt,
                        url = endpoints.google,
                        headers = session + mapOf("x-goog-api-key" to apiKey)
                    )
                }
                if (useResponses) {
                    return callOpenAiResponses(
                        endpoints.responses, apiKey, model, history, message,
                        image = image, systemPrompt = systemPrompt, extraHeaders = session
                    )
                }
                return callOpenAiCompatible(
                    endpoints.chat, apiKey, model, history, message,
                    extraHeaders = session, maxTokens = LLM_OPENAI_MAX_TOKENS,
                    image = image, systemPrompt = systemPrompt
                )
            } catch (e: LlmException) {
                lastError = e
                if (!openCodeShouldFallback(e)) throw e
            }
        }
        throw lastError ?: LlmException(0, "تعذر الاتصال بـ OpenCode")
    }

/** واجهة OpenAI Responses المستخدمة في OpenCode لموديلات GPT/Grok/Muse. */
internal fun LlmClient.callOpenAiResponses(
        url: String,
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        message: String,
        image: ImageAttachment?,
        systemPrompt: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val input = JSONArray()
        history.forEach { turn ->
            input.put(JSONObject().put("role", turn.role).put("content", turn.content))
        }
        val userContent: Any = if (image == null) {
            message
        } else {
            JSONArray()
                .put(JSONObject().put("type", "input_text").put("text", message))
                .put(
                    JSONObject()
                        .put("type", "input_image")
                        .put("image_url", imageDataUri(image))
                )
        }
        input.put(JSONObject().put("role", "user").put("content", userContent))

        val body = JSONObject()
            .put("model", model)
            .put("input", input)
            .put("instructions", systemPrompt)
            .put("max_output_tokens", LLM_OPENAI_MAX_TOKENS)

        val headers = mutableMapOf<String, String>()
        if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"
        headers.putAll(extraHeaders)

        val response = postJson(url, headers, body)
        val direct = response.optString("output_text", "")
        if (direct.isNotBlank()) return direct

        val text = StringBuilder()
        val output = response.optJSONArray("output")
        if (output != null) {
            for (index in 0 until output.length()) {
                val item = output.optJSONObject(index) ?: continue
                if (item.optString("type") != "message") continue
                val content = item.optJSONArray("content") ?: continue
                for (partIndex in 0 until content.length()) {
                    val part = content.optJSONObject(partIndex) ?: continue
                    val type = part.optString("type")
                    if (type == "output_text" || type == "text") {
                        text.append(part.optString("text", ""))
                    }
                }
            }
        }
        return text.toString().ifBlank { "لم يصل رد من المزود." }
    }

internal fun LlmClient.imageDataUri(image: ImageAttachment): String =
    "data:${image.mime};base64,${image.base64}"
