package com.lion.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import android.util.Base64
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

data class Attachment(val name: String, val text: String)
data class ImageAttachment(val name: String, val mime: String, val base64: String)

object Attachments {
    private const val MAX_CHARS = 24000
    private const val MAX_TEXT_BYTES = 1_500_000
    private const val MAX_FILE_BYTES = 40L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val TRUNCATED_NOTE = "\n…[تم اقتطاع باقي المحتوى لكبر حجمه]"

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    private val OOXML_EXTENSIONS = setOf("docx", "pptx", "xlsx")
    private val ODF_EXTENSIONS = setOf("odt", "odp", "ods")
    private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")
    private val ARCHIVE_UNSUPPORTED = setOf("rar", "7z", "tar", "gz", "bz2", "xz", "apk", "exe", "iso")

    /** result.image يُملأ عند اختيار صورة أو عند تحويل PDF ممسوح ضوئياً إلى صورة. */
    data class Result(val attachment: Attachment?, val error: String?, val image: ImageAttachment? = null)

    data class ImageResult(val image: ImageAttachment?, val error: String?)

    // ---------------------------------------------------------------- صور

    fun readImage(context: Context, uri: Uri): ImageResult {
        return try {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (!mime.startsWith("image/")) {
                return ImageResult(null, "⚠️ الملف المختار ليس صورة ($mime).")
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimitedBytes(MAX_IMAGE_BYTES) }
            if (bytes == null || bytes.isEmpty()) {
                return ImageResult(null, "⚠️ تعذر قراءة الصورة.")
            }
            if (bytes.size >= MAX_IMAGE_BYTES) {
                // نصغّر الصور الكبيرة تلقائياً بدل رفضها
                val shrunk = shrinkImage(context, uri, displayName(context, uri))
                return if (shrunk != null) ImageResult(shrunk, null) else ImageResult(null, "⚠️ تعذر تصغير الصورة الكبيرة.")
            }
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            ImageResult(ImageAttachment(displayName(context, uri), mime, base64), null)
        } catch (e: Exception) {
            ImageResult(null, "⚠️ تعذر قراءة الصورة: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun shrinkImage(context: Context, uri: Uri, name: String): ImageAttachment? {
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it)
        } ?: return null
        return ImageCodec.toAttachment(bitmap, name)
    }

    // ---------------------------------------------------------------- الدخول الرئيسي

    fun read(context: Context, uri: Uri): Result {
        val name = displayName(context, uri)
        val mime = context.contentResolver.getType(uri) ?: ""
        val extension = name.substringAfterLast('.', "").lowercase()

        return try {
            when {
                mime.startsWith("image/") || extension in IMAGE_EXTENSIONS -> {
                    val r = readImage(context, uri)
                    Result(null, r.error, r.image)
                }
                mime.startsWith("audio/") || mime.startsWith("video/") -> Result(
                    null, "⚠️ ملفات الصوت والفيديو غير مدعومة للقراءة."
                )
                extension in ARCHIVE_UNSUPPORTED -> Result(
                    null, "⚠️ صيغة .$extension غير مدعومة. المدعوم: ZIP وPDF وWord وExcel وPowerPoint والنصوص والكود."
                )
                extension == "doc" || extension == "xls" || extension == "ppt" -> Result(
                    null, "⚠️ صيغ Office القديمة (.$extension) غير مدعومة. احفظ الملف بصيغة .${extension}x ثم أعد المحاولة."
                )
                mime == "application/pdf" || extension == "pdf" -> readPdf(context, uri, name)
                extension in OOXML_EXTENSIONS -> readOoxml(context, uri, name, extension)
                extension in ODF_EXTENSIONS -> readOdf(context, uri, name)
                mime == "application/zip" || extension == "zip" || extension == "epub" || extension == "jar" ->
                    readZip(context, uri, name)
                extension in HTML_EXTENSIONS || mime == "text/html" -> readHtml(context, uri, name)
                extension == "rtf" -> readRtf(context, uri, name)
                else -> readTextSniff(context, uri, name)
            }
        } catch (e: OutOfMemoryError) {
            Result(null, "⚠️ الملف كبير جداً على ذاكرة الجهاز.")
        } catch (e: Exception) {
            Result(null, "⚠️ تعذر قراءة الملف: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------- نصوص

    private fun readTextSniff(context: Context, uri: Uri, name: String): Result {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimitedBytes(MAX_TEXT_BYTES) }
        if (bytes == null || bytes.isEmpty()) return Result(null, "⚠️ الملف فارغ أو غير قابل للقراءة.")
        val text = decodeText(bytes, bytes.size >= MAX_TEXT_BYTES)
            ?: return Result(null, "⚠️ هذا ملف ثنائي وليس نصاً، ولا يمكن قراءته. المدعوم: PDF وWord وExcel وPowerPoint وZIP والنصوص والكود.")
        return finish(name, text)
    }

    private fun readHtml(context: Context, uri: Uri, name: String): Result {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimitedBytes(MAX_TEXT_BYTES) }
        val html = bytes?.let { decodeText(it, it.size >= MAX_TEXT_BYTES) }
            ?: return Result(null, "⚠️ تعذر قراءة ملف HTML.")
        return finish(name, htmlToText(html))
    }

    private fun readRtf(context: Context, uri: Uri, name: String): Result {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimitedBytes(MAX_TEXT_BYTES) }
        val raw = bytes?.let { String(it, Charsets.ISO_8859_1) } ?: return Result(null, "⚠️ تعذر قراءة RTF.")
        val text = raw
            .replace(Regex("\\\\'([0-9a-fA-F]{2})")) { m ->
                String(byteArrayOf(m.groupValues[1].toInt(16).toByte()), charset("windows-1256"))
            }
            .replace(Regex("\\\\u(-?\\d+)\\??")) { m ->
                var code = m.groupValues[1].toInt()
                if (code < 0) code += 65536
                code.toChar().toString()
            }
            .replace(Regex("\\\\par[d]?"), "\n")
            .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
            .replace(Regex("[{}]"), "")
        return finish(name, text)
    }

