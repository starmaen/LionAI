package com.lion.ai

import android.content.Context
import java.io.File

/**
 * تشخيص فشل البناء: يحوّل سجل Gradle/GitHub Actions الطويل إلى قائمة أخطاء مرتبة (ملف:سطر — الرسالة)
 * مع مقتطف الكود من مساحة العمل، والمهمة الفاشلة والسبب الجذري، حتى يصلح المزود الخطأ الصحيح مباشرة.
 */
object BuildDiagnose {
    private val TIMESTAMP = Regex("(?m)^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ")
    private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private val FAILED_TASK = Regex("Execution failed for task '([^']+)'")
    private val WENT_WRONG = Regex("What went wrong:\\s*\\n(?:Execution failed for task '[^']+'\\.\\s*\\n)?([^\\n]+)")
    private val FAILED_TEST = Regex("(?m)^(\\S+ > .+ FAILED)$")
    private val RUNNER_PREFIX = Regex("^/home/runner/work/[^/]+/[^/]+/")

    class Report(
        val failedTask: String,
        val cause: String,
        val errors: List<String>,
        val failedTests: List<String>
    )

    /** ينظّف طوابع GitHub الزمنية وألوان ANSI ليعمل المحلل على أسطر نظيفة. */
    fun clean(raw: String): String = ANSI.replace(TIMESTAMP.replace(raw, ""), "")

    fun analyze(raw: String): Report {
        val text = clean(raw)
        val errors = ShellTools.parseBuildErrors(text).map { RUNNER_PREFIX.replace(it, "") }
        val task = FAILED_TASK.find(text)?.groupValues?.get(1).orEmpty()
        val cause = WENT_WRONG.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val tests = FAILED_TEST.findAll(text).map { it.groupValues[1].trim() }.distinct().toList()
        return Report(task, cause, errors, tests)
    }

    /** مقتطف ±3 أسطر حول الخطأ إن وُجد الملف في مساحة العمل. */
    private fun snippet(context: Context, entry: String): String {
        val location = entry.substringBefore(" — ")
        val path = location.substringBeforeLast(':', "")
        val line = location.substringAfterLast(':', "").toIntOrNull() ?: return ""
        if (path.isBlank()) return ""
        val file = findFile(context, path) ?: return ""
        val lines = runCatching { file.readLines() }.getOrNull() ?: return ""
        if (line < 1 || line > lines.size) return ""
        val from = (line - 3).coerceAtLeast(1)
        val to = (line + 3).coerceAtMost(lines.size)
        return (from..to).joinToString("\n") { n ->
            (if (n == line) "   >> " else "      ") + n + " | " + lines[n - 1].take(160)
        }
    }

    private fun findFile(context: Context, path: String): File? {
        runCatching { FileTools.file(context, path) }.getOrNull()?.takeIf { it.isFile }?.let { return it }
        val name = path.substringAfterLast('/')
        val match = runCatching { FileTools.walk(context, "", 4000) }.getOrDefault(emptyList())
            .firstOrNull { !it.directory && (it.path == path || it.path.endsWith("/$path") || it.path.substringAfterLast('/') == name) }
            ?: return null
        return runCatching { FileTools.file(context, match.path) }.getOrNull()?.takeIf { it.isFile }
    }

    fun report(context: Context, raw: String): String {
        if (raw.isBlank()) return "لا يوجد سجل لتحليله."
        val r = analyze(raw)
        if (r.errors.isEmpty() && r.failedTask.isBlank() && r.failedTests.isEmpty()) {
            val hints = clean(raw).lineSequence()
                .filter { Regex("(?i)(error|failed|exception|cannot|unresolved)").containsMatchIn(it) }
                .map { it.trim().take(200) }.distinct().take(15).toList()
            return if (hints.isEmpty()) "✅ لم أجد أخطاء بناء واضحة في السجل."
            else "⚠️ لم أستخرج أخطاء منسّقة، لكن هذه أسطر مشبوهة:\n" + hints.joinToString("\n") { "• $it" }
        }
        val sb = StringBuilder("📛 تشخيص فشل البناء\n")
        if (r.failedTask.isNotBlank()) sb.append("• المهمة الفاشلة: ").append(r.failedTask).append('\n')
        if (r.cause.isNotBlank()) sb.append("• السبب: ").append(r.cause.take(300)).append('\n')
        if (r.errors.isNotEmpty()) {
            sb.append("• الأخطاء (").append(r.errors.size).append("):\n")
            r.errors.take(12).forEachIndexed { index, entry ->
                sb.append("  ").append(index + 1).append(") ").append(entry.take(300)).append('\n')
                val code = snippet(context, entry)
                if (code.isNotBlank()) sb.append(code).append('\n')
            }
            if (r.errors.size > 12) sb.append("  …(+").append(r.errors.size - 12).append(" أخطاء أخرى)\n")
        }
        if (r.failedTests.isNotEmpty()) {
            sb.append("• اختبارات فاشلة (").append(r.failedTests.size).append("):\n")
            r.failedTests.take(10).forEach { sb.append("  - ").append(it.take(200)).append('\n') }
        }
        sb.append("\nالتالي: صحّح الملفات أعلاه (file_apply_patch/code_patch) ← تحقق (code_doctor) ← ")
        sb.append("github_push_workspace ← github_dispatch ثم github_wait_run ← أعد build_diagnose حتى ينجح البناء.")
        return sb.toString().take(15_000)
    }
}
