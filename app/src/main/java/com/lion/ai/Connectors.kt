package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

/** تنفيذ أدوات الموصلات: Drive، Gmail، Calendar، Notion، Slack، وخدمة REST مخصّصة. */
object Connectors {
    private const val DRIVE = "https://www.googleapis.com/drive/v3"
    private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me"
    private const val CALENDAR = "https://www.googleapis.com/calendar/v3"
    private const val NOTION = "https://api.notion.com/v1"
    private const val SLACK = "https://slack.com/api"

    data class Response(val code: Int, val body: String, val contentType: String)

    fun ready(prefs: SharedPreferences, id: String): Boolean = ConnectorAuth.isConnected(prefs, id)

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun quote(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    private fun safeUrl(raw: String): URL {
        val url = URL(raw)
        if (url.protocol != "https") throw IllegalArgumentException("يُسمح بعناوين HTTPS فقط")
        val host = url.host.lowercase()
        val blocked = host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1" ||
            host == "0.0.0.0" || host == "::1" || host == "169.254.169.254" ||
            host == "metadata.google.internal" || host.startsWith("10.") ||
            host.startsWith("192.168.") || host.startsWith("169.254.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\.").containsMatchIn(host)
        if (blocked) throw IllegalArgumentException("عنوان داخلي غير مسموح")
        return url
    }

    private fun request(
        method: String,
        url: String,
        token: String = "",
        body: String = "",
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = 2_000_000
    ): Response {
        val urlObj = safeUrl(url)
        val conn = urlObj.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 20_000
        conn.readTimeout = 40_000
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (method != "GET" && method != "HEAD") {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val type = conn.contentType.orEmpty()
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { readLimited(it, maxBytes) }.orEmpty()
        return Response(code, text, type)
    }

    private fun readLimited(stream: java.io.InputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val read = stream.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toString("UTF-8")
    }

    private fun bytesOf(ctx: ToolContext, path: String, maxBytes: Int = 20_000_000): ByteArray {
        val file = FileTools.file(ctx.context, path)
        if (!file.isFile) throw IllegalArgumentException("الملف غير موجود: $path")
        if (file.length() > maxBytes) throw IllegalArgumentException("الملف أكبر من الحد المسموح")
        return file.readBytes()
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "zip" -> "application/zip"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }

    private suspend fun google(ctx: ToolContext, id: String, method: String, url: String, body: String = ""): Response {
        val token = ConnectorAuth.token(ctx.prefs, id)
        return request(method, url, token, body)
    }

    private fun ensureOk(res: Response, service: String): Response {
        if (res.code !in 200..299) {
            val detail = runCatching {
                val json = JSONObject(res.body)
                json.optString("error_description").ifBlank {
                    json.optJSONObject("error")?.optString("message").orEmpty()
                }.ifBlank { json.optString("message") }
            }.getOrDefault("")
            throw IllegalStateException("$service ${res.code}: ${detail.ifBlank { res.body.take(300) }}")
        }
        return res
    }

    // ---------------- Google Drive ----------------

    suspend fun driveSearch(ctx: ToolContext, query: String, max: Int): String {
        val q = "(name contains '${quote(query)}' or fullText contains '${quote(query)}') and trashed = false"
        val url = "$DRIVE/files?q=${enc(q)}&pageSize=${max.coerceIn(1, 50)}" +
            "&fields=files(id,name,mimeType,size,modifiedTime,webViewLink)&orderBy=modifiedTime desc"
        val res = ensureOk(google(ctx, "google_drive", "GET", url), "Drive")
        val files = JSONObject(res.body).optJSONArray("files") ?: JSONArray()
        if (files.length() == 0) return "لا توجد ملفات مطابقة."
        return (0 until files.length()).joinToString("\n") { index ->
            val f = files.optJSONObject(index) ?: JSONObject()
            "• ${f.optString("name")} [${f.optString("mimeType").substringAfterLast('.')}] " +
                "id=${f.optString("id")} — ${f.optString("modifiedTime")}\n  ${f.optString("webViewLink")}"
        }
    }

    suspend fun driveRead(ctx: ToolContext, fileId: String, maxChars: Int): String {
        val metaRes = ensureOk(
            google(ctx, "google_drive", "GET", "$DRIVE/files/$fileId?fields=id,name,mimeType,size,webViewLink"),
            "Drive"
        )
        val meta = JSONObject(metaRes.body)
        val mime = meta.optString("mimeType")
        val name = meta.optString("name")
        val link = meta.optString("webViewLink")
        val max = maxChars.coerceIn(1000, 30_000)
        val looksText = mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") ||
            mime.contains("csv") || mime.contains("javascript") || mime.startsWith("application/vnd.google-apps")
        if (!looksText) {
            return "📄 $name ($mime, ${FileTools.formatSize(meta.optLong("size"))})\n$link\nملف ثنائي؛ استخدم رابط العرض أو نزّله بأداة أخرى."
        }
        val text = if (mime.startsWith("application/vnd.google-apps")) {
            ensureOk(
                google(ctx, "google_drive", "GET", "$DRIVE/files/$fileId/export?mimeType=text/plain"),
                "Drive"
            ).body
        } else {
            ensureOk(
                google(ctx, "google_drive", "GET", "$DRIVE/files/$fileId?alt=media"),
                "Drive"
            ).body
        }
        return "📄 $name ($mime)\n$link\n\n${text.take(max)}" +
            if (text.length > max) "\n… [اقتُطع]" else ""
    }

    suspend fun driveMkdir(ctx: ToolContext, name: String, folderId: String): String {
        val metadata = JSONObject().put("name", name).put("mimeType", "application/vnd.google-apps.folder")
        if (folderId.isNotBlank()) metadata.put("parents", JSONArray().put(folderId))
        val res = ensureOk(
            google(ctx, "google_drive", "POST", "$DRIVE/files?fields=id,name,webViewLink", metadata.toString()),
            "Drive"
        )
        val json = JSONObject(res.body)
        return "✅ أُنشئ المجلد ${json.optString("name")} (id=${json.optString("id")})\n${json.optString("webViewLink")}"
    }

    suspend fun driveUpload(
        ctx: ToolContext,
        name: String,
        path: String,
        content: String,
        mime: String,
        folderId: String
    ): String {
        val bytes = if (path.isNotBlank()) bytesOf(ctx, path) else content.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) throw IllegalArgumentException("الملف فارغ أو لم تحدد content/path")
        val contentType = mime.ifBlank { mimeOf(name) }
        val metadata = JSONObject().put("name", name).put("mimeType", contentType)
        if (folderId.isNotBlank()) metadata.put("parents", JSONArray().put(folderId))
        val created = ensureOk(
            google(ctx, "google_drive", "POST", "$DRIVE/files?fields=id,name,webViewLink", metadata.toString()),
            "Drive"
        )
        val id = JSONObject(created.body).optString("id")
        val url = "$DRIVE_UPLOAD/files/$id?uploadType=media"
        val token = ConnectorAuth.token(ctx.prefs, "google_drive")
        val res = uploadBytes(url, token, bytes, contentType)
        ensureOk(res, "Drive")
        val link = runCatching { JSONObject(res.body).optString("webViewLink") }.getOrDefault("")
        return "✅ رُفع $name (${FileTools.formatSize(bytes.size.toLong())}) إلى Drive\n$link"
    }

    private fun uploadBytes(url: String, token: String, bytes: ByteArray, contentType: String): Response {
        val conn = safeUrl(url).openConnection() as HttpURLConnection
        conn.requestMethod = "PATCH"
        conn.doOutput = true
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", contentType)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return Response(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty(), conn.contentType.orEmpty())
    }

    // ---------------- Gmail ----------------

    suspend fun gmailSearch(ctx: ToolContext, query: String, max: Int): String {
        val limit = max.coerceIn(1, 15)
        val url = "$GMAIL/messages?q=${enc(query)}&maxResults=$limit"
        val res = ensureOk(google(ctx, "gmail", "GET", url), "Gmail")
        val messages = JSONObject(res.body).optJSONArray("messages") ?: JSONArray()
        if (messages.length() == 0) return "لا توجد رسائل مطابقة."
        return (0 until messages.length()).joinToString("\n") { index ->
            val id = messages.optJSONObject(index)?.optString("id").orEmpty()
            val detail = runCatching {
                val d = ensureOk(
                    google(
                        ctx, "gmail", "GET",
                        "$GMAIL/messages/$id?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date"
                    ),
                    "Gmail"
                )
                JSONObject(d.body)
            }.getOrNull()
            val headers = detail?.optJSONObject("payload")?.optJSONArray("headers")
            fun header(name: String): String {
                if (headers == null) return ""
                for (i in 0 until headers.length()) {
                    val h = headers.optJSONObject(i) ?: continue
                    if (h.optString("name").equals(name, true)) return h.optString("value")
                }
                return ""
            }
            val snippet = detail?.optString("snippet").orEmpty()
            "• ${header("Subject").ifBlank { "(بدون موضوع)" }}\n  من: ${header("From")} — ${header("Date")}\n  id=$id\n  ${snippet.take(160)}"
        }
    }

    suspend fun gmailRead(ctx: ToolContext, messageId: String, maxChars: Int): String {
        val res = ensureOk(google(ctx, "gmail", "GET", "$GMAIL/messages/$messageId?format=full"), "Gmail")
        val json = JSONObject(res.body)
        val payload = json.optJSONObject("payload")
        val headers = payload?.optJSONArray("headers")
        fun header(name: String): String {
            if (headers == null) return ""
            for (i in 0 until headers.length()) {
                val h = headers.optJSONObject(i) ?: continue
                if (h.optString("name").equals(name, true)) return h.optString("value")
            }
            return ""
        }
        val body = extractGmailBody(payload)
        val max = maxChars.coerceIn(1000, 30_000)
        return buildString {
            append("✉️ ").append(header("Subject").ifBlank { "(بدون موضوع)" }).append('\n')
            append("من: ").append(header("From")).append('\n')
            append("إلى: ").append(header("To")).append('\n')
            append("التاريخ: ").append(header("Date")).append("\n\n")
            append(if (body.isBlank()) json.optString("snippet") else body.take(max))
            if (body.length > max) append("\n… [اقتُطع]")
        }
    }

    private fun extractGmailBody(payload: JSONObject?): String {
        if (payload == null) return ""
        val mime = payload.optString("mimeType")
        val direct = payload.optJSONObject("body")?.optString("data").orEmpty()
        if (direct.isNotBlank()) {
            val text = decodeBase64Url(direct)
            return if (mime == "text/html") htmlToText(text) else text
        }
        val parts = payload.optJSONArray("parts") ?: return ""
        var plain = ""
        var html = ""
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val value = extractGmailBody(part)
            if (part.optString("mimeType") == "text/plain" && value.isNotBlank()) plain = value
            if (part.optString("mimeType") == "text/html" && value.isNotBlank()) html = value
            if (plain.isBlank() && html.isBlank() && value.isNotBlank()) plain = value
        }
        return plain.ifBlank { html }
    }