    private fun htmlToText(html: String): String {
        val cleaned = html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        return Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun finish(name: String, text: String): Result {
        val trimmed = text.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (trimmed.isBlank()) return Result(null, "⚠️ لم أجد نصاً داخل الملف.")
        val body = if (trimmed.length > MAX_CHARS) trimmed.take(MAX_CHARS) + TRUNCATED_NOTE else trimmed
        return Result(Attachment(name, body), null)
    }

    /** UTF-8 أولاً ثم Windows-1256 (العربية) كاحتياط، ويرفض الملفات الثنائية. */
    private fun decodeText(bytes: ByteArray, truncated: Boolean): String? {
        if (bytes.isEmpty()) return null
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val sampleSize = minOf(bytes.size, 4096)
        for (i in 0 until sampleSize) {
            if (bytes[i] == 0.toByte()) return null
        }
        val drops = if (truncated) 4 else 1
        for (drop in 0 until drops) {
            try {
                val slice = ByteBuffer.wrap(bytes, 0, bytes.size - drop)
                return Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(slice)
                    .toString()
            } catch (e: CharacterCodingException) {
            }
        }
        return try {
            String(bytes, charset("windows-1256"))
        } catch (e: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    // ---------------------------------------------------------------- PDF

    private fun readPdf(context: Context, uri: Uri, name: String): Result {
        val size = fileSize(context, uri)
        if (size > MAX_FILE_BYTES) return Result(null, "⚠️ ملف PDF أكبر من 40 ميجابايت.")
        var text = ""
        var failure: String? = null
        try {
            PDFBoxResourceLoader.init(context.applicationContext)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val document = PDDocument.load(stream)
                try {
                    val stripper = PDFTextStripper()
                    stripper.sortByPosition = true
                    stripper.startPage = 1
                    stripper.endPage = minOf(document.numberOfPages, 80)
                    text = stripper.getText(document)
                } finally {
                    document.close()
                }
            }
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
        }
        if (text.isNotBlank()) {
            return finish(name, "نص ملف PDF:\n$text")
        }
        // ملف ممسوح ضوئياً أو محمي: نحوّل أول صفحات إلى صورة لتقرأها نماذج الرؤية
        val image = renderPdfPages(context, uri, name)
        return if (image != null) {
            Result(null, null, image)
        } else {
            Result(null, "⚠️ تعذر استخراج نص من PDF" + (failure?.let { " ($it)" } ?: "") + ". قد يكون محمياً بكلمة مرور.")
        }
    }

    private fun renderPdfPages(context: Context, uri: Uri, name: String): ImageAttachment? {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: return null
        var renderer: PdfRenderer? = null
        try {
            renderer = PdfRenderer(pfd)
            val pages = minOf(renderer.pageCount, 3)
            if (pages == 0) return null
            val width = 1000
            val bitmaps = mutableListOf<Bitmap>()
            for (i in 0 until pages) {
                val page = renderer.openPage(i)
                try {
                    val height = (page.height.toFloat() / page.width * width).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmaps.add(bitmap)
                } finally {
                    page.close()
                }
            }
            val totalHeight = bitmaps.sumOf { it.height }
            val combined = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(combined)
            var top = 0f
            bitmaps.forEach {
                canvas.drawBitmap(it, 0f, top, null)
                top += it.height
            }
            return ImageCodec.toAttachment(combined, name.substringBeforeLast('.') + ".jpg")
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd.close() }
        }
    }

    // ---------------------------------------------------------------- Office

    private fun readOoxml(context: Context, uri: Uri, name: String, ext: String): Result {
        if (fileSize(context, uri) > MAX_FILE_BYTES) return Result(null, "⚠️ الملف أكبر من 40 ميجابايت.")
        val parts = mutableListOf<Pair<String, ByteArray>>()
        val sharedStrings = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val n = entry.name
                    val wanted = when (ext) {
                        "docx" -> n == "word/document.xml"
                        "pptx" -> n.startsWith("ppt/slides/slide") && n.endsWith(".xml")
                        else -> n == "xl/sharedStrings.xml" || (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml"))
                    }
                    if (wanted && parts.size < 80) {
                        parts.add(n to zip.readLimitedBytes(6_000_000))
                    }
                    entry = zip.nextEntry
                }
            }
        }
        if (parts.isEmpty()) return Result(null, "⚠️ الملف تالف أو ليس بصيغة .$ext صحيحة.")

