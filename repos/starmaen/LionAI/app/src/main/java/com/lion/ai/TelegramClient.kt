package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * عميل Telegram Bot API الرسمي. لا يحتاج مكتبات خارجية، ويعمل مع أي بوت
 * يملك توكن من @BotFather. للوصول إلى حساب شخصي كامل (كل المحادثات) يلزم
 * TDLib وMTProto، وهو مسار منفصل موثّق في docs/PLATFORM_STUDY_AR.md.
 */
object TelegramClient {
    private const val BASE = "https://api.telegram.org"
    private const val UA = "LionAI/1.8 (Android)"

    fun call(token: String, method: String, body: JSONObject = JSONObject()): JSONObject {
        return when (val result = callRaw(token, method, body)) {
            is JSONObject -> result
            is JSONArray -> JSONObject().put("result", result)
            null -> JSONObject()
            else -> JSONObject().put("value", result)
        }
    }

    fun callRaw(token: String, method: String, body: JSONObject = JSONObject()): Any? {
        val clean = requireToken(token)
        val connection = (URL("$BASE/bot$clean/$method").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 65_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", UA)
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            if (!json.optBoolean("ok")) {
                val description = json.optString("description").ifBlank { "فشل طلب Telegram ($status)" }
                throw IllegalStateException("Telegram: $description (${json.optInt("error_code", status)})")
            }
            return json.opt("result")
        } finally {
            connection.disconnect()
        }
    }

    fun getMe(token: String): JSONObject = call(token, "getMe")

    fun getUpdates(token: String, offset: Long = 0, limit: Int = 50, timeoutSec: Int = 0): JSONArray {
        val body = JSONObject()
            .put("offset", offset)
            .put("limit", limit.coerceIn(1, 100))
            .put("timeout", timeoutSec.coerceIn(0, 50))
            .put("allowed_updates", JSONArray(listOf("message", "edited_message", "callback_query", "channel_post")))
        return callRaw(token, "getUpdates", body) as? JSONArray ?: JSONArray()
    }

    fun sendMessage(
        token: String,
        chatId: String,
        text: String,
        parseMode: String = "",
        replyTo: Long = 0,
        disablePreview: Boolean = false,
        threadId: Long = 0
    ): JSONObject {
        val body = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .put("disable_web_page_preview", disablePreview)
        if (parseMode.isNotBlank()) body.put("parse_mode", parseMode)
        if (replyTo > 0) body.put("reply_to_message_id", replyTo)
        if (threadId > 0) body.put("message_thread_id", threadId)
        return call(token, "sendMessage", body)
    }

    fun editMessageText(token: String, chatId: String, messageId: Long, text: String, parseMode: String = ""): JSONObject {
        val body = JSONObject().put("chat_id", chatId).put("message_id", messageId).put("text", text)
        if (parseMode.isNotBlank()) body.put("parse_mode", parseMode)
        return call(token, "editMessageText", body)
    }

    fun deleteMessage(token: String, chatId: String, messageId: Long): JSONObject =
        call(token, "deleteMessage", JSONObject().put("chat_id", chatId).put("message_id", messageId))

    fun forwardMessage(token: String, chatId: String, fromChatId: String, messageId: Long): JSONObject =
        call(
            token, "forwardMessage",
            JSONObject().put("chat_id", chatId).put("from_chat_id", fromChatId).put("message_id", messageId)
        )

    fun copyMessage(
        token: String, chatId: String, fromChatId: String, messageId: Long, caption: String = ""
    ): JSONObject {
        val body = JSONObject().put("chat_id", chatId).put("from_chat_id", fromChatId).put("message_id", messageId)
        if (caption.isNotBlank()) body.put("caption", caption)
        return call(token, "copyMessage", body)
    }

    fun pinMessage(token: String, chatId: String, messageId: Long, notify: Boolean = false): JSONObject =
        call(
            token, "pinChatMessage",
            JSONObject().put("chat_id", chatId).put("message_id", messageId).put("disable_notification", !notify)
        )

    fun unpinMessage(token: String, chatId: String, messageId: Long = 0): JSONObject {
        val body = JSONObject().put("chat_id", chatId)
        if (messageId > 0) body.put("message_id", messageId)
        return call(token, "unpinChatMessage", body)
    }