    suspend fun gmailSend(
        ctx: ToolContext,
        to: String,
        subject: String,
        body: String,
        cc: String,
        bcc: String,
        html: Boolean
    ): String {
        val recipients = to.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }
        if (recipients.isEmpty() || recipients.any { !it.contains("@") }) {
            throw IllegalArgumentException("حدد بريداً صالحاً في to")
        }
        fun clean(value: String) = value.replace("\r", " ").replace("\n", " ").trim()
        val mime = buildString {
            append("To: ").append(recipients.joinToString(", ")).append("\r\n")
            if (cc.isNotBlank()) append("Cc: ").append(clean(cc)).append("\r\n")
            if (bcc.isNotBlank()) append("Bcc: ").append(clean(bcc)).append("\r\n")
            append("Subject: =?UTF-8?B?")
                .append(Base64.getEncoder().encodeToString(clean(subject).toByteArray(Charsets.UTF_8)))
                .append("?=\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: ").append(if (html) "text/html" else "text/plain")
                .append("; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray(Charsets.UTF_8)))
        }
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime.toByteArray(Charsets.UTF_8))
        val payload = JSONObject().put("raw", raw)
        val res = ensureOk(
            google(ctx, "gmail", "POST", "$GMAIL/messages/send", payload.toString()),
            "Gmail"
        )
        val json = JSONObject(res.body)
        return "✅ أُرسلت الرسالة إلى ${recipients.joinToString(", ")}\nid=${json.optString("id")}"
    }

    // ---------------- Google Calendar ----------------

    suspend fun calendarList(ctx: ToolContext, days: Int, max: Int): String {
        val now = Instant.now()
        val end = now.plusSeconds(days.coerceIn(1, 365) * 86400L)
        val url = "$CALENDAR/calendars/primary/events?timeMin=${enc(now.toString())}&timeMax=${enc(end.toString())}" +
            "&singleEvents=true&orderBy=startTime&maxResults=${max.coerceIn(1, 50)}"
        val res = ensureOk(google(ctx, "google_calendar", "GET", url), "Calendar")
        val events = JSONObject(res.body).optJSONArray("items") ?: JSONArray()
        if (events.length() == 0) return "لا توجد مواعيد قادمة."
        return (0 until events.length()).joinToString("\n") { index ->
            val e = events.optJSONObject(index) ?: JSONObject()
            val start = e.optJSONObject("start")?.let { it.optString("dateTime").ifBlank { it.optString("date") } }.orEmpty()
            val location = e.optString("location")
            buildString {
                append("• ").append(e.optString("summary").ifBlank { "(بدون عنوان)" }).append(" — ").append(start)
                if (location.isNotBlank()) append("\n  📍 ").append(location)
                append("\n  id=").append(e.optString("id"))
            }
        }
    }

    suspend fun calendarCreate(
        ctx: ToolContext,
        summary: String,
        start: String,
        end: String,
        description: String,
        location: String,
        timezone: String
    ): String {
        if (summary.isBlank()) throw IllegalArgumentException("العنوان مطلوب")
        val startObj = calendarTime(start)
        val endObj = calendarTime(end.ifBlank { defaultEnd(start) })
        if (timezone.isNotBlank()) {
            startObj.put("timeZone", timezone)
            endObj.put("timeZone", timezone)
        }
        val payload = JSONObject()
            .put("summary", summary)
            .put("start", startObj)
            .put("end", endObj)
        if (description.isNotBlank()) payload.put("description", description)
        if (location.isNotBlank()) payload.put("location", location)
        val res = ensureOk(
            google(ctx, "google_calendar", "POST", "$CALENDAR/calendars/primary/events", payload.toString()),
            "Calendar"
        )
        val json = JSONObject(res.body)
        return "✅ أُنشئ الموعد: ${json.optString("summary")}\n${json.optString("htmlLink")}"
    }

    private fun calendarTime(value: String): JSONObject {
        if (value.isBlank()) throw IllegalArgumentException("وقت البداية/النهاية مطلوب (ISO 8601)")
        return if (value.contains('T')) {
            JSONObject().put("dateTime", normalizeDateTime(value))
        } else {
            JSONObject().put("date", LocalDate.parse(value).toString())
        }
    }

    private fun defaultEnd(start: String): String {
        return if (start.contains('T')) {
            runCatching { OffsetDateTime.parse(normalizeDateTime(start)).plusHours(1).toString() }
                .getOrDefault(start)
        } else {
            runCatching { LocalDate.parse(start).plusDays(1).toString() }.getOrDefault(start)
        }
    }

    private fun normalizeDateTime(value: String): String {
        val trimmed = value.trim()
        if (trimmed.endsWith("Z") || trimmed.matches(Regex(".*[+-]\\d{2}:?\\d{2}$"))) return trimmed
        return runCatching {
            LocalDateTime.parse(trimmed).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
        }.getOrDefault(trimmed)
    }

    // ---------------- Notion ----------------

    private suspend fun notion(ctx: ToolContext, method: String, path: String, body: String = ""): Response {
        val token = ConnectorAuth.token(ctx.prefs, "notion")
        return ensureOk(
            request(
                method, "$NOTION$path", token, body,
                headers = mapOf("Notion-Version" to "2022-06-28")
            ),
            "Notion"
        )
    }

    suspend fun notionSearch(ctx: ToolContext, query: String, max: Int): String {
        val payload = JSONObject()
            .put("query", query)
            .put("page_size", max.coerceIn(1, 50))
        val res = notion(ctx, "POST", "/search", payload.toString())
        val results = JSONObject(res.body).optJSONArray("results") ?: JSONArray()
        if (results.length() == 0) return "لا توجد نتائج."
        return (0 until results.length()).joinToString("\n") { index ->
            val item = results.optJSONObject(index) ?: JSONObject()
            val type = item.optString("object")
            "• ${notionTitle(item).ifBlank { "(بدون عنوان)" }} [$type]\n  id=${item.optString("id")} ${item.optString("url")}"
        }
    }

    suspend fun notionRead(ctx: ToolContext, pageId: String, maxChars: Int): String {
        val max = maxChars.coerceIn(1000, 30_000)
        val builder = StringBuilder()
        var budget = 300
        suspend fun walk(blockId: String, depth: Int) {
            if (depth > 3 || budget <= 0) return
            val res = notion(ctx, "GET", "/blocks/$blockId/children?page_size=100")
            val results = JSONObject(res.body).optJSONArray("results") ?: return
            for (i in 0 until results.length()) {
                if (budget-- <= 0) break
                val block = results.optJSONObject(i) ?: continue
                val type = block.optString("type")
                val data = block.optJSONObject(type)
                val text = richText(data?.optJSONArray("rich_text") ?: data?.optJSONArray("title"))
                if (text.isNotBlank() || type == "divider") {
                    val prefix = when (type) {
                        "heading_1" -> "# "
                        "heading_2" -> "## "
                        "heading_3" -> "### "
                        "bulleted_list_item" -> "- "
                        "numbered_list_item" -> "1. "
                        "to_do" -> if (data?.optBoolean("checked") == true) "- [x] " else "- [ ] "
                        "quote" -> "> "
                        "code" -> "```${data?.optString("language").orEmpty()}\n"
                        "divider" -> "\n---\n"
                        else -> ""
                    }
                    append("  ".repeat(depth)).append(prefix).append(text)
                    if (type == "code") append("\n```")
                    append('\n')
                }
                if (block.optBoolean("has_children")) walk(block.optString("id"), depth + 1)
            }
        }
        walk(pageId, 0)
        val body = builder.toString().trim()
        return if (body.isBlank()) "الصفحة فارغة." else "📝 محتوى الصفحة:\n\n${body.take(max)}" +
            if (body.length > max) "\n… [اقتُطع]" else ""
    }

    suspend fun notionCreate(
        ctx: ToolContext,
        parentPage: String,
        title: String,
        content: String
    ): String {
        if (title.isBlank()) throw IllegalArgumentException("العنوان مطلوب")
        val children = JSONArray()
        content.lines().filter { it.isNotBlank() }.take(90).forEach { line ->
            children.put(
                JSONObject()
                    .put("object", "block")
                    .put("type", "paragraph")
                    .put("paragraph", JSONObject().put("rich_text", richTextArray(line)))
            )
        }
        val payload = JSONObject()
            .put("parent", JSONObject().put("page_id", parentPage))
            .put(
                "properties",
                JSONObject().put(
                    "title",
                    JSONObject().put("title", richTextArray(title))
                )
            )
            .put("children", children)
        val res = notion(ctx, "POST", "/pages", payload.toString())
        val json = JSONObject(res.body)
        return "✅ أُنشئت الصفحة: $title\n${json.optString("url")}"
    }

    suspend fun notionAppend(ctx: ToolContext, pageId: String, content: String): String {
        val children = JSONArray()
        content.lines().filter { it.isNotBlank() }.take(90).forEach { line ->
            children.put(
                JSONObject()
                    .put("object", "block")
                    .put("type", "paragraph")
                    .put("paragraph", JSONObject().put("rich_text", richTextArray(line)))
            )
        }
        if (children.length() == 0) throw IllegalArgumentException("المحتوى فارغ")
        notion(ctx, "PATCH", "/blocks/$pageId/children", JSONObject().put("children", children).toString())
        return "✅ أُضيف ${children.length()} سطراً إلى الصفحة"
    }

    private fun richTextArray(text: String): JSONArray = JSONArray().put(
        JSONObject().put("type", "text").put("text", JSONObject().put("content", text.take(1900)))
    )

    private fun richText(array: JSONArray?): String {
        if (array == null) return ""
        val builder = StringBuilder()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            builder.append(item.optString("plain_text").ifBlank {
                item.optJSONObject("text")?.optString("content").orEmpty()
            })
        }
        return builder.toString()
    }

    private fun notionTitle(item: JSONObject): String {
        val properties = item.optJSONObject("properties")
        if (properties != null) {
            properties.keys().forEach { key ->
                val prop = properties.optJSONObject(key) ?: return@forEach
                if (prop.optString("type") == "title") {
                    val value = richText(prop.optJSONArray("title"))
                    if (value.isNotBlank()) return value
                }
            }
        }
        val title = richText(item.optJSONArray("title"))
        return title
    }

    // ---------------- Slack ----------------

    private suspend fun slack(ctx: ToolContext, method: String, path: String, body: String = ""): JSONObject {
        val token = ConnectorAuth.token(ctx.prefs, "slack")
        val res = request(method, "$SLACK$path", token, body)
        ensureOk(res, "Slack")
        val json = JSONObject(res.body)
        if (!json.optBoolean("ok")) throw IllegalStateException("Slack: ${json.optString("error", "خطأ غير معروف")}")
        return json
    }

    suspend fun slackChannels(ctx: ToolContext, max: Int): String {
        val json = slack(
            ctx, "GET",
            "/conversations.list?types=public_channel,private_channel&exclude_archived=true&limit=${max.coerceIn(1, 100)}"
        )
        val channels = json.optJSONArray("channels") ?: JSONArray()
        if (channels.length() == 0) return "لا توجد قنوات."
        return (0 until channels.length()).joinToString("\n") { index ->
            val c = channels.optJSONObject(index) ?: JSONObject()
            "• #${c.optString("name")} — id=${c.optString("id")}${if (c.optBoolean("is_private")) " (خاصة)" else ""}"
        }
    }

    suspend fun slackPost(ctx: ToolContext, channel: String, text: String): String {
        if (text.isBlank()) throw IllegalArgumentException("نص الرسالة مطلوب")
        val id = resolveSlackChannel(ctx, channel)
        val payload = JSONObject().put("channel", id).put("text", text)
        val json = slack(ctx, "POST", "/chat.postMessage", payload.toString())
        return "✅ أُرسلت الرسالة إلى #${json.optString("channel")}\nts=${json.optString("ts")}"
    }

    suspend fun slackHistory(ctx: ToolContext, channel: String, max: Int): String {
        val id = resolveSlackChannel(ctx, channel)
        val json = slack(ctx, "GET", "/conversations.history?channel=$id&limit=${max.coerceIn(1, 50)}")
        val messages = json.optJSONArray("messages") ?: JSONArray()
        if (messages.length() == 0) return "لا توجد رسائل."
        return (0 until messages.length()).joinToString("\n") { index ->
            val m = messages.optJSONObject(index) ?: JSONObject()
            "• ${m.optString("user")}: ${m.optString("text").take(500)}"
        }
    }

    private suspend fun resolveSlackChannel(ctx: ToolContext, channel: String): String {
        val clean = channel.trim().removePrefix("#")
        if (clean.matches(Regex("[CDG][A-Z0-9]{6,}"))) return clean
        val json = slack(ctx, "GET", "/conversations.list?types=public_channel,private_channel&limit=200")
        val channels = json.optJSONArray("channels") ?: JSONArray()
        for (i in 0 until channels.length()) {
            val c = channels.optJSONObject(i) ?: continue
            if (c.optString("name").equals(clean, true) || c.optString("id") == clean) {
                return c.optString("id")
            }
        }
        throw IllegalArgumentException("لم أجد قناة Slack باسم $channel؛ استخدم id من slack_channels")
    }

    // ---------------- Generic REST ----------------

    suspend fun genericRequest(ctx: ToolContext, method: String, path: String, body: String): String {
        val base = ctx.prefs.getString(ConnectorAuth.baseKey("generic"), "").orEmpty().trim()
        if (base.isBlank()) throw IllegalStateException("أضف خدمة REST من شاشة الموصلات أولاً")
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            base.trimEnd('/') + "/" + path.trimStart('/')
        }
        val parsed = safeUrl(url)
        val baseUrl = safeUrl(base)
        if (!parsed.host.equals(baseUrl.host, true)) {
            throw IllegalArgumentException("لا يمكن الخروج عن نطاق الخدمة المحددة")
        }
        val header = ctx.prefs.getString(ConnectorAuth.headerKey("generic"), "Authorization")
            .orEmpty().ifBlank { "Authorization" }
        val value = ctx.prefs.getString(ConnectorAuth.valueKey("generic"), "").orEmpty()
        val headers = if (value.isBlank()) emptyMap() else mapOf(header to value)
        val res = request(method.uppercase(), url, "", body, headers = headers, maxBytes = 100_000)
        if (res.code !in 200..299) throw IllegalStateException("الخدمة ${res.code}: ${res.body.take(500)}")
        val pretty = runCatching {
            val trimmed = res.body.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed).toString(2) else JSONObject(trimmed).toString(2)
        }.getOrDefault(res.body)
        return "✅ ${res.code} ${parsed.host}\n\n${pretty.take(20_000)}"
    }

    // ---------------- Helpers ----------------

    private fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun decodeBase64Url(data: String): String = runCatching {
        String(Base64.getUrlDecoder().decode(data), Charsets.UTF_8)
    }.getOrDefault("")
}
