package com.lion.ai

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ShellResult(
    val ok: Boolean,
    val output: String,
    val exitCode: Int,
    val elapsedMs: Long,
    val timedOut: Boolean = false
)

/**
 * تنفيذ محصور داخل مساحة عمل التطبيق. ليست صدفة مفتوحة:
 * قائمة أوامر مسموحة + منع الخروج من الجذر + مهلة + سقف مخرجات.
 */
object ShellTools {
    private val ALLOWED = setOf(
        "git", "gradle", "./gradlew", "sh", "bash", "ls", "find", "grep", "rg", "cat", "sed",
        "awk", "wc", "head", "tail", "sort", "uniq", "mkdir", "cp", "mv", "rm", "touch",
        "zip", "unzip", "tar", "adb", "emulator", "avdmanager", "sdkmanager", "apkanalyzer",
        "apksigner", "bundletool", "java", "node", "npm", "npx", "python", "python3", "pip",
        "pip3", "pkg", "apt", "apt-get", "which", "command",
        "make", "cmake", "clang", "clang++", "go", "cargo", "rustc", "php", "ruby", "sqlite3", "shellcheck",
        "patch", "diff", "wget", "tree", "fd", "openssl", "ruff", "flake8", "black", "mypy", "pytest",
        "eslint", "prettier", "tsc"
    )

    /** حزم مسموح تثبيتها فقط (لا يُمرَّر اسم الحزمة من النموذج مباشرة إلى الصدفة). */
    private val PACKAGES = mapOf(
        "git" to listOf("git"),
        "python" to listOf("python3", "python"),
        "node" to listOf("nodejs", "node"),
        "java" to listOf("openjdk-17-jdk-headless", "openjdk-17"),
        "gradle" to listOf("gradle"),
        "curl" to listOf("curl"),
        "jq" to listOf("jq"),
        "unzip" to listOf("unzip"),
        "zip" to listOf("zip"),
        "tar" to listOf("tar"),
        "ffmpeg" to listOf("ffmpeg"),
        "build-essential" to listOf("build-essential"),
        "openssh" to listOf("openssh"),
        // أدوات تطوير اختيارية (المفتاح = اسم الأمر الذي يُفحص وجوده)
        "rg" to listOf("ripgrep"),
        "make" to listOf("make"),
        "cmake" to listOf("cmake"),
        "clang" to listOf("clang"),
        "go" to listOf("golang"),
        "cargo" to listOf("rust"),
        "php" to listOf("php"),
        "ruby" to listOf("ruby"),
        "sqlite3" to listOf("sqlite"),
        "shellcheck" to listOf("shellcheck"),
        "patch" to listOf("patch"),
        "tree" to listOf("tree"),
        "fd" to listOf("fd"),
        "wget" to listOf("wget"),
        "openssl" to listOf("openssl")
    )
    /** أدوات مسموح استيرادها عبر pip/npm من الإنترنت (اختيارياً): الاسم يجب أن يكون في القائمة. */
    private val PIP_TOOLS = setOf("ruff", "flake8", "black", "mypy", "pytest", "pylint", "bandit")
    private val NPM_TOOLS = setOf("typescript", "eslint", "prettier", "tsx", "jshint")
    private val BLOCKED = listOf(
        Regex("(^|\\s)(sudo|su|mount|umount|reboot|shutdown|mkfs|dd|iptables|setenforce)(\\s|$)"),
        Regex("rm\\s+-[a-z]*r[a-z]*f?\\s+/(\\s|$)"),
        Regex("cd\\s+/(\\s|$)"),
        Regex("cd\\s+\\.\\.(/|\\s|$)"),
        Regex("(\\.\\./){2,}"),
        Regex("(/etc/|/proc/|/sys/)"),
        Regex("(curl|wget)[^\\n|]*\\|\\s*(sh|bash)"),
        Regex("(^|[;&|]\\s*)(sh|bash)\\s+-[a-z]*c(\\s|$)"),
        Regex("(^|\\s)chmod\\s+777\\s+/")
    )
    private const val MAX_OUTPUT = 18_000

