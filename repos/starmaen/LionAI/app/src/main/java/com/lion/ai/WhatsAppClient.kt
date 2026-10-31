package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * عميل WhatsApp Business Cloud API الرسمي (Meta Graph API).
 * صيغة التوكن في التطبيق: PHONE_NUMBER_ID:ACCESS_TOKEN
 *
 * ملاحظة صريحة: هذا المسار يخص رقم أعمال على WhatsApp Business Platform،
 * ولا يقرأ محادثات حساب WhatsApp الشخصي. لقراءة إشعارات/ردود الحساب الشخصي
 * على الجهاز يُستخدم LionNotificationListener (قناة الإشعارات) الموثّقة.
 */
object WhatsAppClient {
    private const val BASE = "https://graph.facebook.com/v21.0"
    private const val UA = "LionAI/1.8 (Android)"

    data class Credentials(val phoneId: String, val accessToken: String)

    fun parse(raw: String): Credentials {
        val clean = raw.trim()
        val index = clean.indexOf(':')
        if (index <= 0 || index == clean.length - 1) {
            throw IllegalStateException("توكن WhatsApp يجب أن يكون بصيغة PHONE_NUMBER_ID:ACCESS_TOKEN")
        }
        val phone = clean.substring(0, index).trim()
        val token = clean.substring(index + 1).trim()
        if (!phone.all { it.isDigit() }) throw IllegalStateException("PHONE_NUMBER_ID يجب أن يكون أرقاماً فقط")
        if (token.isBlank()) throw IllegalStateException("ACCESS_TOKEN فارغ")
        return Credentials(phone, token)
    }

