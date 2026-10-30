package com.lion.ai

import android.content.Context
import java.io.File
import java.util.regex.Pattern

/** فحص سريع قبل الدفع: كشف الأسرار الشائعة داخل مساحة العمل. */
object SecurityTools {
    private data class Rule(val label: String, val pattern: Pattern)

    private val RULES = listOf(
        Rule("OpenAI/DeepSeek key", Pattern.compile("sk-[A-Za-z0-9_-]{20,}")),
        Rule("GitHub token", Pattern.compile("gh[pousr]_[A-Za-z0-9]{30,}")),
        Rule("Google API key", Pattern.compile("AIza[0-9A-Za-z_-]{35}")),
        Rule("AWS access key", Pattern.compile("AKIA[0-9A-Z]{16}")),
        Rule("Slack token", Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}")),
        Rule("Private key", Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")),
        Rule("Bearer token literal", Pattern.compile("Bearer\\s+[A-Za-z0-9._-]{30,}")),
        Rule("Telegram bot token", Pattern.compile("\\b\\d{8,10}:AA[A-Za-z0-9_-]{30,}"))
    )

    private val IGNORED_DIRS = setOf(".git", "build", ".gradle", ".idea", "node_modules", "keystore")
    private val TEXT_EXTENSIONS = setOf(
        "kt", "kts", "java", "js", "ts", "tsx", "py", "rb", "go", "rs", "c", "h", "cpp",
        "json", "yaml", "yml", "xml", "md", "txt", "gradle", "properties", "env", "sh",
        "html", "css", "toml", "ini", "conf", "sql", "csv"
    )

    fun scan(context: Context, subPath: String = "", maxFiles: Int = 800, maxBytes: Long = 1_000_000L): String {
        val root = FileTools.root(context)
        val target = if (subPath.isBlank()) root else runCatching { FileTools.file(context, subPath) }.getOrDefault(root)
        if (!target.exists()) return "⚠️ المسار غير موجود: $subPath"
        val files = mutableListOf<File>()
        collect(target, files, maxFiles)
        val findings = mutableListOf<String>()
        var scanned = 0
        files.forEach { file ->
            if (file.length() > maxBytes) return@forEach
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            scanned++
            val lines = text.lineSequence().toList()
            RULES.forEach { rule ->
                lines.forEachIndexed { index, line ->
                    val matcher = rule.pattern.matcher(line)
                    if (matcher.find()) {
                        val masked = line.trim().take(60).replace(Regex("([A-Za-z0-9_-]{12})[A-Za-z0-9_-]+"), "$1…")
                        findings.add("• ${rule.label} في ${FileTools.relative(context, file)}:${index + 1} — $masked")
                    }
                }
            }
        }
        if (findings.isEmpty()) {
            return "✅ لا أسرار ظاهرة. فُحص $scanned ملفاً في ${if (subPath.isBlank()) "مساحة العمل" else subPath}."
        }
        return buildString {
            append("⛔ عُثر على ").append(findings.size).append(" اشتباه تسريب أسرار. لا تدفع قبل المعالجة:\n\n")
            append(findings.take(40).joinToString("\n"))
            append("\n\nأزل المفتاح من الملف، أبطله من لوحة الخدمة، ثم استخدم متغيرات بيئة/Secrets.")
        }
    }

    private fun collect(dir: File, out: MutableList<File>, maxFiles: Int) {
        if (out.size >= maxFiles) return
        val children = dir.listFiles() ?: return
        children.forEach { child ->
            if (out.size >= maxFiles) return
            if (child.isDirectory) {
                if (child.name !in IGNORED_DIRS) collect(child, out, maxFiles)
            } else {
                val extension = child.extension.lowercase()
                if (extension in TEXT_EXTENSIONS || child.name.startsWith(".env")) out.add(child)
            }
        }
    }
}