    fun exec(
        context: Context,
        command: String,
        timeoutSec: Int = 120,
        cwd: String = ""
    ): ShellResult {
        val cleaned = command.trim()
        if (cleaned.isBlank()) return ShellResult(false, "❌ أمر فارغ", 1, 0)
        val first = cleaned.substringBefore(' ').substringBefore('\t')
        if (first !in ALLOWED) {
            return ShellResult(
                false,
                "⛔ الأمر «$first» غير مسموح. المسموح: ${ALLOWED.sorted().joinToString("، ")}",
                126,
                0
            )
        }
        BLOCKED.firstOrNull { it.containsMatchIn(cleaned) }?.let { pattern ->
            return ShellResult(false, "⛔ أمر مرفوض أمنياً: ${pattern.pattern}", 126, 0)
        }
        val root = FileTools.file(context, cwd)
        if (!root.exists()) return ShellResult(false, "❌ مجلد العمل غير موجود: $cwd", 1, 0)
        val temp = File(FileTools.root(context), ".lion-tmp").apply { mkdirs() }
        val started = System.currentTimeMillis()
        return try {
            val shell = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
            val process = ProcessBuilder(shell, "-c", cleaned)
                .directory(root)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = FileTools.root(context).absolutePath
                    environment()["TMPDIR"] = temp.absolutePath
                }
                .start()
            val output = StringBuilder()
            val reader = thread(start = true, name = "lion-shell-reader") {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                    var line = input.readLine()
                    while (line != null) {
                        if (output.length < MAX_OUTPUT) {
                            output.append(line).append('\n')
                        }
                        line = input.readLine()
                    }
                }
            }
            val finished = process.waitFor(timeoutSec.coerceIn(5, 900).toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            reader.join(1500)
            val elapsed = System.currentTimeMillis() - started
            val code = runCatching { process.exitValue() }.getOrDefault(if (finished) 1 else 124)
            val text = output.toString().take(MAX_OUTPUT)
            ShellResult(
                ok = finished && code == 0,
                output = if (text.isBlank()) "(لا مخرجات)" else text,
                exitCode = code,
                elapsedMs = elapsed,
                timedOut = !finished
            )
        } catch (e: Exception) {
            ShellResult(false, "❌ تعذر التنفيذ: ${e.message ?: e.javaClass.simpleName}", 1, 0)
        }
    }

    fun which(context: Context, name: String): String {
        val clean = name.trim().filter { it.isLetterOrDigit() || it in "._-/" }
        if (clean.isBlank() || clean.contains("..")) return "اسم أمر غير صالح"
        if (clean.contains('/')) {
            val direct = File(clean)
            return if (direct.exists()) direct.absolutePath else "غير موجود: $clean"
        }
        val fixed = listOf(
            "/system/bin", "/system/xbin", "/vendor/bin", "/data/local/bin",
            "/data/data/com.termux/files/usr/bin", "/usr/bin", "/usr/local/bin", "/bin"
        )
        val fromPath = System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        (fromPath + fixed).distinct().forEach { dir ->
            val file = File(dir, clean)
            if (file.isFile && file.canExecute()) return file.absolutePath
        }
        val workspace = FileTools.root(context)
        val local = File(workspace, clean)
        if (local.isFile && local.canExecute()) return local.absolutePath
        return "غير موجود في PATH (تحقق عبر shell_exec أو ثبّت الأداة)"
    }

    fun buildAndroid(
        context: Context,
        projectRoot: String = "",
        task: String = ":app:assembleDebug",
        timeoutSec: Int = 600
    ): ShellResult {
        val safeTask = task.trim().ifBlank { ":app:assembleDebug" }
        if (!Regex("[A-Za-z0-9:_-]+").matches(safeTask)) {
            return ShellResult(false, "❌ اسم مهمة Gradle غير صالح", 1, 0)
        }
        val root = FileTools.file(context, projectRoot)
        val gradlew = File(root, "gradlew").takeIf { it.exists() }
            ?: File(FileTools.root(context), "gradlew").takeIf { it.exists() }
        if (gradlew == null) {
            return ShellResult(
                false,
                "❌ لم أجد gradlew في مساحة العمل. انسخ المشروع أولاً (github_clone) أو أضف Gradle Wrapper.",
                1,
                0
            )
        }
        val dir = gradlew.parentFile ?: root
        val prefix = if (dir == FileTools.root(context)) "" else FileTools.relative(context, dir) + "/"
        val command = "sh ${prefix}gradlew $safeTask --no-daemon --console=plain"
        val result = exec(context, command, timeoutSec, cwd = FileTools.relative(context, dir))
        val errors = parseBuildErrors(result.output)
        val output = buildString {
            append(result.output)
            if (errors.isNotEmpty()) {
                append("\n\n=== أخطاء محللة (file:line) ===\n")
                errors.take(40).forEach { append(it).append('\n') }
            }
            if (!result.ok) {
                append("\n— تلميح: إن كان JDK/Android SDK غير متاح على الجهاز فاستخدم github_dispatch لبناء APK في Actions.")
            }
        }
        return result.copy(output = output.take(MAX_OUTPUT))
    }

    private val WRITE_COMMAND = Regex(
        "(^|[;&|]\\s*)(rm|mv|cp|mkdir|touch|tee|dd|chmod|chown|install|ln|truncate)\\b|" +
            ">>?\\s*[^&|]|\\bsed\\s+-i|\\bgit\\s+(apply|checkout|reset|clean|commit|push|merge|rebase)\\b|" +
            "\\b(npm|pnpm|yarn)\\s+(install|add|remove|update)\\b|\\bpip3?\\s+(install|uninstall)\\b"
    )

    /** يفرّق بين أوامر الفحص والأوامر التي تكتب/تحذف، لتطبيق حوكمة Manifest عليها. */
    fun looksLikeWrite(command: String): Boolean = WRITE_COMMAND.containsMatchIn(command)

    fun parseBuildErrors(raw: String): List<String> {
        val results = LinkedHashSet<String>()
        Regex("""(?m)^e:\s+file://([^:]+):\s*\((\d+),\s*(\d+)\):\s*(.+)$""").findAll(raw).forEach {
            results.add("${it.groupValues[1]}:${it.groupValues[2]} — ${it.groupValues[4].trim()}")
        }
        Regex("""(?m)^(.+?\.(?:kt|java|xml|gradle|kts)):(\d+):\s*(?:error:\s*)?(.+)$""").findAll(raw).forEach {
            results.add("${it.groupValues[1]}:${it.groupValues[2]} — ${it.groupValues[3].trim()}")
        }
        Regex("""(?m)^(.+?\.(?:kt|java)):(\d+),\s*(\d+):\s*(.+)$""").findAll(raw).forEach {
            results.add("${it.groupValues[1]}:${it.groupValues[2]} — ${it.groupValues[4].trim()}")
        }
        return results.toList()
    }

    fun installAndRun(
        context: Context,
        apkPath: String,
        packageName: String = "",
        activity: String = ""
    ): ShellResult {
        val apk = runCatching { FileTools.file(context, apkPath) }.getOrNull()
            ?: return ShellResult(false, "❌ مسار APK غير صالح", 1, 0)
        if (!apk.isFile) return ShellResult(false, "❌ ملف APK غير موجود: $apkPath", 1, 0)
        val install = exec(
            context,
            "adb install -r \"${apk.absolutePath}\"",
            300
        )
        if (!install.ok) return install
        if (packageName.isBlank()) return install.copy(output = install.output + "\n✅ ثُبّت APK. حدّد package_name/activity للتشغيل التلقائي.")
        val component = if (activity.isBlank()) packageName else "$packageName/$activity"
        val run = exec(context, "adb shell am start -n \"${component.filter { it.isLetterOrDigit() || it in "._/$" }}\"", 60)
        return ShellResult(
            ok = install.ok && run.ok,
            output = "=== install ===\n${install.output}\n\n=== launch ===\n${run.output}",
            exitCode = if (run.ok) 0 else run.exitCode,
            elapsedMs = install.elapsedMs + run.elapsedMs
        )
    }

    /** هل الأداة موجودة على الجهاز/الخادم؟ (بلا صدفة مفتوحة) */
    fun available(context: Context, name: String): Boolean {
        val safe = name.trim().filter { it.isLetterOrDigit() || it in "._-" }
        if (safe.isBlank()) return false
        return try {
            val process = ProcessBuilder("sh", "-c", "command -v " + safe)
                .redirectErrorStream(true)
                .start()
            val done = process.waitFor(8, TimeUnit.SECONDS)
            done && process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun installedPackages(context: Context): Map<String, Boolean> =
        PACKAGES.keys.associateWith { available(context, it) }

    /** تثبيت حزمة من قائمة بيضاء عبر مدير الحزم المتاح (pkg/apt-get). */
    fun install(context: Context, packageName: String, timeoutSec: Int = 600): ShellResult {
        val key = packageName.trim().lowercase()
        if (key.startsWith("pip:") || key.startsWith("npm:")) {
            val kind = key.substringBefore(':')
            val name = key.substringAfter(':')
            val allowed = if (kind == "pip") PIP_TOOLS else NPM_TOOLS
            if (name !in allowed) {
                return ShellResult(false, "⛔ أداة غير مسموحة: $name. المسموح ($kind): " + allowed.sorted().joinToString("، "), 126, 0)
            }
            val command = if (kind == "pip") "pip install --user $name" else "npm install -g $name"
            val result = exec(context, command, timeoutSec)
            return result.copy(output = "=== $kind ===\n" + result.output.takeLast(12_000))
        }
        val candidates = PACKAGES[key]
            ?: return ShellResult(
                false,
                "⛔ حزمة غير مسموحة: $packageName. المسموح: " + PACKAGES.keys.joinToString("، "),
                126,
                0
            )
        if (available(context, key)) return ShellResult(true, "✅ $key مثبتة بالفعل.", 0, 0)
        val manager = when {
            available(context, "pkg") -> "pkg"
            available(context, "apt-get") -> "apt-get"
            available(context, "apt") -> "apt"
            else -> ""
        }
        if (manager.isBlank()) {
            return ShellResult(
                false,
                "❌ لا يوجد مدير حزم (pkg/apt). ثبّت «$key» يدوياً على الجهاز/الخادم: " + candidates.joinToString("/"),
                127,
                0
            )
        }
        val command = when (manager) {
            "pkg" -> "pkg install -y " + candidates.first()
            else -> manager + " install -y " + candidates.first()
        }
        val result = exec(context, command, timeoutSec)
        return result.copy(output = "=== $manager ===\n" + result.output.takeLast(12_000))
    }

    fun logcat(context: Context, filter: String = "", lines: Int = 300): ShellResult {
        val safeFilter = filter.trim().filter { it.isLetterOrDigit() || it in " _-.:*" }
        val command = buildString {
            append("adb logcat -d -t ").append(lines.coerceIn(20, 2000))
            if (safeFilter.isNotBlank()) append(" -s ").append(safeFilter)
        }
        return exec(context, command, 60)
    }
}
