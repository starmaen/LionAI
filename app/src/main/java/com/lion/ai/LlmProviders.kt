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
                JSONObject().put("maxOutputTokens", LLM_GEMINI_MAX_TOKENS)
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
            .put("max_tokens", LLM_CLAUDE_MAX_TOKENS)
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

internal fun LlmClient.imageDataUri(image: ImageAttachment): String =
    "data:${image.mime};base64,${image.base64}"