    fun call(credentials: Credentials, path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val url = if (path.startsWith("http")) path else BASE + path
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            useCaches = false
            setRequestProperty("Authorization", "Bearer ${credentials.accessToken}")
            setRequestProperty("User-Agent", UA)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            if (status !in 200..299) {
                val message = json.optJSONObject("error")?.optString("message")
                    .orEmpty().ifBlank { raw.take(300).ifBlank { "فشل طلب WhatsApp ($status)" } }
                throw IllegalStateException("WhatsApp: $message ($status)")
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun messages(credentials: Credentials): String = "/${credentials.phoneId}/messages"

    private fun baseMessage(to: String): JSONObject = JSONObject()
        .put("messaging_product", "whatsapp")
        .put("recipient_type", "individual")
        .put("to", to.trim())

    fun status(rawToken: String): String {
        val credentials = parse(rawToken)
        val o = call(
            credentials,
            "/${credentials.phoneId}?fields=display_phone_number,verified_name,quality_rating,platform_type,code_verification_status"
        )
        return buildString {
            append("📱 ").append(o.optString("verified_name").ifBlank { "WhatsApp Business" })
            append(" — ").append(o.optString("display_phone_number"))
            append("\nالحالة: ").append(o.optString("code_verification_status").ifBlank { "—" })
            append(" · الجودة: ").append(o.optString("quality_rating").ifBlank { "—" })
            append(" · المنصة: ").append(o.optString("platform_type").ifBlank { "—" })
        }
    }

    fun sendText(rawToken: String, to: String, text: String, previewUrl: Boolean = false): String {
        val credentials = parse(rawToken)
        val body = baseMessage(to)
            .put("type", "text")
            .put("text", JSONObject().put("preview_url", previewUrl).put("body", text.take(4096)))
        val o = call(credentials, messages(credentials), "POST", body)
        return "✅ أُرسلت رسالة WhatsApp إلى $to — id=${messageId(o)}"
    }

    fun sendTemplate(
        rawToken: String,
        to: String,
        templateName: String,
        language: String = "ar",
        components: JSONArray? = null
    ): String {
        val credentials = parse(rawToken)
        val template = JSONObject()
            .put("name", templateName)
            .put("language", JSONObject().put("code", language))
        if (components != null && components.length() > 0) template.put("components", components)
        val body = baseMessage(to).put("type", "template").put("template", template)
        val o = call(credentials, messages(credentials), "POST", body)
        return "✅ أُرسل قالب «$templateName» إلى $to — id=${messageId(o)}"
    }

    fun sendMedia(
        rawToken: String,
        to: String,
        type: String,
        link: String = "",
        path: String = "",
        caption: String = "",
        filename: String = ""
    ): String {
        val credentials = parse(rawToken)
        val normalized = when (type.lowercase()) {
            "image", "photo", "صورة" -> "image"
            "audio", "voice", "صوت" -> "audio"
            "video", "فيديو" -> "video"
            else -> "document"
        }
        val mediaId = if (path.isNotBlank()) {
            val file = File(path)
            if (!file.isFile) throw IllegalArgumentException("الملف غير موجود: $path")
            uploadMedia(credentials, file)
        } else {
            ""
        }
        val media = JSONObject()
        if (mediaId.isNotBlank()) media.put("id", mediaId)
        else if (link.isNotBlank()) media.put("link", link)
        else throw IllegalArgumentException("حدد link أو path لملف وسائط")
        if (caption.isNotBlank() && normalized != "audio") media.put("caption", caption.take(1024))
        if (filename.isNotBlank() && normalized == "document") media.put("filename", filename)
        val body = baseMessage(to).put("type", normalized).put(normalized, media)
        val o = call(credentials, messages(credentials), "POST", body)
        return "✅ أُرسلت وسائط $normalized إلى $to — id=${messageId(o)}"
    }

    fun markRead(rawToken: String, messageId: String): String {
        val credentials = parse(rawToken)
        val body = JSONObject()
            .put("messaging_product", "whatsapp")
            .put("status", "read")
            .put("message_id", messageId)
        call(credentials, messages(credentials), "POST", body)
        return "✅ عُلّمت الرسالة $messageId كمقروءة"
    }

    fun listTemplates(rawToken: String, wabaId: String): String {
        val credentials = parse(rawToken)
        val o = call(credentials, "/${encode(wabaId.trim())}/message_templates?limit=100")
        val array = o.optJSONArray("data") ?: JSONArray()
        if (array.length() == 0) return "لا توجد قوالب."
        return (0 until array.length()).joinToString("\n") { index ->
            val item = array.optJSONObject(index) ?: return@joinToString ""
            "• ${item.optString("name")} [${item.optString("language")}] — ${item.optString("status")} (${item.optString("category")})"
        }
    }

    fun profile(rawToken: String): String {
        val credentials = parse(rawToken)
        val o = call(
            credentials,
            "/${credentials.phoneId}/whatsapp_business_profile?fields=about,address,description,email,profile_picture_url,websites,vertical"
        )
        val data = o.optJSONArray("data")?.optJSONObject(0) ?: o
        return buildString {
            append("🏢 ").append(data.optString("description").ifBlank { "—" }).append('\n')
            append("about: ").append(data.optString("about").ifBlank { "—" }).append('\n')
            append("email: ").append(data.optString("email").ifBlank { "—" }).append('\n')
            append("address: ").append(data.optString("address").ifBlank { "—" }).append('\n')
            append("vertical: ").append(data.optString("vertical").ifBlank { "—" })
        }
    }

    fun phoneNumbers(rawToken: String, wabaId: String): String {
        val credentials = parse(rawToken)
        val o = call(credentials, "/${encode(wabaId.trim())}/phone_numbers?limit=100")
        val array = o.optJSONArray("data") ?: JSONArray()
        if (array.length() == 0) return "لا توجد أرقام."
        return (0 until array.length()).joinToString("\n") { index ->
            val item = array.optJSONObject(index) ?: return@joinToString ""
            "• ${item.optString("display_phone_number")} — ${item.optString("verified_name")} " +
                "(${item.optString("quality_rating")}) id=${item.optString("id")}"
        }
    }

    fun downloadMedia(rawToken: String, mediaId: String, target: File): File {
        val credentials = parse(rawToken)
        val info = call(credentials, "/${encode(mediaId)}")
        val url = info.optString("url")
        if (url.isBlank()) throw IllegalStateException("WhatsApp لم يُرجع رابط الوسائط")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 180_000
            instanceFollowRedirects = true
            setRequestProperty("Authorization", "Bearer ${credentials.accessToken}")
            setRequestProperty("User-Agent", UA)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("فشل تنزيل وسائط WhatsApp ($status)")
            target.parentFile?.mkdirs()
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            return target
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadMedia(credentials: Credentials, file: File): String {
        if (file.length() > 100L * 1024 * 1024) throw IllegalArgumentException("حجم الوسائط يتجاوز 100MB")
        val boundary = "LionWA" + System.nanoTime().toString(16)
        val output = ByteArrayOutputStream()
        fun write(text: String) = output.write(text.toByteArray(Charsets.UTF_8))
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"messaging_product\"\r\n\r\n")
        write("whatsapp\r\n")
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name.replace("\"", "")}\"\r\n")
        write("Content-Type: application/octet-stream\r\n\r\n")
        file.inputStream().use { input -> input.copyTo(output) }
        write("\r\n--$boundary--\r\n")

        val connection = (URL("$BASE/${credentials.phoneId}/media").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${credentials.accessToken}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Content-Length", output.size().toString())
            setRequestProperty("User-Agent", UA)
        }
        try {
            connection.outputStream.use { it.write(output.toByteArray()) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            if (status !in 200..299) {
                throw IllegalStateException(
                    "WhatsApp upload: ${json.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "فشل ($status)" }}"
                )
            }
            return json.optString("id").ifBlank { throw IllegalStateException("لم يُرجع WhatsApp معرّف الوسائط") }
        } finally {
            connection.disconnect()
        }
    }

    private fun messageId(o: JSONObject): String =
        o.optJSONArray("messages")?.optJSONObject(0)?.optString("id").orEmpty()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

}
