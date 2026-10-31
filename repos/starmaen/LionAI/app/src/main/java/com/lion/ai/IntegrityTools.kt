package com.lion.ai

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/**
 * فحص سلامة ملفات المشروع: بصمات، ملفات فارغة/مكررة/متجاوزة الحد،
 * ومقارنة مع خط أساس محفوظ لكشف التعديل أو الحذف أو الإضافة.
 */
object IntegrityTools {
    fun verify(
        context: Context,
        path: String = "",
        algorithm: String = "sha256",
        baselinePath: String = "",
        maxFiles: Int = 5000
    ): String {
        val hashes = FileTools.hashAll(context, path, algorithm, maxFiles)
        if (hashes.isEmpty()) return "المسار فارغ أو لا يحتوي ملفات: ${path.ifBlank { "المساحة" }}"
        val baselineRel = runCatching {
            FileTools.relative(context, FileTools.file(context, baselinePath))
        }.getOrNull()
        val hashed = hashes.filter { it.hash != null && it.path != baselineRel }
        val failed = hashes.filter { it.hash == null }
        val zero = hashed.filter { it.size == 0L }
        val duplicates = hashed.filter { it.size > 0L }
            .groupBy { it.hash!! }
            .filterValues { it.size > 1 }
            .values
            .sortedByDescending { group -> group.sumOf { it.size } }
        val largest = hashed.sortedByDescending { it.size }.take(5)

        return buildString {
            append("🛡️ فحص سلامة الملفات (${algorithm.uppercase()})\n")
            append("• المسار: ").append(path.ifBlank { "مساحة العمل" }).append('\n')
            append("• الملفات: ").append(hashes.size)
            append(" | مبصومة: ").append(hashed.size)
            append(" | فشلت: ").append(failed.size).append('\n')
            append("• الحجم الكلي: ").append(FileTools.formatSize(hashes.sumOf { it.size })).append('\n')
            append("• البصمة المجمّعة: ").append(fingerprint(hashed, algorithm)).append('\n')
            if (zero.isNotEmpty()) {
                append("\n⚠️ ملفات فارغة (").append(zero.size).append("):\n")
                zero.take(15).forEach { append("  - ").append(it.path).append('\n') }
            }
            if (duplicates.isNotEmpty()) {
                append("\n♻️ محتوى مكرر (").append(duplicates.size).append(" مجموعة):\n")
                duplicates.take(8).forEach { group ->
                    append("  - ").append(FileTools.formatSize(group.first().size)).append(": ")
                    append(group.take(5).joinToString("، ") { it.path })
                    if (group.size > 5) append(" … +").append(group.size - 5)
                    append('\n')
                }
            }
            if (failed.isNotEmpty()) {
                append("\n⏭️ لم تُبصم (").append(failed.size).append("):\n")
                failed.take(10).forEach { append("  - ").append(it.path).append(": ").append(it.error).append('\n') }
            }
            append("\n📦 أكبر الملفات:\n")
            largest.forEach { append("  - ").append(it.path).append(" (").append(FileTools.formatSize(it.size)).append(")\n") }

            if (baselinePath.isNotBlank()) {
                val baseline = loadBaseline(context, baselinePath)
                if (baseline == null) {
                    append("\n❌ تعذر قراءة خط الأساس: ").append(baselinePath).append('\n')
                } else {
                    val current = hashed.associate { it.path to it.hash!! }
                    val matches = current.filter { (key, value) -> baseline[key] == value }.size
                    val changed = current.keys.filter { key -> baseline.containsKey(key) && baseline[key] != current[key] }
                    val missing = baseline.keys - current.keys
                    val added = current.keys - baseline.keys
                    append("\n📊 مقارنة بخط الأساس (").append(baselinePath).append("):\n")
                    append("• مطابق: ").append(matches)
                    append(" | متغير: ").append(changed.size)
                    append(" | مفقود: ").append(missing.size)
                    append(" | جديد: ").append(added.size).append('\n')
                    if (changed.isNotEmpty()) {
                        append("✏️ تغيّرت:\n")
                        changed.take(15).forEach { append("  - ").append(it).append('\n') }
                    }
                    if (missing.isNotEmpty()) {
                        append("🗑️ حُذفت:\n")
                        missing.sorted().take(15).forEach { append("  - ").append(it).append('\n') }
                    }
                    if (added.isNotEmpty()) {
                        append("➕ أُضيفت:\n")
                        added.sorted().take(15).forEach { append("  - ").append(it).append('\n') }
                    }
                }
            }
        }.trim()
    }

    fun saveBaseline(
        context: Context,
        path: String = "",
        output: String = "integrity-baseline.json",
        algorithm: String = "sha256",
        maxFiles: Int = 5000
    ): String {
        val hashes = FileTools.hashAll(context, path, algorithm, maxFiles)
        val outputRel = runCatching { FileTools.relative(context, FileTools.file(context, output)) }.getOrNull()
        val files = JSONObject()
        hashes.filter { it.hash != null && it.path != outputRel }.forEach { files.put(it.path, it.hash) }
        val json = JSONObject()
            .put("version", 1)
            .put("algorithm", algorithm.lowercase())
            .put("generatedAt", System.currentTimeMillis())
            .put("root", path)
            .put("fileCount", files.length())
            .put("files", files)
        FileTools.write(context, output, json.toString(2) + "\n")
        return "✅ حُفظ خط الأساس في $output (${files.length()} ملفاً، ${algorithm.uppercase()})"
    }

    private fun loadBaseline(context: Context, path: String): Map<String, String>? {
        return runCatching {
            val text = FileTools.read(context, path, 8_000_000).text
            val root = JSONObject(text)
            val files = root.optJSONObject("files") ?: root
            val map = mutableMapOf<String, String>()
            files.keys().forEach { key ->
                val value = files.optString(key).trim()
                if (value.isNotBlank()) map[key] = value
            }
            map
        }.getOrNull()
    }

    private fun fingerprint(hashes: List<FileTools.EntryHash>, algorithm: String): String {
        val name = when (algorithm.lowercase()) {
            "md5" -> "MD5"
            "sha1", "sha-1" -> "SHA-1"
            else -> "SHA-256"
        }
        val digest = MessageDigest.getInstance(name)
        hashes.sortedBy { it.path }.forEach { entry ->
            digest.update(entry.path.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(entry.hash!!.toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
