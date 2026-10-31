package com.lion.ai

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/**
 * تسليم الملفات للمستخدم: بطاقة تنزيل لكل ملف/مجلد (يُضغط ZIP) ينشئه المزود بأدوات الملفات.
 * الرد يحمل علامة [[FILE:المسار]] تُرسم في الشات بطاقةً قابلة للتنزيل والمشاركة.
 */
object FileDelivery {
    private val MARK = Regex("\\[\\[FILE:([^\\]|]+)(?:\\|[^\\]]*)?\\]\\]")

    fun marker(path: String): String = "[[FILE:${path.trim()}]]"

    fun paths(text: String): List<String> =
        MARK.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.distinct().toList()

    fun strip(text: String): String =
        MARK.replace(text, "").replace(Regex("\n{3,}"), "\n\n").trim()

    /** الملف داخل مساحة العمل إن وُجد (آمن ضد الخروج من المساحة). */
    fun file(context: Context, path: String): File? =
        runCatching { FileTools.file(context, path) }.getOrNull()?.takeIf { it.isFile }

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "xml" -> "text/xml"
        "csv" -> "text/csv"
        "md", "txt", "kt", "java", "py", "js", "ts", "css", "sh", "gradle", "yml", "yaml", "properties" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }

    /** يحفظ في Download/LionAI. يعيد المسار الظاهر للمستخدم أو null عند الفشل. */
    fun saveBytes(context: Context, name: String, mime: String, writer: (OutputStream) -> Unit): String? {
        return try {
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "lion-file" }
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/LionAI")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    null
                } else {
                    resolver.openOutputStream(uri)?.use { writer(it) }
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                    "Download/LionAI/$safeName"
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LionAI")
                dir.mkdirs()
                File(dir, safeName).outputStream().use { writer(it) }
                "Download/LionAI/$safeName"
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveFile(context: Context, file: File): String? =
        saveBytes(context, file.name, mime(file.name)) { out -> file.inputStream().use { it.copyTo(out) } }

    fun saveText(context: Context, name: String, text: String): String? =
        saveBytes(context, name, mime(name)) { out -> out.write(text.toByteArray(Charsets.UTF_8)) }

    fun share(context: Context, file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime(file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun extensionFor(lang: String): String = when (lang.lowercase()) {
        "kotlin", "kt" -> "kt"
        "java" -> "java"
        "python", "py" -> "py"
        "javascript", "js" -> "js"
        "typescript", "ts" -> "ts"
        "html" -> "html"
        "css" -> "css"
        "json" -> "json"
        "xml" -> "xml"
        "bash", "sh", "shell" -> "sh"
        "yaml", "yml" -> "yml"
        "markdown", "md" -> "md"
        "csv" -> "csv"
        "gradle" -> "gradle"
        else -> "txt"
    }
}
