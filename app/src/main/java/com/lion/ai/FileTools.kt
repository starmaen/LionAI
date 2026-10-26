package com.lion.ai

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/** مساحة عمل محلية داخل التطبيق: ملفات المشاريع، ZIP، وقراءة/كتابة آمنة. */
object FileTools {
    data class Info(val path: String, val size: Long, val binary: Boolean, val text: String, val truncated: Boolean)
    data class Entry(val path: String, val size: Long, val directory: Boolean)
    data class Match(val path: String, val line: Int, val text: String)

    data class CsvInfo(
        val name: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        val rowCount: Int,
        val columnCount: Int
    )

    fun fileType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "cpp", "h", "cs", "go", "rs", "rb", "php", "swift", "dart", "sh" -> "code"
            "json" -> "json"
            "yaml", "yml" -> "yaml"
            "xml" -> "xml"
            "html", "htm" -> "html"
            "css", "scss" -> "style"
            "md", "markdown" -> "markdown"
            "txt" -> "text"
            "csv" -> "csv"
            "sql" -> "sql"
            "zip" -> "archive"
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> "image"
            "pdf" -> "pdf"
            "docx", "doc", "xlsx", "xls", "pptx", "ppt" -> "office"
            else -> "unknown"
        }
    }

    fun language(name: String): String? = when (fileType(name)) {
        "code" -> com.lion.ai.CodeAnalyzer.languageFor(name)
        "json" -> "JSON"
        "yaml" -> "YAML"
        "xml" -> "XML"
        "html" -> "HTML"
        "style" -> "CSS"
        "markdown" -> "Markdown"
        "sql" -> "SQL"
        else -> null
    }

    fun readCsv(context: Context, path: String): CsvInfo {
        val info = read(context, path, 4_000_000)
        if (info.binary) throw IllegalArgumentException("الملف ثنائي وليس CSV")
        val lines = info.text.lines().filter { it.isNotBlank() }
        val parsed = lines.map { parseCsvLine(it) }
        val headers = parsed.firstOrNull().orEmpty()
        val rows = parsed.drop(1)
        return CsvInfo(
            name = info.path,
            headers = headers,
            rows = rows,
            rowCount = rows.size,
            columnCount = headers.size
        )
    }

    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val c = line[index]
            when {
                c == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    cells.add(cell.toString().trim())
                    cell.clear()
                }
                else -> cell.append(c)
            }
            index++
        }
        cells.add(cell.toString().trim())
        return cells
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    fun root(context: Context): File = File(context.filesDir, "workspace").apply { mkdirs() }

    private fun resolve(context: Context, path: String): File {
        val root = root(context).canonicalFile
        val clean = path.trim().removePrefix("/").ifBlank { "." }
        val target = File(root, clean).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw IllegalArgumentException("مسار غير مسموح خارج مساحة العمل")
        }
        if (target.name.startsWith(".lion-")) throw IllegalArgumentException("مسار محجوز")
        return target
    }

    fun relative(context: Context, file: File): String =
        runCatching { file.canonicalFile.relativeTo(root(context).canonicalFile).path }.getOrDefault(file.name)

    fun list(context: Context, path: String = ""): List<Entry> {
        val dir = resolve(context, path)
        if (!dir.exists()) return emptyList()
        if (dir.isFile) return listOf(Entry(relative(context, dir), dir.length(), false))
        val files = dir.listFiles()?.toList().orEmpty()
        return files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).map {
            Entry(relative(context, it), if (it.isDirectory) 0L else it.length(), it.isDirectory)
        }
    }

    fun read(context: Context, path: String, maxBytes: Int = 1_000_000): Info {
        val file = resolve(context, path)
        if (!file.exists() || !file.isFile) throw IllegalArgumentException("الملف غير موجود: $path")
        if (file.length() > maxBytes) {
            val bytes = file.inputStream().use { it.readBytesLimited(maxBytes) }
            val binary = bytes.take(4096).count { it.toInt() == 0 } > 0
            return Info(
                path = relative(context, file),
                size = file.length(),
                binary = binary,
                text = if (binary) "" else runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault(""),
                truncated = true
            )
        }
        val bytes = file.readBytes()
        val binary = bytes.take(4096).count { it.toInt() == 0 } > 0
        return Info(
            path = relative(context, file),
            size = file.length(),
            binary = binary,
            text = if (binary) "" else runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault(""),
            truncated = false
        )
    }

    fun write(context: Context, path: String, content: String): Entry {
        val file = resolve(context, path)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return Entry(relative(context, file), file.length(), false)
    }

    fun delete(context: Context, path: String, recursive: Boolean = false): Boolean {
        val file = resolve(context, path)
        if (!file.exists()) return false
        if (file.isDirectory && !recursive) throw IllegalArgumentException("المجلد غير فارغ؛ استخدم recursive=true")
        return file.deleteRecursively()
    }

    fun mkdir(context: Context, path: String): Boolean = resolve(context, path).apply { mkdirs() }.isDirectory

    fun zipList(path: File): List<Entry> {
        if (!path.exists()) throw IllegalArgumentException("الملف غير موجود")
        ZipFile(path).use { zip ->
            return zip.entries().asSequence().take(1000).map {
                Entry(it.name, it.size.coerceAtLeast(0), it.isDirectory)
            }.toList()
        }
    }

    fun zipRead(path: File, entryName: String, maxBytes: Int = 500_000): String {
        ZipFile(path).use { zip ->
            val entry = zip.getEntry(entryName) ?: throw IllegalArgumentException("المدخل غير موجود: $entryName")
            if (entry.isDirectory) return ""
            val bytes = zip.getInputStream(entry).use { it.readBytesLimited(maxBytes) }
            return runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
        }
    }

    fun zipCreate(context: Context, target: String, sources: List<String>): Entry {
        val out = resolve(context, target)
        out.parentFile?.mkdirs()
        java.util.zip.ZipOutputStream(out.outputStream()).use { zip ->
            sources.forEach { source ->
                val file = resolve(context, source)
                if (file.isDirectory) {
                    file.walkTopDown().filter { it.isFile }.forEach { child ->
                        addZipEntry(zip, relative(context, child), child)
                    }
                } else if (file.isFile) {
                    addZipEntry(zip, relative(context, file), file)
                }
            }
        }
        return Entry(relative(context, out), out.length(), false)
    }

    private fun addZipEntry(zip: java.util.zip.ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(java.util.zip.ZipEntry(name.replace(File.separatorChar, '/')))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /** بحث نصي داخل مساحة العمل (اسم/مسار/محتوى) مع تجاهل الملفات الثنائية والكبيرة. */
    fun search(
        context: Context,
        query: String,
        path: String = "",
        maxResults: Int = 100,
        caseSensitive: Boolean = false
    ): List<Match> {
        if (query.isBlank()) throw IllegalArgumentException("نص البحث مطلوب")
        val root = resolve(context, path)
        val files = if (root.isFile) listOf(root) else
            root.walkTopDown().onEnter { !it.name.startsWith(".lion-") }
                .filter { it.isFile && it.length() <= 512_000 }
                .take(5000).toList()
        val needle = if (caseSensitive) query else query.lowercase()
        val results = mutableListOf<Match>()
        files.forEach { file ->
            if (results.size >= maxResults) return@forEach
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
            if (bytes.take(2048).any { it.toInt() == 0 }) return@forEach
            val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return@forEach
            val relative = relative(context, file)
            if ((if (caseSensitive) relative else relative.lowercase()).contains(needle)) {
                results.add(Match(relative, 0, relative))
            }
            if (results.size >= maxResults) return@forEach
            text.lineSequence().forEachIndexed { index, line ->
                if (results.size >= maxResults) return@forEach
                val hay = if (caseSensitive) line else line.lowercase()
                if (hay.contains(needle)) {
                    results.add(Match(relative, index + 1, line.trim().take(240)))
                }
            }
        }
        return results
    }

    /** فك ضغط ZIP داخل مساحة العمل مع منع الخروج عن الجذر (zip-slip). */
    fun extractZip(context: Context, zipPath: String, target: String = "", overwrite: Boolean = false): Int {
        val archive = resolve(context, zipPath)
        if (!archive.isFile) throw IllegalArgumentException("ملف ZIP غير موجود: $zipPath")
        val root = root(context).canonicalFile
        val targetDir = resolve(context, target.ifBlank { zipPath.substringBeforeLast('.', "unzipped") })
        targetDir.mkdirs()
        var count = 0
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                if (count >= 3000) break
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val out = File(targetDir, entry.name).canonicalFile
                if (out != root && !out.path.startsWith(root.path + File.separator)) {
                    throw IllegalArgumentException("مدخل غير آمن في الأرشيف: ${entry.name}")
                }
                if (out.exists() && !overwrite) continue
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                count++
            }
        }
        return count
    }

    fun exportTo(context: Context, source: String, target: File) {
        val file = resolve(context, source)
        target.outputStream().use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private fun java.io.InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 65536))
        val buffer = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val read = read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }
}
