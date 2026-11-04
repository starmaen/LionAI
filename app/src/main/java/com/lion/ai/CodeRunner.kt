package com.lion.ai

import android.content.Context
import java.io.File
import java.util.UUID

/** تشغيل آمن نسبياً لمقتطفات المحادثة داخل Alpine ومساحة العمل فقط، بمهلة قصيرة. */
object CodeRunner {
    internal data class Plan(val extension: String, val command: String)

    internal fun plan(language: String): Plan? = when (language.trim().lowercase()) {
        "js", "javascript", "node" -> Plan("js", "node")
        "ts", "typescript" -> Plan("ts", "node")
        "sh", "bash", "shell" -> Plan("sh", "/bin/busybox sh")
        "sql", "sqlite", "sqlite3" -> Plan("sql", "sqlite3 -header -column :memory: <")
        else -> null
    }

    fun run(context: Context, language: String, code: String, timeoutSec: Int = 30): ShellResult {
        val plan = plan(language) ?: return ShellResult(
            false, "التشغيل مدعوم لـ JavaScript/TypeScript وShell/Bash وSQL/SQLite.", 126, 0
        )
        if (!LinuxEnv.ready(context)) return ShellResult(false, "فعّل بيئة Linux أولاً.", 1, 0)
        if (code.isBlank()) return ShellResult(false, "كتلة الكود فارغة.", 1, 0)
        if (code.length > 300_000) return ShellResult(false, "المقتطف أكبر من 300KB.", 1, 0)

        val id = UUID.randomUUID().toString().replace("-", "")
        val relative = ".lion-run/$id.${plan.extension}"
        val file = FileTools.file(context, relative)
        return try {
            file.parentFile?.mkdirs()
            file.writeText(code)
            val guest = "/workspace/$relative"
            val command = "${plan.command} $guest"
            val result = LinuxEnv.run(context, command, timeoutSec.coerceIn(5, 30), FileTools.root(context))
            result.copy(output = "=== ${language.lowercase()} • exit ${result.exitCode} ===\n" + result.output)
        } catch (e: Exception) {
            ShellResult(false, "تعذر تشغيل المقتطف: ${e.message ?: e.javaClass.simpleName}", 1, 0)
        } finally {
            file.delete()
            file.parentFile?.let { parent ->
                if (parent.listFiles()?.isEmpty() == true) parent.delete()
            }
        }
    }
}
