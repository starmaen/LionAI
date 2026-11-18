package com.lion.ai

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object DeepAi {
    const val BASE = "https://api.deepai.org/api/"
    private val VERSIONS = setOf("hd", "genius", "super_genius")
    private val TEXT_MODELS = setOf("text2img", "image-editor")
    private val IMAGE_ONLY = setOf("colorizer", "background-remover", "torch-srgan")
    private val URL_RE = Regex("https?://\\S+")

    data class Plan(val endpoint: String, val fields: Map<String, String>, val needsImage: Boolean)

    fun plan(model: String, message: String): Plan {
        val raw = model.trim().ifBlank { "text2img" }
        val name = raw.substringBefore(':').lowercase()
        val version = raw.substringAfter(':', "").lowercase()
        require(name in TEXT_MODELS || name in IMAGE_ONLY) { "نموذج DeepAI غير معروف: $name" }
        val needsImage = name == "image-editor" || name in IMAGE_ONLY
        val fields = linkedMapOf<String, String>()
        if (name in TEXT_MODELS) {
            val text = if (needsImage) message.replace(URL_RE, "").trim() else message.trim()
            require(text.isNotBlank()) { "اكتب وصف الصورة أو التعديل المطلوب" }
            fields["text"] = text
            if (version in VERSIONS) fields["image_generator_version"] = version
        }
        return Plan(name, fields, needsImage)
    }

    fun imageUrl(message: String): String? =
        URL_RE.find(message)?.value?.trimEnd('.', ',', ')', '،')

    fun output(status: Int, raw: String): String {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val url = json?.optString("output_url", "").orEmpty()
        if (status in 200..299 && url.isNotBlank()) return url
        val err = json?.optString("err", "").orEmpty()
        val st = json?.optString("status", "").orEmpty()
        val detail = err.ifBlank { st }.ifBlank { raw.take(160) }
        val hint = when (status) {
            401 -> "DeepAI: المفتاح مفقود أو غير صالح أو تجاوزت الحصة (واجهة API تتطلب DeepAI Pro). "
            410 -> "DeepAI: هذا الموديل أُزيل. "
            else -> ""
        }
        throw LlmException(if (status in 200..299) 500 else status, hint + detail)
    }

    fun multipart(
        boundary: String,
        fields: Map<String, String>,
        file: Triple<String, String, ByteArray>?
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        fields.forEach { (k, v) ->
            w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        }
        file?.let { (name, mime, bytes) ->
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            w("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"$safe\"\r\nContent-Type: $mime\r\n\r\n")
            out.write(bytes)
            w("\r\n")
        }
        w("--$boundary--\r\n")
        return out.toByteArray()
    }
}

internal fun LlmClient.callDeepAi(
    message: String,
    apiKey: String,
    model: String,
    image: ImageAttachment?
): String {
    val plan = try {
        DeepAi.plan(model, message)
    } catch (e: IllegalArgumentException) {
        return "⚠️ ${e.message}"
    }
    val fields = LinkedHashMap<String, String>(plan.fields)
    var file: Triple<String, String, ByteArray>? = null
    if (plan.needsImage) {
        val att = image?.all()?.firstOrNull()
        if (att != null) {
            file = Triple(att.name, att.mime, java.util.Base64.getDecoder().decode(att.base64))
        } else {
            fields["image"] = DeepAi.imageUrl(message)
                ?: return "⚠️ هذا الموديل يحتاج صورة: أرفق صورة أو ضع رابطها في الرسالة."
        }
    }
    val boundary = "----LionAI" + UUID.randomUUID().toString().replace("-", "")
    val body = DeepAi.multipart(boundary, fields, file)
    val conn = (URL(DeepAi.BASE + plan.endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = LLM_CONNECT_TIMEOUT
        readTimeout = currentReadTimeout()
        doOutput = true
        useCaches = false
        setRequestProperty("api-key", apiKey)
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", LLM_USER_AGENT)
    }
    try {
        conn.outputStream.use { it.write(body) }
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        return "🖼️ تم عبر DeepAI (${plan.endpoint}):\n" + DeepAi.output(status, raw)
    } finally {
        conn.disconnect()
    }
}
