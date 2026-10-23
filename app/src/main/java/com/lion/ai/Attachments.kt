package com.lion.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.util.zip.ZipInputStream

data class Attachment(val name: String, val text: String)

object Attachments {
    private const val MAX_CHARS = 12000
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "json", "xml", "html", "htm",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx",
        "c", "cpp", "h", "cs", "go", "rs", "rb", "php", "sh",
        "yml", "yaml", "gradle", "properties", "toml", "ini", "log", "sql"
    )

    data class Result(val attachment: Attachment?, val error: String?)

    fun read(context: Context, uri: Uri): Result {
        val name = displayName(context, uri)
        val mime = context.contentResolver.getType(uri) ?: ""
        val extension = name.substringAfterLast('.', "").lowercase()

        return try {
            when {
                mime == "application/zip" || extension == "zip" -> readZip(context, uri, name)
                mime == "application/pdf" || extension == "pdf" -> Result(
                    null,
                    "⚠️ ملفات PDF غير مدعومة حالياً. حوّل الصفحات إلى نص أو استخدم ملفاً نصياً."
                )
                mime.startsWith("text/") || extension in TEXT_EXTENSIONS -> readText(context, uri, name)
                else -> Result(
                    null,
                    "⚠️ نوع الملف ($mime) غير مدعوم. المدعوم: النصوص، الكود، وZIP."
                )
            }
        } catch (e: Exception) {
            Result(null, "⚠️ تعذر قراءة الملف: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun readText(context: Context, uri: Uri, name: String): Result {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).use { it.readLimited(MAX_CHARS) }
        }
        return if (text.isNullOrBlank()) {
            Result(null, "⚠️ الملف فارغ أو غير قابل للقراءة كنص UTF-8.")
        } else {
            Result(Attachment(name, text), null)
        }
    }

    private fun readZip(context: Context, uri: Uri, name: String): Result {
        val entries = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && entries.size < 200) {
                    entries.add(entry.name)
                    entry = zip.nextEntry
                }
            }
        }
        if (entries.isEmpty()) return Result(null, "⚠️ الأرشيف فارغ أو غير صالح.")
        val listing = buildString {
            append("محتويات الأرشيف (").append(entries.size).append(" عنصر):\n")
            entries.forEach { append("- ").append(it).append('\n') }
        }
        return Result(Attachment(name, listing), null)
    }

    private fun displayName(context: Context, uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "ملف"
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(index) ?: name
                }
            }
        }
        return name
    }

    private fun BufferedReader.readLimited(max: Int): String {
        val builder = StringBuilder()
        val buffer = CharArray(4096)
        while (builder.length < max) {
            val count = read(buffer)
            if (count < 0) break
            builder.append(buffer, 0, minOf(count, max - builder.length))
        }
        return builder.toString()
    }
}