        val builder = StringBuilder()
        when (ext) {
            "docx" -> builder.append(ooxmlText(parts.first().second))
            "pptx" -> {
                parts.sortedBy { slideNumber(it.first) }.forEachIndexed { i, part ->
                    builder.append("— شريحة ${i + 1} —\n").append(ooxmlText(part.second)).append('\n')
                }
            }
            else -> {
                parts.firstOrNull { it.first.endsWith("sharedStrings.xml") }?.let {
                    sharedStrings.addAll(xlsxSharedStrings(it.second))
                }
                parts.filter { it.first.contains("worksheets/") }
                    .sortedBy { slideNumber(it.first) }
                    .forEachIndexed { i, part ->
                        builder.append("— ورقة ${i + 1} —\n")
                            .append(xlsxSheet(part.second, sharedStrings)).append('\n')
                    }
            }
        }
        return finish(name, builder.toString())
    }

    private fun slideNumber(path: String): Int =
        Regex("(\\d+)\\.xml$").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun local(tag: String): String = tag.substringAfter(':')

    private fun newParser(bytes: ByteArray): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(bytes), null)
        return parser
    }

    /** docx وpptx: النص داخل <w:t> أو <a:t> والفقرات <p>. */
    private fun ooxmlText(bytes: ByteArray): String {
        val parser = newParser(bytes)
        val sb = StringBuilder()
        var inText = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && sb.length < MAX_CHARS * 2) {
            when (event) {
                XmlPullParser.START_TAG -> when (local(parser.name)) {
                    "t" -> inText = true
                    "tab" -> sb.append('\t')
                    "br" -> sb.append('\n')
                }
                XmlPullParser.TEXT -> if (inText) sb.append(parser.text)
                XmlPullParser.END_TAG -> when (local(parser.name)) {
                    "t" -> inText = false
                    "p" -> sb.append('\n')
                }
            }
            event = parser.next()
        }
        return sb.toString()
    }

    private fun xlsxSharedStrings(bytes: ByteArray): List<String> {
        val parser = newParser(bytes)
        val list = mutableListOf<String>()
        var current = StringBuilder()
        var inT = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (local(parser.name)) {
                    "si" -> current = StringBuilder()
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inT) current.append(parser.text)
                XmlPullParser.END_TAG -> when (local(parser.name)) {
                    "t" -> inT = false
                    "si" -> list.add(current.toString())
                }
            }
            event = parser.next()
        }
        return list
    }

    private fun xlsxSheet(bytes: ByteArray, shared: List<String>): String {
        val parser = newParser(bytes)
        val sb = StringBuilder()
        val row = mutableListOf<String>()
        var cellType = ""
        var inV = false
        var inInline = false
        var value = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && sb.length < MAX_CHARS * 2) {
            when (event) {
                XmlPullParser.START_TAG -> when (local(parser.name)) {
                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t") ?: ""
                        value = StringBuilder()
                    }
                    "v" -> inV = true
                    "is" -> inInline = true
                    "t" -> if (inInline) inV = true
                }
                XmlPullParser.TEXT -> if (inV) value.append(parser.text)
                XmlPullParser.END_TAG -> when (local(parser.name)) {
                    "v" -> inV = false
                    "t" -> if (inInline) inV = false
                    "is" -> inInline = false
                    "c" -> {
                        val raw = value.toString()
                        row.add(if (cellType == "s") shared.getOrNull(raw.trim().toIntOrNull() ?: -1).orEmpty() else raw)
                    }
                    "row" -> {
                        if (row.any { it.isNotBlank() }) sb.append(row.joinToString(" | ")).append('\n')
                        row.clear()
                    }
                }
            }
            event = parser.next()
        }
        return sb.toString()
    }

    /** odt وods وodp: النص داخل text:p وtext:h. */
    private fun readOdf(context: Context, uri: Uri, name: String): Result {
        var content: ByteArray? = null
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "content.xml") {
                        content = zip.readLimitedBytes(8_000_000)
                        break
                    }
                    entry = zip.nextEntry
                }
            }
        }
        val bytes = content ?: return Result(null, "⚠️ تعذر قراءة محتوى الملف.")
        val parser = newParser(bytes)
        val sb = StringBuilder()
        var depth = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && sb.length < MAX_CHARS * 2) {
            when (event) {
                XmlPullParser.START_TAG -> when (local(parser.name)) {
                    "p", "h" -> depth++
                    "tab" -> sb.append('\t')
                    "s" -> sb.append(' ')
                    "line-break" -> sb.append('\n')
                    "table-cell" -> sb.append(" | ")
                }
                XmlPullParser.TEXT -> if (depth > 0) sb.append(parser.text)
                XmlPullParser.END_TAG -> when (local(parser.name)) {
                    "p", "h" -> {
                        depth = maxOf(0, depth - 1)
                        sb.append('\n')
                    }
                }
            }
            event = parser.next()
        }
        return finish(name, sb.toString())
    }

    // ---------------------------------------------------------------- ZIP

    private fun readZip(context: Context, uri: Uri, name: String): Result {
        val names = mutableListOf<String>()
        val contents = StringBuilder()
        var budget = MAX_CHARS - 2000
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && names.size < 300) {
                    names.add(entry.name)
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    val readable = !entry.isDirectory && budget > 300 &&
                        !entry.name.startsWith("__MACOSX") &&
                        (ext in TEXT_LIKE || ext in HTML_EXTENSIONS)
                    if (readable) {
                        val bytes = zip.readLimitedBytes(200_000)
                        var text = decodeText(bytes, bytes.size >= 200_000)
                        if (text != null) {
                            if (ext in HTML_EXTENSIONS) text = htmlToText(text)
                            val piece = text.trim().take(minOf(budget, 6000))
                            if (piece.isNotBlank()) {
                                contents.append("\n===== ").append(entry.name).append(" =====\n").append(piece).append('\n')
                                budget -= piece.length + entry.name.length + 16
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        if (names.isEmpty()) return Result(null, "⚠️ الأرشيف فارغ أو غير صالح.")
        val listing = buildString {
            append("محتويات الأرشيف (").append(names.size).append(" عنصر):\n")
            names.take(150).forEach { append("- ").append(it).append('\n') }
            if (names.size > 150) append("… و ").append(names.size - 150).append(" عنصر آخر\n")
        }
        return Result(Attachment(name, listing + contents.toString()), null)
    }

    private val TEXT_LIKE = setOf(
        "txt", "md", "csv", "tsv", "json", "xml", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx",
        "c", "cpp", "h", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bat", "yml", "yaml", "gradle",
        "properties", "toml", "ini", "cfg", "conf", "log", "sql", "css", "scss", "vue", "swift", "dart",
        "lua", "r", "pl", "srt", "tex", "env", "gitignore", "pro"
    )

    // ---------------------------------------------------------------- أدوات

    private fun fileSize(context: Context, uri: Uri): Long {
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) size = cursor.getLong(index)
            }
        }
        return size
    }

    private fun displayName(context: Context, uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "ملف"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index) ?: name
            }
        }
        return name
    }

    private fun InputStream.readLimitedBytes(max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < max) {
            val count = read(buffer, 0, minOf(buffer.size, max - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

object ImageCodec {
    /** يحوّل Bitmap إلى JPEG بحجم مناسب للإرسال للنماذج (أقل من 4 ميجابايت). */
    fun toAttachment(source: Bitmap, name: String, maxSide: Int = 2048): ImageAttachment? {
        var bitmap = source
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > maxSide) {
            val scale = maxSide.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }
        var quality = 88
        var bytes: ByteArray
        do {
            val out = ByteArrayOutputStream()
            // JPEG لا يدعم الشفافية: نرسم على خلفية بيضاء
            val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flat)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            flat.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 12
        } while (bytes.size > 3_800_000 && quality > 30)
        if (bytes.size > 3_800_000) return null
        val safeName = if (name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }) name
        else name.substringBeforeLast('.') + ".jpg"
        return ImageAttachment(safeName, "image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }
}
