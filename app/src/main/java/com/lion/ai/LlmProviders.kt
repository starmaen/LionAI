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
        val root = if (go) "https://opencode.ai/inference/go" else "https://opencode.ai/inference"
        val session = mapOf("x-opencode-session" to openCodeSession)
        val id = model.trim().lowercase()

        // وسيط مخصص = بروكسي متوافق مع OpenAI، فنمرر كل شيء عبره.
        val custom = endpoint(provider, "")
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

        // موديلات OpenCode التي تُقدَّم عبر واجهة Anthropic (حسب كتالوج OpenCode الرسمي).
        val anthropicModels = setOf(
            "qwen3.5-plus", "qwen3.6-plus", "qwen3.6-plus-free", "qwen3.8-flash",
            "minimax-m2.1-free", "minimax-m2.5-free", "minimax-m3-free"
        )
        val anthropicGoModels = setOf(
            "qwen3.7-plus", "qwen3.8-max", "minimax-m2.7", "minimax-m3"
        )
        val useAnthropic = id.startsWith("claude-") ||
            id in anthropicModels || (go && id in anthropicGoModels)
        if (useAnthropic) {
            return callClaude(
                message, apiKey, model, history, image, systemPrompt,
                url = "$root/anthropic/v1/messages",
                headers = session + mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01"
                )
            )
        }

        if (id.startsWith("gemini-")) {
            return callGemini(
                message, apiKey, model, history, image, systemPrompt,
                url = "$root/google/v1beta/models/$model:generateContent",
                headers = session + mapOf("x-goog-api-key" to apiKey)
            )
        }

        val useResponses = id.startsWith("gpt-") || id.startsWith("muse-") ||
            (id.startsWith("grok-") && id != "grok-code")
        if (useResponses) {
            return callOpenAiResponses(
                "$root/openai/v1/responses", apiKey, model, history, message,
                image = image, systemPrompt = systemPrompt, extraHeaders = session
            )
        }

        return callOpenAiCompatible(
            "$root/openai/v1/chat/completions", apiKey, model, history, message,
            extraHeaders = session, maxTokens = LLM_OPENAI_MAX_TOKENS,
            image = image, systemPrompt = systemPrompt
        )
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
