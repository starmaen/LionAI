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
    private const val ALL_DRIVES = "supportsAllDrives=true&includeItemsFromAllDrives=true"

    data class Response(val code: Int, val body: String, val contentType: String)

    fun ready(prefs: SharedPreferences, id: String): Boolean = ConnectorAuth.isConnected(prefs, id)

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun url(base: String, vararg params: Pair<String, String>): String {
        val query = params
            .filter { it.second.isNotBlank() }
            .joinToString("&") { enc(it.first) + "=" + enc(it.second) }
        if (query.isBlank()) return base
        return base + (if (base.contains('?')) "&" else "?") + query
    }

    private fun quote(value: String): String = value
        .replace("\\", "\\\\")
        .replace("'", "\\'")

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

    fun extractDriveId(raw: String): String? {
        val text = raw.trim()
        val path = Regex(
            """/(?:folders|file/d|document/d|spreadsheets/d|presentation/d|forms/d|drawings/d|d)/([A-Za-z0-9_-]{10,})"""
        ).find(text)?.groupValues?.get(1)
        if (!path.isNullOrBlank()) return path
        val query = Regex("""[?&]id=([A-Za-z0-9_-]{10,})""").find(text)?.groupValues?.get(1)
        if (!query.isNullOrBlank()) return query
        return text.takeIf { it.matches(Regex("[A-Za-z0-9_-]{20,}")) }
    }

    suspend fun driveOpen(ctx: ToolContext, raw: String, maxChars: Int): String {
        val id = extractDriveId(raw) ?: return driveSearch(ctx, raw, 10)
        val metaRes = ensureOk(
            google(
                ctx, "google_drive", "GET",
                url(
                    "$DRIVE/files/$id",
                    "fields" to "id,name,mimeType,size,parents,webViewLink,shortcutDetails",
                    "supportsAllDrives" to "true"
                )
            ),
            "Drive"
        )
        val meta = JSONObject(metaRes.body)
        val mime = meta.optString("mimeType")
        val name = meta.optString("name")
        val link = meta.optString("webViewLink")
        if (mime == "application/vnd.google-apps.shortcut") {
            val target = meta.optJSONObject("shortcutDetails")?.optString("targetId").orEmpty()
            if (target.isNotBlank()) return "🔗 اختصار إلى ملف آخر:\n\n" + driveOpen(ctx, target, maxChars)
        }
        if (mime.startsWith("application/vnd.google-apps.folder")) {
            val listing = driveList(ctx, 100, id)
            return "📁 $name\n$link\n\n$listing"
        }
        return driveRead(ctx, id, maxChars)
    }

    suspend fun driveMove(ctx: ToolContext, fileId: String, folderId: String, name: String): String {
        val id = extractDriveId(fileId) ?: throw IllegalArgumentException("معرّف الملف غير صالح")
        val dest = extractDriveId(folderId) ?: throw IllegalArgumentException("معرّف المجلد غير صالح")
        val meta = ensureOk(
            google(ctx, "google_drive", "GET", url("$DRIVE/files/$id", "fields" to "id,name,parents", "supportsAllDrives" to "true")),
            "Drive"
        )
        val parents = JSONObject(meta.body).optJSONArray("parents") ?: JSONArray()
        val remove = (0 until parents.length()).mapNotNull { parents.optString(it).takeIf { p -> p.isNotBlank() } }
            .joinToString(",")
        val target = url(
            "$DRIVE/files/$id",
            "addParents" to dest,
            "removeParents" to remove,
            "fields" to "id,name,parents,webViewLink",
            "supportsAllDrives" to "true"
        )
        val payload = JSONObject()
        if (name.isNotBlank()) payload.put("name", name)
        ensureOk(google(ctx, "google_drive", "PATCH", target, payload.toString()), "Drive")
        return "✅ نُقل الملف إلى المجلد المحدد${if (name.isNotBlank()) " باسم $name" else ""}"
    }

    suspend fun driveRename(ctx: ToolContext, fileId: String, name: String): String {
        val id = extractDriveId(fileId) ?: throw IllegalArgumentException("معرّف الملف غير صالح")
        if (name.isBlank()) throw IllegalArgumentException("الاسم الجديد مطلوب")
        val payload = JSONObject().put("name", name)
        ensureOk(
            google(
                ctx, "google_drive", "PATCH",
                url("$DRIVE/files/$id", "fields" to "id,name", "supportsAllDrives" to "true"),
                payload.toString()
            ),
            "Drive"
        )
        return "✅ أُعيدت تسمية الملف إلى $name"
    }

    suspend fun driveCopy(ctx: ToolContext, fileId: String, name: String, folderId: String): String {
        val id = extractDriveId(fileId) ?: throw IllegalArgumentException("معرّف الملف غير صالح")
        val payload = JSONObject()
        if (name.isNotBlank()) payload.put("name", name)
        val dest = extractDriveId(folderId)
        if (dest != null) payload.put("parents", JSONArray().put(dest))
        val res = ensureOk(
            google(
                ctx, "google_drive", "POST",
                url(
                    "$DRIVE/files/$id/copy",
                    "fields" to "id,name,webViewLink",
                    "supportsAllDrives" to "true"
                ),
                payload.toString()
            ),
            "Drive"
        )
        val json = JSONObject(res.body)
        return "✅ نُسخ الملف: ${json.optString("name")}\n${json.optString("webViewLink")}"
    }

    suspend fun driveTrash(ctx: ToolContext, fileId: String, permanent: Boolean): String {
        val id = extractDriveId(fileId) ?: throw IllegalArgumentException("معرّف الملف غير صالح")
        if (permanent) {
            ensureOk(
                google(ctx, "google_drive", "DELETE", url("$DRIVE/files/$id", "supportsAllDrives" to "true")),
                "Drive"
            )
            return "✅ حُذف الملف نهائياً"
        }
        val payload = JSONObject().put("trashed", true)
        ensureOk(
            google(
                ctx, "google_drive", "PATCH",
                url("$DRIVE/files/$id", "fields" to "id", "supportsAllDrives" to "true"),
                payload.toString()
            ),
            "Drive"
        )
        return "✅ نُقل الملف إلى سلة المهملات"
    }

    suspend fun driveUpdate(ctx: ToolContext, fileId: String, content: String, path: String, mime: String): String {
        val id = extractDriveId(fileId) ?: throw IllegalArgumentException("معرّف الملف غير صالح")
        val meta = ensureOk(
            google(ctx, "google_drive", "GET", url("$DRIVE/files/$id", "fields" to "id,name,mimeType", "supportsAllDrives" to "true")),
            "Drive"
        )
        val info = JSONObject(meta.body)
        val type = info.optString("mimeType")
        if (type.startsWith("application/vnd.google-apps")) {
            throw IllegalStateException("ملفات Google Docs لا تُعدّل عبر هذه الأداة؛ استخدم drive_upload لملف نصي عادي")
        }
        val bytes = if (path.isNotBlank()) bytesOf(ctx, path) else content.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) throw IllegalArgumentException("المحتوى فارغ")
        val contentType = mime.ifBlank { type.ifBlank { "text/plain" } }
        val token = ConnectorAuth.token(ctx.prefs, "google_drive")
        val res = uploadBytes(
            url("$DRIVE_UPLOAD/files/$id", "uploadType" to "media", "supportsAllDrives" to "true"),
            token, bytes, contentType
        )
        ensureOk(res, "Drive")
        return "✅ حُدّث محتوى ${info.optString("name")} (${FileTools.formatSize(bytes.size.toLong())})"
    }

    suspend fun driveSearch(ctx: ToolContext, query: String, max: Int): String {
        val limit = max.coerceIn(1, 50)
        val escaped = quote(query.trim())
        val q = "(name contains '$escaped' or fullText contains '$escaped') and trashed = false"
        val target = url(
            "$DRIVE/files",
            "q" to q,
            "pageSize" to limit.toString(),
            "fields" to "files(id,name,mimeType,size,modifiedTime,webViewLink),nextPageToken",
            "orderBy" to "modifiedTime desc"
        ) + "&" + ALL_DRIVES
        val res = ensureOk(google(ctx, "google_drive", "GET", target), "Drive")
        val files = JSONObject(res.body).optJSONArray("files") ?: JSONArray()
        if (files.length() == 0) return "لا توجد ملفات مطابقة."
        return (0 until files.length()).joinToString("\n") { index ->
            val f = files.optJSONObject(index) ?: JSONObject()
            val icon = if (f.optString("mimeType").startsWith("application/vnd.google-apps.folder")) "📁" else "📄"
            "$icon ${f.optString("name")} [${shortMime(f.optString("mimeType"))}] " +
                "id=${f.optString("id")} — ${f.optString("modifiedTime")}\n  ${f.optString("webViewLink")}"
        }
    }

    suspend fun driveList(ctx: ToolContext, max: Int, folderId: String = ""): String {
        val limit = max.coerceIn(1, 200)
        val params = mutableListOf(
            "pageSize" to limit.toString(),
            "fields" to "files(id,name,mimeType,size,modifiedTime,webViewLink),nextPageToken"
        )
        if (folderId.isBlank()) {
            params.add("orderBy" to "modifiedTime desc")
        } else {
            val fid = extractDriveId(folderId) ?: throw IllegalArgumentException("معرّف المجلد غير صالح")
            params.add("q" to "'$fid' in parents and trashed = false")
            params.add("orderBy" to "folder,name")
        }
        val target = url("$DRIVE/files", *params.toTypedArray()) + "&" + ALL_DRIVES
        val res = ensureOk(google(ctx, "google_drive", "GET", target), "Drive")
        val files = JSONObject(res.body).optJSONArray("files") ?: JSONArray()
        if (files.length() == 0) return if (folderId.isBlank()) "Drive فارغ." else "المجلد فارغ."
        return (0 until files.length()).joinToString("\n") { index ->
            val f = files.optJSONObject(index) ?: JSONObject()
            val folder = f.optString("mimeType").startsWith("application/vnd.google-apps.folder")
            (if (folder) "📁 " else "📄 ") + f.optString("name") +
                (if (folder) "" else " (${FileTools.formatSize(f.optLong("size"))})") +
                " id=${f.optString("id")} — ${f.optString("modifiedTime")}"
        }
    }

    suspend fun driveDownload(ctx: ToolContext, fileId: String, name: String, exportMime: String = ""): String {
        val id = extractDriveId(fileId) ?: throw IllegalArgumentException("معرّف الملف غير صالح")
        val metaRes = ensureOk(
            google(
                ctx, "google_drive", "GET",
                url("$DRIVE/files/$id", "fields" to "id,name,mimeType,size", "supportsAllDrives" to "true")
            ),
            "Drive"
        )
        val meta = JSONObject(metaRes.body)
        val mime = meta.optString("mimeType")
        val displayName = meta.optString("name").ifBlank { "drive-file" }
        if (mime.startsWith("application/vnd.google-apps.folder")) {
            throw IllegalArgumentException("هذا مجلد وليس ملفاً؛ استخدم drive_list لعرض محتوياته")
        }
        if (mime.startsWith("application/vnd.google-apps")) {
            val chosen = exportMime.ifBlank { defaultExportMime(mime) }
            val extension = extensionFor(chosen, displayName)
            val rawName = name.ifBlank { displayName + extension }
            val target = uniqueFile(FileTools.file(ctx.context, "downloads/" + safeName(rawName)))
            target.parentFile?.mkdirs()
            val isText = chosen.startsWith("text/") || chosen.contains("json") ||
                chosen.contains("xml") || chosen == "application/vnd.google-apps.script+json"
            if (isText) {
                val text = ensureOk(
                    google(ctx, "google_drive", "GET", url("$DRIVE/files/$id/export", "mimeType" to chosen)),
                    "Drive"
                ).body
                target.writeText(text)
            } else {
                val token = ConnectorAuth.token(ctx.prefs, "google_drive")
                downloadBytes(
                    url("$DRIVE/files/$id/export", "mimeType" to chosen),
                    token, target, 50_000_000L
                )
            }
            return "✅ نُزّل ${FileTools.relative(ctx.context, target)} (${FileTools.formatSize(target.length())}) بصيغة $chosen"
        }
        val rawName = name.ifBlank { displayName }
        val target = uniqueFile(FileTools.file(ctx.context, "downloads/" + safeName(rawName)))
        target.parentFile?.mkdirs()
        val token = ConnectorAuth.token(ctx.prefs, "google_drive")
        val bytes = downloadBytes(
            url("$DRIVE/files/$id", "alt" to "media", "supportsAllDrives" to "true"),
            token, target, 50_000_000L
        )
        return "✅ نُزّل ${FileTools.relative(ctx.context, target)} (${FileTools.formatSize(bytes)})"
    }

    private fun defaultExportMime(googleMime: String): String = when (googleMime) {
        "application/vnd.google-apps.spreadsheet" -> "text/csv"
        "application/vnd.google-apps.presentation" -> "text/plain"
        "application/vnd.google-apps.drawing" -> "image/png"
        "application/vnd.google-apps.script" -> "application/vnd.google-apps.script+json"
        else -> "text/plain"
    }

    private fun extensionFor(mime: String, name: String): String {
        val fromName = name.substringAfterLast('.', "")
        if (fromName.isNotBlank() && fromName.length <= 5) return ""
        return when (mime) {
            "text/plain" -> ".txt"
            "text/csv" -> ".csv"
            "text/html" -> ".html"
            "application/pdf" -> ".pdf"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
            "image/png" -> ".png"
            "application/zip" -> ".zip"
            else -> ".bin"
        }
    }

    private fun uniqueFile(file: java.io.File): java.io.File {
        if (!file.exists()) return file
        val base = file.nameWithoutExtension
        val ext = file.extension
        var index = 2
        while (true) {
            val candidate = java.io.File(file.parentFile, "$base-$index${if (ext.isBlank()) "" else ".$ext"}")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun shortMime(mime: String): String = when {
        mime.startsWith("application/vnd.google-apps.folder") -> "مجلد"
        mime.startsWith("application/vnd.google-apps.document") -> "Google Doc"
        mime.startsWith("application/vnd.google-apps.spreadsheet") -> "Google Sheet"
        mime.startsWith("application/vnd.google-apps.presentation") -> "Google Slides"
        mime.startsWith("application/vnd.google-apps") -> "Google"
        else -> mime.substringAfterLast('.', "ملف")
    }

    private fun safeName(raw: String): String {
        val cleaned = raw.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_', '.')
        return cleaned.ifBlank { "drive-file" }.take(120)
    }

    private suspend fun downloadBytes(url: String, token: String, target: java.io.File, maxBytes: Long): Long {
        var current = url
        var bearer = token
        var lastError = ""
        repeat(5) {
            val conn = safeUrl(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            if (bearer.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $bearer")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                if (location.isNullOrBlank()) throw IllegalStateException("إعادة توجيه بلا عنوان")
                current = location
                bearer = ""
                return@repeat
            }
            if (code !in 200..299) {
                lastError = "Drive ${code}: " + conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(300)
                throw IllegalStateException(lastError)
            }
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > maxBytes) throw IllegalArgumentException("الملف أكبر من 50MB")
                        out.write(buffer, 0, read)
                    }
                    return total
                }
            }
        }
        throw IllegalStateException(lastError.ifBlank { "تعذر تنزيل الملف" })
    }

    suspend fun driveRead(ctx: ToolContext, fileId: String, maxChars: Int): String {
        val id = extractDriveId(fileId) ?: throw IllegalArgumentException("معرّف الملف غير صالح")
        val metaRes = ensureOk(
            google(
                ctx, "google_drive", "GET",
                url(
                    "$DRIVE/files/$id",
                    "fields" to "id,name,mimeType,size,webViewLink",
                    "supportsAllDrives" to "true"
                )
            ),
            "Drive"
        )
        val meta = JSONObject(metaRes.body)
        val mime = meta.optString("mimeType")
        val name = meta.optString("name")
        val link = meta.optString("webViewLink")
        val max = maxChars.coerceIn(1000, 30_000)
        if (mime.startsWith("application/vnd.google-apps.folder")) {
            return "📁 $name\n$link\n\n" + driveList(ctx, 100, id)
        }
        val googleDoc = mime.startsWith("application/vnd.google-apps")
        val looksText = googleDoc || mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") ||
            mime.contains("csv") || mime.contains("javascript") || mime.contains("x-yaml")
        if (!looksText) {
            return "📄 $name ($mime, ${FileTools.formatSize(meta.optLong("size"))})\n$link\n" +
                "ملف ثنائي؛ استخدم drive_download لنزيله إلى مساحة العمل."
        }
        val text = if (googleDoc) {
            ensureOk(
                google(
                    ctx, "google_drive", "GET",
                    url("$DRIVE/files/$id/export", "mimeType" to defaultExportMime(mime))
                ),
                "Drive"
            ).body
        } else {
            ensureOk(
                google(ctx, "google_drive", "GET", url("$DRIVE/files/$id", "alt" to "media")),
                "Drive"
            ).body
        }
        return "📄 $name ($mime)\n$link\n\n${text.take(max)}" +
            if (text.length > max) "\n… [اقتُطع]" else ""
    }

    suspend fun driveMeta(ctx: ToolContext, fileId: String): String {
        val id = extractDriveId(fileId) ?: throw IllegalArgumentException("معرّف الملف غير صالح")
        val res = ensureOk(
            google(
                ctx, "google_drive", "GET",
                url(
                    "$DRIVE/files/$id",
                    "fields" to "id,name,mimeType,size,createdTime,modifiedTime,webViewLink,parents,owners(displayName,emailAddress),shared,capabilities(canEdit,canShare),shortcutDetails",
                    "supportsAllDrives" to "true"
                )
            ),
            "Drive"
        )
        val f = JSONObject(res.body)
        val owner = f.optJSONArray("owners")?.optJSONObject(0)
        return buildString {
            append("📄 ").append(f.optString("name")).append('\n')
            append("id=").append(f.optString("id")).append('\n')
            append("النوع: ").append(shortMime(f.optString("mimeType"))).append('\n')
            append("الحجم: ").append(FileTools.formatSize(f.optLong("size"))).append('\n')
            append("أُنشئ: ").append(f.optString("createdTime")).append('\n')
            append("عُدّل: ").append(f.optString("modifiedTime")).append('\n')
            if (owner != null) append("المالك: ").append(owner.optString("displayName"))
                .append(" <").append(owner.optString("emailAddress")).append(">\n")
            append("مشترك: ").append(if (f.optBoolean("shared")) "نعم" else "لا").append('\n')
            append("رابط: ").append(f.optString("webViewLink"))
        }
    }

    suspend fun driveMkdir(ctx: ToolContext, name: String, folderId: String): String {
        val metadata = JSONObject().put("name", name).put("mimeType", "application/vnd.google-apps.folder")
        val parent = extractDriveId(folderId)
        if (parent != null) metadata.put("parents", JSONArray().put(parent))
        val res = ensureOk(
            google(
                ctx, "google_drive", "POST",
                url("$DRIVE/files", "fields" to "id,name,webViewLink") + "&" + ALL_DRIVES,
                metadata.toString()
            ),
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
        if (name.isBlank()) throw IllegalArgumentException("اسم الملف مطلوب")
        val bytes = if (path.isNotBlank()) bytesOf(ctx, path) else content.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) throw IllegalArgumentException("الملف فارغ أو لم تحدد content/path")
        val contentType = mime.ifBlank { mimeOf(name) }
        val metadata = JSONObject().put("name", name).put("mimeType", contentType)
        val parent = extractDriveId(folderId)
        if (parent != null) metadata.put("parents", JSONArray().put(parent))
        val token = ConnectorAuth.token(ctx.prefs, "google_drive")
        val res = ensureOk(
            uploadMultipart(
                url(
                    "$DRIVE_UPLOAD/files",
                    "uploadType" to "multipart",
                    "fields" to "id,name,webViewLink",
                    "supportsAllDrives" to "true"
                ),
                token, metadata, bytes, contentType
            ),
            "Drive"
        )
        val json = JSONObject(res.body)
        return "✅ رُفع $name (${FileTools.formatSize(bytes.size.toLong())}) إلى Drive\n${json.optString("webViewLink")}"
    }

    private fun uploadMultipart(
        target: String,
        token: String,
        metadata: JSONObject,
        bytes: ByteArray,
        contentType: String
    ): Response {
        val boundary = "lion_" + java.util.UUID.randomUUID().toString().replace("-", "")
        val head = "--$boundary\r\n" +
            "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
            metadata.toString() + "\r\n" +
            "--$boundary\r\n" +
            "Content-Type: $contentType\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val headBytes = head.toByteArray(Charsets.UTF_8)
        val tailBytes = tail.toByteArray(Charsets.UTF_8)
        val conn = safeUrl(target).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        conn.setFixedLengthStreamingMode(headBytes.size + bytes.size + tailBytes.size)
        conn.outputStream.use { out ->
            out.write(headBytes)
            out.write(bytes)
            out.write(tailBytes)
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return Response(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty(), conn.contentType.orEmpty())
    }

    private fun uploadBytes(
        target: String,
        token: String,
        bytes: ByteArray,
        contentType: String,
        method: String = "PATCH"
    ): Response {
        val conn = safeUrl(target).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.doOutput = true
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", contentType)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return Response(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty(), conn.contentType.orEmpty())
    }

    // ---------------- Gmail ----------------

    private fun gmailHeaders(json: JSONObject): Map<String, String> {
        val headers = json.optJSONObject("payload")?.optJSONArray("headers") ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        for (i in 0 until headers.length()) {
            val h = headers.optJSONObject(i) ?: continue
            out[h.optString("name").lowercase()] = h.optString("value")
        }
        return out
    }

    private data class GmailAttachment(val name: String, val mime: String, val id: String, val size: Long)

    private fun gmailAttachments(payload: JSONObject?): List<GmailAttachment> {
        if (payload == null) return emptyList()
        val out = mutableListOf<GmailAttachment>()
        fun walk(part: JSONObject) {
            val filename = part.optString("filename")
            val attachmentId = part.optJSONObject("body")?.optString("attachmentId").orEmpty()
            if (filename.isNotBlank() && attachmentId.isNotBlank()) {
                out.add(
                    GmailAttachment(
                        filename,
                        part.optString("mimeType", "application/octet-stream"),
                        attachmentId,
                        part.optJSONObject("body")?.optLong("size", 0L) ?: 0L
                    )
                )
            }
            val children = part.optJSONArray("parts") ?: return
            for (i in 0 until children.length()) {
                children.optJSONObject(i)?.let { walk(it) }
            }
        }
        walk(payload)
        return out
    }

    suspend fun gmailSearch(ctx: ToolContext, query: String, max: Int): String {
        val limit = max.coerceIn(1, 20)
        val target = url("$GMAIL/messages", "q" to query, "maxResults" to limit.toString())
        val res = ensureOk(google(ctx, "gmail", "GET", target), "Gmail")
        val messages = JSONObject(res.body).optJSONArray("messages") ?: JSONArray()
        if (messages.length() == 0) return "لا توجد رسائل مطابقة."
        val lines = mutableListOf<String>()
        for (index in 0 until messages.length()) {
            val id = messages.optJSONObject(index)?.optString("id").orEmpty()
            val detail = try {
                val d = ensureOk(
                    google(
                        ctx, "gmail", "GET",
                        url("$GMAIL/messages/$id", "format" to "metadata") +
                            "&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date"
                    ),
                    "Gmail"
                )
                JSONObject(d.body)
            } catch (e: Exception) {
                null
            }
            val headers = detail?.let { gmailHeaders(it) }.orEmpty()
            val snippet = detail?.optString("snippet").orEmpty()
            lines.add(
                "• ${headers["subject"].orEmpty().ifBlank { "(بدون موضوع)" }}\n" +
                    "  من: ${headers["from"].orEmpty()} — ${headers["date"].orEmpty()}\n" +
                    "  id=$id\n  ${snippet.take(160)}"
            )
        }
        return lines.joinToString("\n")
    }

    suspend fun gmailRead(ctx: ToolContext, messageId: String, maxChars: Int): String {
        val res = ensureOk(
            google(ctx, "gmail", "GET", url("$GMAIL/messages/$messageId", "format" to "full")),
            "Gmail"
        )
        val json = JSONObject(res.body)
        val headers = gmailHeaders(json)
        val payload = json.optJSONObject("payload")
        val body = extractGmailBody(payload)
        val attachments = gmailAttachments(payload)
        val max = maxChars.coerceIn(1000, 30_000)
        return buildString {
            append("✉️ ").append(headers["subject"].orEmpty().ifBlank { "(بدون موضوع)" }).append('\n')
            append("من: ").append(headers["from"].orEmpty()).append('\n')
            append("إلى: ").append(headers["to"].orEmpty()).append('\n')
            append("التاريخ: ").append(headers["date"].orEmpty()).append('\n')
            append("threadId: ").append(json.optString("threadId")).append("\n\n")
            append(if (body.isBlank()) json.optString("snippet") else body.take(max))
            if (body.length > max) append("\n… [اقتُطع]")
            if (attachments.isNotEmpty()) {
                append("\n\n📎 المرفقات (${attachments.size}):\n")
                attachments.forEach { a ->
                    append("- ").append(a.name).append(" (").append(a.mime).append(", ")
                        .append(FileTools.formatSize(a.size)).append(") attachmentId=")
                        .append(a.id).append('\n')
                }
            }
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
            val partMime = part.optString("mimeType")
            val value = extractGmailBody(part)
            if (partMime == "text/plain" && value.isNotBlank()) plain = value
            if (partMime == "text/html" && value.isNotBlank()) html = value
            if (plain.isBlank() && html.isBlank() && value.isNotBlank()) plain = value
        }
        return plain.ifBlank { html }
    }

    private fun gmailEncodeHeader(value: String): String {
        val clean = value.replace("\r", " ").replace("\n", " ").trim()
        if (clean.all { it.code in 32..126 }) return clean
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(clean.toByteArray(Charsets.UTF_8)) + "?="
    }

    private fun gmailFilename(name: String): String {
        val clean = name.replace("\r", " ").replace("\n", " ").replace("\"", "").trim()
        if (clean.all { it.code in 32..126 }) return "\"$clean\""
        val encoded = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
        return "\"$clean\"; filename*=UTF-8''$encoded"
    }

    private fun buildGmailMime(
        ctx: ToolContext,
        to: String,
        subject: String,
        body: String,
        cc: String,
        bcc: String,
        html: Boolean,
        from: String,
        attachments: List<String>,
        inReplyTo: String
    ): String {
        val recipients = to.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }
        if (recipients.isEmpty() || recipients.any { !it.contains("@") }) {
            throw IllegalArgumentException("حدد بريداً صالحاً في to")
        }
        val boundary = "lion_" + java.util.UUID.randomUUID().toString().replace("-", "")
        val builder = StringBuilder()
        if (from.isNotBlank()) builder.append("From: ").append(gmailEncodeHeader(from)).append("\r\n")
        builder.append("To: ").append(recipients.joinToString(", ")).append("\r\n")
        if (cc.isNotBlank()) builder.append("Cc: ").append(cc.replace("\r", " ").replace("\n", " ")).append("\r\n")
        if (bcc.isNotBlank()) builder.append("Bcc: ").append(bcc.replace("\r", " ").replace("\n", " ")).append("\r\n")
        builder.append("Subject: ").append(gmailEncodeHeader(subject)).append("\r\n")
        if (inReplyTo.isNotBlank()) {
            val clean = inReplyTo.replace("\r", " ").replace("\n", " ").trim()
            builder.append("In-Reply-To: ").append(clean).append("\r\n")
            builder.append("References: ").append(clean).append("\r\n")
        }
        builder.append("MIME-Version: 1.0\r\n")
        val content = if (html) "text/html" else "text/plain"
        if (attachments.isEmpty()) {
            builder.append("Content-Type: ").append(content).append("; charset=UTF-8\r\n")
            builder.append("Content-Transfer-Encoding: base64\r\n\r\n")
            builder.append(Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray(Charsets.UTF_8)))
        } else {
            builder.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n")
            builder.append("--").append(boundary).append("\r\n")
            builder.append("Content-Type: ").append(content).append("; charset=UTF-8\r\n")
            builder.append("Content-Transfer-Encoding: base64\r\n\r\n")
            builder.append(Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray(Charsets.UTF_8)))
            builder.append("\r\n")
            attachments.forEach { rawPath ->
                val file = FileTools.file(ctx.context, rawPath)
                if (!file.isFile) throw IllegalArgumentException("المرفق غير موجود: $rawPath")
                if (file.length() > 20_000_000L) throw IllegalArgumentException("المرفق أكبر من 20MB: ${file.name}")
                val mime = mimeOf(file.name)
                builder.append("--").append(boundary).append("\r\n")
                builder.append("Content-Type: ").append(mime).append("; name=")
                    .append(gmailFilename(file.name)).append("\r\n")
                builder.append("Content-Disposition: attachment; filename=")
                    .append(gmailFilename(file.name)).append("\r\n")
                builder.append("Content-Transfer-Encoding: base64\r\n\r\n")
                builder.append(Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(file.readBytes()))
                builder.append("\r\n")
            }
            builder.append("--").append(boundary).append("--\r\n")
        }
        return builder.toString()
    }

    suspend fun gmailSend(
        ctx: ToolContext,
        to: String,
        subject: String,
        body: String,
        cc: String,
        bcc: String,
        html: Boolean,
        from: String = "",
        attachments: List<String> = emptyList(),
        threadId: String = "",
        inReplyTo: String = ""
    ): String {
        val mime = buildGmailMime(ctx, to, subject, body, cc, bcc, html, from, attachments, inReplyTo)
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime.toByteArray(Charsets.UTF_8))
        val payload = JSONObject().put("raw", raw)
        if (threadId.isNotBlank()) payload.put("threadId", threadId)
        val res = ensureOk(
            google(ctx, "gmail", "POST", "$GMAIL/messages/send", payload.toString()),
            "Gmail"
        )
        val json = JSONObject(res.body)
        val recipients = to.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }
        return "✅ أُرسلت الرسالة إلى ${recipients.joinToString(", ")}\nid=${json.optString("id")} threadId=${json.optString("threadId")}"
    }

    suspend fun gmailDraft(
        ctx: ToolContext,
        to: String,
        subject: String,
        body: String,
        cc: String,
        bcc: String,
        html: Boolean,
        from: String = "",
        attachments: List<String> = emptyList()
    ): String {
        val mime = buildGmailMime(ctx, to, subject, body, cc, bcc, html, from, attachments, "")
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime.toByteArray(Charsets.UTF_8))
        val res = ensureOk(
            google(ctx, "gmail", "POST", "$GMAIL/drafts", JSONObject().put("message", JSONObject().put("raw", raw)).toString()),
            "Gmail"
        )
        val json = JSONObject(res.body)
        return "✅ حُفظت مسودة\ndraftId=${json.optString("id")} messageId=${json.optJSONObject("message")?.optString("id").orEmpty()}"
    }

    suspend fun gmailLabels(ctx: ToolContext): String {
        val res = ensureOk(google(ctx, "gmail", "GET", "$GMAIL/labels"), "Gmail")
        val labels = JSONObject(res.body).optJSONArray("labels") ?: JSONArray()
        if (labels.length() == 0) return "لا توجد تسميات."
        return (0 until labels.length()).joinToString("\n") { index ->
            val l = labels.optJSONObject(index) ?: JSONObject()
            "• ${l.optString("name")} (${l.optString("type")}) id=${l.optString("id")}"
        }
    }

    suspend fun gmailModify(ctx: ToolContext, messageId: String, add: List<String>, remove: List<String>): String {
        val payload = JSONObject()
        if (add.isNotEmpty()) payload.put("addLabelIds", JSONArray(add))
        if (remove.isNotEmpty()) payload.put("removeLabelIds", JSONArray(remove))
        if (add.isEmpty() && remove.isEmpty()) throw IllegalArgumentException("حدد تسميات للإضافة أو الإزالة")
        ensureOk(google(ctx, "gmail", "POST", "$GMAIL/messages/$messageId/modify", payload.toString()), "Gmail")
        return "✅ حُدّثت تسميات الرسالة $messageId"
    }

    suspend fun gmailTrash(ctx: ToolContext, messageId: String, permanent: Boolean): String {
        if (permanent) {
            ensureOk(google(ctx, "gmail", "DELETE", "$GMAIL/messages/$messageId"), "Gmail")
            return "✅ حُذفت الرسالة نهائياً"
        }
        ensureOk(google(ctx, "gmail", "POST", "$GMAIL/messages/$messageId/trash", "{}"), "Gmail")
        return "✅ نُقلت الرسالة إلى المهملات"
    }

    suspend fun gmailAttachment(ctx: ToolContext, messageId: String, attachmentId: String, name: String): String {
        val res = ensureOk(
            google(ctx, "gmail", "GET", "$GMAIL/messages/$messageId/attachments/$attachmentId"),
            "Gmail"
        )
        val data = JSONObject(res.body).optString("data")
        if (data.isBlank()) throw IllegalStateException("لم تُرجع Gmail بيانات المرفق")
        val bytes = Base64.getUrlDecoder().decode(data)
        val target = uniqueFile(FileTools.file(ctx.context, "downloads/" + safeName(name.ifBlank { "gmail-attachment" })))
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return "✅ نُزّل ${FileTools.relative(ctx.context, target)} (${FileTools.formatSize(bytes.size.toLong())})"
    }

    suspend fun gmailProfile(ctx: ToolContext): String {
        val res = ensureOk(google(ctx, "gmail", "GET", "$GMAIL/profile"), "Gmail")
        val json = JSONObject(res.body)
        return "📧 ${json.optString("emailAddress")}\nالرسائل: ${json.optInt("messagesTotal")} — " +
            "المحادثات: ${json.optInt("threadsTotal")}"
    }

    // ---------------- Google Calendar ----------------

    suspend fun calendarList(ctx: ToolContext, days: Int, max: Int, calendarId: String = "primary"): String {
        val now = Instant.now()
        val end = now.plusSeconds(days.coerceIn(1, 365) * 86400L)
        val target = url(
            "$CALENDAR/calendars/${enc(calendarId.ifBlank { "primary" })}/events",
            "timeMin" to now.toString(),
            "timeMax" to end.toString(),
            "singleEvents" to "true",
            "orderBy" to "startTime",
            "maxResults" to max.coerceIn(1, 50).toString()
        )
        val res = ensureOk(google(ctx, "google_calendar", "GET", target), "Calendar")
        val events = JSONObject(res.body).optJSONArray("items") ?: JSONArray()
        if (events.length() == 0) return "لا توجد مواعيد قادمة."
        return (0 until events.length()).joinToString("\n") { index ->
            val e = events.optJSONObject(index) ?: JSONObject()
            val start = e.optJSONObject("start")?.let { it.optString("dateTime").ifBlank { it.optString("date") } }.orEmpty()
            val location = e.optString("location")
            val meet = e.optString("hangoutLink")
            buildString {
                append("• ").append(e.optString("summary").ifBlank { "(بدون عنوان)" }).append(" — ").append(start)
                if (location.isNotBlank()) append("\n  📍 ").append(location)
                if (meet.isNotBlank()) append("\n  🔗 ").append(meet)
                append("\n  id=").append(e.optString("id"))
            }
        }
    }

    suspend fun calendarCalendars(ctx: ToolContext): String {
        val res = ensureOk(google(ctx, "google_calendar", "GET", "$CALENDAR/users/me/calendarList"), "Calendar")
        val items = JSONObject(res.body).optJSONArray("items") ?: JSONArray()
        if (items.length() == 0) return "لا توجد تقاويم."
        return (0 until items.length()).joinToString("\n") { index ->
            val c = items.optJSONObject(index) ?: JSONObject()
            val primary = if (c.optBoolean("primary")) " ⭐" else ""
            "• ${c.optString("summary")}$primary — id=${c.optString("id")} (${c.optString("timeZone")})"
        }
    }

    suspend fun calendarCreate(
        ctx: ToolContext,
        summary: String,
        start: String,
        end: String,
        description: String,
        location: String,
        timezone: String,
        attendees: List<String> = emptyList(),
        calendarId: String = "primary"
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
        if (attendees.isNotEmpty()) {
            payload.put("attendees", JSONArray().also { array ->
                attendees.filter { it.contains("@") }.forEach { array.put(JSONObject().put("email", it)) }
            })
        }
        val res = ensureOk(
            google(
                ctx, "google_calendar", "POST",
                "$CALENDAR/calendars/${enc(calendarId.ifBlank { "primary" })}/events", payload.toString()
            ),
            "Calendar"
        )
        val json = JSONObject(res.body)
        return "✅ أُنشئ الموعد: ${json.optString("summary")}\n${json.optString("htmlLink")}"
    }

    suspend fun calendarUpdate(
        ctx: ToolContext,
        eventId: String,
        summary: String,
        start: String,
        end: String,
        description: String,
        location: String,
        timezone: String,
        calendarId: String = "primary"
    ): String {
        if (eventId.isBlank()) throw IllegalArgumentException("event_id مطلوب")
        val payload = JSONObject()
        if (summary.isNotBlank()) payload.put("summary", summary)
        if (start.isNotBlank()) {
            val value = calendarTime(start)
            if (timezone.isNotBlank()) value.put("timeZone", timezone)
            payload.put("start", value)
        }
        if (end.isNotBlank()) {
            val value = calendarTime(end)
            if (timezone.isNotBlank()) value.put("timeZone", timezone)
            payload.put("end", value)
        }
        if (description.isNotBlank()) payload.put("description", description)
        if (location.isNotBlank()) payload.put("location", location)
        if (payload.length() == 0) throw IllegalArgumentException("لا يوجد تغيير")
        val res = ensureOk(
            google(
                ctx, "google_calendar", "PATCH",
                "$CALENDAR/calendars/${enc(calendarId.ifBlank { "primary" })}/events/${enc(eventId)}",
                payload.toString()
            ),
            "Calendar"
        )
        val json = JSONObject(res.body)
        return "✅ حُدّث الموعد: ${json.optString("summary")}\n${json.optString("htmlLink")}"
    }

    suspend fun calendarDelete(ctx: ToolContext, eventId: String, calendarId: String = "primary"): String {
        if (eventId.isBlank()) throw IllegalArgumentException("event_id مطلوب")
        ensureOk(
            google(
                ctx, "google_calendar", "DELETE",
                "$CALENDAR/calendars/${enc(calendarId.ifBlank { "primary" })}/events/${enc(eventId)}"
            ),
            "Calendar"
        )
        return "✅ حُذف الموعد $eventId"
    }

    suspend fun calendarQuickAdd(ctx: ToolContext, text: String): String {
        if (text.isBlank()) throw IllegalArgumentException("نص الموعد مطلوب")
        val res = ensureOk(
            google(
                ctx, "google_calendar", "POST",
                url("$CALENDAR/calendars/primary/events/quickAdd", "text" to text)
            ),
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
                    builder.append("  ".repeat(depth)).append(prefix).append(text)
                    if (type == "code") builder.append("\n```")
                    builder.append('\n')
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
        val basePath = baseUrl.path.trimEnd('/')
        if (basePath.isNotEmpty() && !parsed.path.startsWith(basePath)) {
            throw IllegalArgumentException("لا يمكن الخروج عن مسار الخدمة المحدد")
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
