package com.lion.ai

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * DeepAI: واجهة api.deepai.org الرسمية. ليست متوافقة مع OpenAI، لذلك لها مسار خاص:
 * POST https://api.deepai.org/api/<model> ، الترويسة api-key ، الحقل text (form)،
 * والرد JSON فيه الحقل output. اسم «الموديل» في التطبيق = اسم المسار (الافتراضي text-generator).
 */
internal fun LlmClient.callDeepAi(
    message: String,
    apiKey: String,
    model: String,
    history: List<ChatTurn>,
    systemPrompt: String
): String {
    // الواجهة تأخذ نصاً واحداً: ندمج التعليمات والسجل والرسالة في نص واحد.
    val prompt = buildString {
        if (systemPrompt.isNotBlank()) append(systemPrompt.trim()).append("\n\n")
        history.takeLast(12).forEach { turn ->
            append(if (turn.role == "user") "User: " else "Assistant: ")
                .append(turn.content.trim()).append('\n')
        }
        append("User: ").append(message.trim()).append("\nAssistant:")
    }.take(8_000)

    val slug = model.trim().ifBlank { "text-generator" }.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    val url = endpoint("deepai", "https://api.deepai.org/api/$slug")
    throttleFor(url)

    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = LLM_CONNECT_TIMEOUT
        readTimeout = currentReadTimeout()
        doOutput = true
        useCaches = false
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", LLM_USER_AGENT)
        setRequestProperty("api-key", apiKey)
    }
    try {
        connection.outputStream.use {
            it.write(("text=" + URLEncoder.encode(prompt, "UTF-8")).toByteArray(Charsets.UTF_8))
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(raw) }.getOrNull()
        if (status !in 200..299) {
            val why = json?.optString("status")?.takeIf { it.isNotBlank() }
                ?: json?.optString("err")?.takeIf { it.isNotBlank() }
                ?: raw.take(300).ifBlank { "خطأ غير معروف" }
            throw LlmException(status, why)
        }
        val out = json?.optString("output", "").orEmpty().trim()
        // قد يكرر النموذج نص الطلب أو يبدأ بـ Assistant: فننظف البداية فقط.
        return out.removePrefix("Assistant:").trim().ifBlank { "لم يصل رد من DeepAI." }
    } finally {
        connection.disconnect()
    }
}