    fun getChat(token: String, chatId: String): JSONObject =
        call(token, "getChat", JSONObject().put("chat_id", chatId))

    fun getChatAdministrators(token: String, chatId: String): JSONArray =
        callRaw(token, "getChatAdministrators", JSONObject().put("chat_id", chatId)) as? JSONArray ?: JSONArray()

    fun getChatMemberCount(token: String, chatId: String): Int =
        (callRaw(token, "getChatMemberCount", JSONObject().put("chat_id", chatId)) as? Number)?.toInt() ?: 0

    fun getChatMember(token: String, chatId: String, userId: Long): JSONObject =
        call(token, "getChatMember", JSONObject().put("chat_id", chatId).put("user_id", userId))

    fun setWebhook(token: String, url: String, secret: String = ""): JSONObject {
        val body = JSONObject().put("url", url)
        if (secret.isNotBlank()) body.put("secret_token", secret)
        return call(token, "setWebhook", body)
    }

    fun deleteWebhook(token: String): JSONObject = call(token, "deleteWebhook")

    fun getWebhookInfo(token: String): JSONObject = call(token, "getWebhookInfo")

    fun sendChatAction(token: String, chatId: String, action: String = "typing"): JSONObject =
        call(token, "sendChatAction", JSONObject().put("chat_id", chatId).put("action", action))

    fun answerCallbackQuery(token: String, callbackId: String, text: String = ""): JSONObject {
        val body = JSONObject().put("callback_query_id", callbackId)
        if (text.isNotBlank()) body.put("text", text)
        return call(token, "answerCallbackQuery", body)
    }

    fun sendPoll(
        token: String, chatId: String, question: String, options: List<String>, anonymous: Boolean = true
    ): JSONObject {
        require(options.size >= 2) { "الاستطلاع يحتاج خيارين على الأقل" }
        return call(
            token, "sendPoll",
            JSONObject()
                .put("chat_id", chatId)
                .put("question", question)
                .put("options", JSONArray(options.take(10)))
                .put("is_anonymous", anonymous)
        )
    }

    fun setMyCommands(token: String, commands: List<Pair<String, String>>): JSONObject {
        val array = JSONArray()
        commands.forEach { (command, description) ->
            array.put(JSONObject().put("command", command).put("description", description))
        }
        return call(token, "setMyCommands", JSONObject().put("commands", array))
    }

    fun getMyCommands(token: String): JSONArray =
        callRaw(token, "getMyCommands") as? JSONArray ?: JSONArray()

    fun createInviteLink(token: String, chatId: String, name: String = "", memberLimit: Int = 0): JSONObject {
        val body = JSONObject().put("chat_id", chatId)
        if (name.isNotBlank()) body.put("name", name)
        if (memberLimit > 0) body.put("member_limit", memberLimit)
        return call(token, "createChatInviteLink", body)
    }

    fun banChatMember(token: String, chatId: String, userId: Long, untilDate: Long = 0): JSONObject {
        val body = JSONObject().put("chat_id", chatId).put("user_id", userId)
        if (untilDate > 0) body.put("until_date", untilDate)
        return call(token, "banChatMember", body)
    }

    fun unbanChatMember(token: String, chatId: String, userId: Long): JSONObject =
        call(token, "unbanChatMember", JSONObject().put("chat_id", chatId).put("user_id", userId).put("only_if_banned", true))

    fun promoteChatMember(token: String, chatId: String, userId: Long, admin: Boolean = true): JSONObject {
        return call(
            token, "promoteChatMember",
            JSONObject()
                .put("chat_id", chatId)
                .put("user_id", userId)
                .put("is_anonymous", false)
                .put("can_manage_chat", admin)
                .put("can_delete_messages", admin)
                .put("can_manage_video_chats", admin)
                .put("can_restrict_members", admin)
                .put("can_promote_members", false)
                .put("can_change_info", admin)
                .put("can_invite_users", admin)
                .put("can_pin_messages", admin)
                .put("can_manage_topics", admin)
        )
    }

