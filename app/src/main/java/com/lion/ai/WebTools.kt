package com.lion.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

/** أدوات الويب: جلب صفحة أو API، استخراج النص والروابط والصور، وتنزيل ملف. */
object WebTools {
    data class CodeBlock(val language: String, val code: String)

    data class Page(
        val finalUrl: String,
        val title: String,
        val text: String,
        val links: List<Pair<String, String>>,
        val images: List<String>,
        val contentType: String,
        val bytes: Int,
        val codeBlocks: List<CodeBlock> = emptyList(),
        val metadata: Map<String, String> = emptyMap()
    )

    data class Download(val file: File, val bytes: Int, val contentType: String)

    private val SCRIPT = Regex("(?is)<script[^>]*>.*?</script>")
    private val STYLE = Regex("(?is)<style[^>]*>.*?</style>")
    private val NOSCRIPT = Regex("(?is)<noscript[^>]*>.*?</noscript>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val BLOCK = Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article|/header|/footer|/table|/ul|/ol)[^>]*>")
    private val LIST_ITEM = Regex("(?i)<li[^>]*>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val LINK = Regex("(?is)<a\\s[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>")
    private val IMG = Regex("(?is)<img\\s[^>]*(?:src|data-src)=[\"']([^\"']+)[\"']")
    private val META_CHARSET = Regex("(?i)<meta[^>]+charset=[\"']?([a-zA-Z0-9_\\-]+)")
    private val NOISE = listOf(
        Regex("(?is)<nav[^>]*>.*?</nav>"),
        Regex("(?is)<footer[^>]*>.*?</footer>"),
        Regex("(?is)<header[^>]*>.*?</header>"),
        Regex("(?is)<aside[^>]*>.*?</aside>"),
        Regex("(?is)<form[^>]*>.*?</form>")
    )
    private val MAIN = Regex("(?is)<main[^>]*>(.*?)</main>")
    private val ARTICLE = Regex("(?is)<article[^>]*>(.*?)</article>")
    private val PRE = Regex("(?is)<pre[^>]*>(.*?)</pre>")
    private val CODE = Regex("(?is)<code[^>]*>(.*?)</code>")
    private val META = Regex("(?is)<meta\\s+[^>]*>")
    private val ATTR = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)=[\"']([^\"']*)[\"']")
    private val LANGUAGE_CLASS = Regex("(?i)(?:language|lang)-([a-zA-Z0-9_+#.-]+)")

    private fun decodeEntities(value: String): String {
        var text = value
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&copy;", "©")
            .replace("&reg;", "®")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
        text = Regex("&#x([0-9a-fA-F]+);").replace(text) {
            runCatching { String(Character.toChars(it.groupValues[1].toInt(16))) }.getOrDefault("")
        }
        text = Regex("&#([0-9]+);").replace(text) {
            runCatching { String(Character.toChars(it.groupValues[1].toInt())) }.getOrDefault("")
        }
        return text
    }

    private fun plain(html: String): String {
        var text = html
            .replace(SCRIPT, " ")
            .replace(STYLE, " ")
            .replace(NOSCRIPT, " ")
            .replace(COMMENT, " ")
        NOISE.forEach { text = text.replace(it, " ") }
        text = text
            .replace(BLOCK, "\n")
            .replace(LIST_ITEM, "\n• ")
        text = TAG.replace(text, " ")
        text = decodeEntities(text)
        return text.lineSequence()
            .map { it.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun extractCodeBlocks(html: String): List<CodeBlock> {
        val blocks = mutableListOf<CodeBlock>()
        PRE.findAll(html).take(30).forEach { match ->
            val opening = match.value.substringBefore('>')
            val language = LANGUAGE_CLASS.find(opening)?.groupValues?.get(1).orEmpty()
            val code = decodeEntities(TAG.replace(match.groupValues[1], "")).trim()
            if (code.length >= 10) blocks.add(CodeBlock(language, code))
        }
        val seen = blocks.map { it.code }.toMutableSet()
        CODE.findAll(html).take(50).forEach { match ->
            val opening = match.value.substringBefore('>')
            val language = LANGUAGE_CLASS.find(opening)?.groupValues?.get(1).orEmpty()
            val code = decodeEntities(TAG.replace(match.groupValues[1], "")).trim()
            if (code.length >= 10 && seen.add(code)) blocks.add(CodeBlock(language, code))
        }
        return blocks
    }

    private fun extractMetadata(html: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        META.findAll(html).take(100).forEach { match ->
            val attrs = ATTR.findAll(match.value).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val key = attrs["name"] ?: attrs["property"] ?: return@forEach
            val content = attrs["content"] ?: return@forEach
            result[key] = content
        }
        return result
    }

    private fun mainContent(html: String): String =
        MAIN.find(html)?.groupValues?.get(1)
            ?: ARTICLE.find(html)?.groupValues?.get(1)
            ?: html

    private fun absolute(base: String, href: String): String? {
        val cleaned = href.trim()
        if (cleaned.isBlank() || cleaned.startsWith("javascript:") || cleaned.startsWith("mailto:") ||
            cleaned.startsWith("data:")
        ) return null
        return runCatching { URI(base).resolve(cleaned).toString() }.getOrNull()
    }

    private fun charsetOf(contentType: String, body: ByteArray): String {
        val fromHeader = Regex("(?i)charset=([a-zA-Z0-9_\\-]+)").find(contentType)?.groupValues?.get(1)
        if (!fromHeader.isNullOrBlank()) return fromHeader
        val head = runCatching { String(body.take(4096).toByteArray(), Charsets.ISO_8859_1) }.getOrDefault("")
        return META_CHARSET.find(head)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: "UTF-8"
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 65536))
        val buffer = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    private fun validate(url: String) {        val parsed = runCatching { URL(url) }.getOrNull()
            ?: throw IllegalArgumentException("رابط غير صالح")
        if (parsed.protocol !in setOf("http", "https")) {
            throw IllegalArgumentException("السماح لروابط http/https فقط")
        }
        val host = parsed.host.lowercase()
        if (host == "169.254.169.254" || host == "metadata.google.internal") {
            throw IllegalArgumentException("هذا العنوان محجوب لأسباب أمنية")
        }
    }

    fun fetch(url: String, maxBytes: Int = 2_000_000, withLinks: Boolean = true): Page {
        validate(url)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) LionAI/1.7")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "ar,en;q=0.8")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status ${connection.responseMessage.orEmpty()}".trim())
            }
            val contentType = connection.contentType.orEmpty()
            val bytes = connection.inputStream.use { readLimited(it, maxBytes.coerceIn(1024, 5_000_000)) }
            val charset = runCatching { charsetOf(contentType, bytes) }.getOrDefault("UTF-8")
            var body = runCatching { String(bytes, charset) }.getOrElse { String(bytes, Charsets.UTF_8) }
            if (body.count { it == '\uFFFD' } > 20 && charset.uppercase() != "WINDOWS-1256") {
                body = runCatching { String(bytes, charset("windows-1256")) }.getOrDefault(body)
            }
            val finalUrl = connection.url.toString()
            val isJson = contentType.contains("json", ignoreCase = true) ||
                body.trimStart().startsWith("{") || body.trimStart().startsWith("[")
            if (isJson) {
                val pretty = runCatching {
                    val trimmed = body.trim()
                    if (trimmed.startsWith("[")) JSONArray(trimmed).toString(2) else JSONObject(trimmed).toString(2)
                }.getOrDefault(body)
                return Page(finalUrl, "", pretty, emptyList(), emptyList(), contentType, bytes.size)
            }
            val isHtml = contentType.contains("html", ignoreCase = true) || body.contains("<html", ignoreCase = true)
            if (!isHtml) {
                return Page(finalUrl, "", body.trim(), emptyList(), emptyList(), contentType, bytes.size)
            }
            val title = TITLE.find(body)?.groupValues?.get(1)?.let { plain(it) }.orEmpty()
            val links = if (!withLinks) emptyList() else LINK.findAll(body).take(200).mapNotNull { match ->
                val href = absolute(finalUrl, match.groupValues[1]) ?: return@mapNotNull null
                val label = plain(match.groupValues[2]).take(80).ifBlank { href }
                label to href
            }.distinctBy { it.second }.toList()
            val images = if (!withLinks) emptyList() else IMG.findAll(body).take(100).mapNotNull { match ->
                absolute(finalUrl, match.groupValues[1])
            }.distinct().toList()
            val contentHtml = mainContent(body)
            return Page(
                finalUrl = finalUrl,
                title = title,
                text = plain(contentHtml),
                links = links,
                images = images,
                contentType = contentType,
                bytes = bytes.size,
                codeBlocks = extractCodeBlocks(body),
                metadata = extractMetadata(body)
            )
        } finally {
            connection.disconnect()
        }
    }

    fun httpRequest(url: String, method: String = "GET", body: String = "", contentType: String = "application/json"): String {
        validate(url)
        val verb = method.uppercase().let { if (it in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) it else "GET" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = verb
            connectTimeout = 15_000
            readTimeout = 45_000
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LionAI/1.7 (Android)")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            if (body.isNotBlank()) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }
        }
        try {
            if (body.isNotBlank()) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("HTTP $status: ${raw.take(500)}")
            return raw
        } finally {
            connection.disconnect()
        }
    }

    fun download(context: Context, url: String, fileName: String = ""): Download {
        validate(url)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LionAI/1.7 (Android)")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            val contentType = connection.contentType.orEmpty()
            val directory = File(File(context.filesDir, "workspace"), "downloads").apply { mkdirs() }
            val fromUrl = URL(url).path.substringAfterLast('/')
            val suggested = fileName.ifBlank { fromUrl.ifBlank { "download.bin" } }
            val safe = suggested.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").take(80).ifBlank { "download.bin" }
            val target = uniqueFile(directory, safe)
            var total = 0
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > 20_000_000) throw IllegalStateException("الملف أكبر من 20MB")
                        output.write(buffer, 0, read)
                    }
                }
            }
            return Download(target, total, contentType)
        } finally {
            connection.disconnect()
        }
    }

    private fun uniqueFile(directory: File, name: String): File {
        var target = File(directory, name)
        if (!target.exists()) return target
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var index = 1
        while (target.exists()) {
            val candidate = if (ext.isBlank()) "${base}_$index" else "${base}_$index.$ext"
            target = File(directory, candidate)
            index++
        }
        return target
    }

    fun summary(page: Page, maxChars: Int = 12_000): String = buildString {
        if (page.title.isNotBlank()) append("📄 ").append(page.title).append('\n')
        append("🔗 ").append(page.finalUrl).append('\n')
        append("الحجم: ").append(page.bytes).append(" بايت | ").append(page.contentType).append('\n')
        if (page.metadata.isNotEmpty()) {
            append("بيانات وصفية: ")
            append(page.metadata.entries.take(6).joinToString("، ") { "${it.key}=${it.value.take(60)}" })
            append('\n')
        }
        append('\n').append(page.text.take(maxChars))
        if (page.text.length > maxChars) append("\n… [اقتُطع النص]")
        if (page.codeBlocks.isNotEmpty()) {
            append("\n\n🧩 أكواد مستخرجة (${page.codeBlocks.size}):\n")
            page.codeBlocks.take(5).forEach { block ->
                append("```").append(block.language).append('\n')
                append(block.code.take(3000)).append("\n```\n")
            }
        }
        if (page.links.isNotEmpty()) {
            append("\n🔗 الروابط (${page.links.size}):\n")
            page.links.take(40).forEach { (label, url) -> append("- ").append(label).append(" — ").append(url).append('\n') }
        }
        if (page.images.isNotEmpty()) {
            append("\n🖼️ الصور (${page.images.size}):\n")
            page.images.take(20).forEach { append("- ").append(it).append('\n') }
        }
    }.trim()

    fun decodeUrl(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