    fun sendMedia(
        token: String,
        chatId: String,
        type: String,
        file: File?,
        url: String,
        caption: String,
        parseMode: String = "",
        replyTo: Long = 0,
        threadId: Long = 0
    ): JSONObject {
        val normalized = when (type.lowercase()) {
            "video" -> "video"
            "audio", "voice" -> "audio"
            "document", "file" -> "document"
            else -> "photo"
        }
        val field = normalized
        if (file != null) {
            if (!file.isFile) throw IllegalArgumentException("الملف غير موجود: ${file.path}")
            if (file.length() > 50L * 1024 * 1024) throw IllegalArgumentException("حجم الملف يتجاوز حد Telegram (50MB)")
            val fields = linkedMapOf(
                "chat_id" to chatId,
                "caption" to caption,
                "disable_notification" to "false"
            )
            if (parseMode.isNotBlank()) fields["parse_mode"] = parseMode
            if (replyTo > 0) fields["reply_to_message_id"] = replyTo.toString()
            if (threadId > 0) fields["message_thread_id"] = threadId.toString()
            return multipart(token, "send${field.replaceFirstChar { it.uppercase() }}", fields, field, file)
        }
        if (url.isBlank()) throw IllegalArgumentException("حدد رابطاً أو مسار ملف داخل مساحة العمل")
        val body = JSONObject().put("chat_id", chatId).put(field, url)
        if (caption.isNotBlank()) body.put("caption", caption)
        if (parseMode.isNotBlank()) body.put("parse_mode", parseMode)
        if (replyTo > 0) body.put("reply_to_message_id", replyTo)
        if (threadId > 0) body.put("message_thread_id", threadId)
        val method = when (field) {
            "video" -> "sendVideo"
            "audio" -> "sendAudio"
            "document" -> "sendDocument"
            else -> "sendPhoto"
        }
        return call(token, method, body)
    }

    private fun multipart(
        token: String,
        method: String,
        fields: Map<String, String>,
        fileField: String,
        file: File
    ): JSONObject {
        val boundary = "LionAI" + System.nanoTime().toString(16)
        val output = ByteArrayOutputStream()
        fun write(text: String) = output.write(text.toByteArray(Charsets.UTF_8))
        fields.forEach { (key, value) ->
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"$key\"\r\n\r\n")
            write("$value\r\n")
        }
        write("--$boundary\r\n")
        write(
            "Content-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name.replace("\"", "")}\"\r\n"
        )
        write("Content-Type: application/octet-stream\r\n\r\n")
        file.inputStream().use { input -> input.copyTo(output) }
        write("\r\n--$boundary--\r\n")

        val connection = (URL("$BASE/bot${requireToken(token)}/$method").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
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
            if (!json.optBoolean("ok")) {
                throw IllegalStateException(
                    "Telegram: ${json.optString("description").ifBlank { "فشل الرفع ($status)" }}"
                )
            }
            return json.optJSONObject("result") ?: JSONObject()
        } finally {
            connection.disconnect()
        }
    }

    fun getFile(token: String, fileId: String): JSONObject =
        call(token, "getFile", JSONObject().put("file_id", fileId))

    fun downloadFile(token: String, fileId: String, target: File): File {
        val info = getFile(token, fileId)
        val path = info.optString("file_path")
        if (path.isBlank()) throw IllegalStateException("Telegram لم يُرجع مسار الملف")
        val connection = (URL("$BASE/file/bot${requireToken(token)}/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 180_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("فشل تنزيل ملف Telegram ($status)")
            target.parentFile?.mkdirs()
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            return target
        } finally {
            connection.disconnect()
        }
    }

    fun requireToken(token: String): String =
        token.trim().removePrefix("bot").takeIf { it.contains(':') }
            ?: throw IllegalStateException("توكن Telegram غير صالح (يجب أن يحتوي ':')")

    fun formatUser(user: JSONObject?): String {
        if (user == null) return "—"
        val name = listOf(user.optString("first_name"), user.optString("last_name"))
            .filter { it.isNotBlank() }.joinToString(" ")
        return if (user.optString("username").isNotBlank()) {
            "$name (@${user.optString("username")})"
        } else {
            name.ifBlank { "—" }
        }
    }
}
