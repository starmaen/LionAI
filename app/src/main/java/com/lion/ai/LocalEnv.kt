package com.lion.ai

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class EnvCheck(val name: String, val ok: Boolean, val detail: String)

data class WorkspaceReport(val root: String, val checks: List<EnvCheck>) {
    val ok: Boolean get() = checks.all { it.ok }
}

/** أداة تطوير مطلوبة في بيئة العمل. */
data class DevTool(
    val id: String,
    val label: String,
    /** أمر فحص ثابت (لا يتأثر بمدخلات المستخدم أو النموذج). */
    val probe: String,
    /** مفتاح الحزمة في ShellTools.install (null = لا تثبيت تلقائي). */
    val pkgKey: String?,
    /** الأمر اليدوي الذي ينسخه المستخدم إن لم يتوفر مدير حزم. */
    val manualCommand: String
)

data class DevToolStatus(val tool: DevTool, val ok: Boolean, val version: String)

/**
 * بيئة العمل المحلية:
 * 1) مساحة العمل (ملفات/ZIP/مجلدات/مشاريع) تعمل بـ Kotlin صرف، فلا تعتمد على أي أداة خارجية.
 * 2) أدوات التطوير تُفحص وتُثبَّت إن وُجد مدير حزم (pkg/apt)، وإلا تُعرض للمستخدم بأمر جاهز للنسخ.
 */
object LocalEnv {
    private val DIRS = listOf("projects", "downloads", "tools/bin")

    val TOOLS = listOf(
        DevTool("opencode", "OpenCode", "opencode --version", null, "npm install -g opencode-ai"),
        DevTool("git", "Git", "git --version", "git", "apk add git"),
        DevTool("bash", "Bash", "bash --version", "bash", "apk add bash"),
        DevTool("curl", "curl", "curl --version", "curl", "apk add curl"),
        DevTool("ssh", "SSH", "ssh -V", "openssh", "apk add openssh-client"),
        DevTool("rg", "ripgrep", "rg --version", "rg", "apk add ripgrep"),
        DevTool("adb", "ADB", "adb version", "adb", "apk add android-tools"),
        DevTool("python", "Python + pip", "python --version && python3 -m pip --version", "python", "apk add python3 py3-pip"),
        DevTool("node", "Node.js + npm", "node --version && npm --version", "node", "apk add nodejs npm"),
        DevTool("java", "Java 17", "/usr/lib/jvm/java-17-openjdk/bin/java -version", "java", "apk add openjdk17-jdk"),
        DevTool("gradle", "Gradle (JDK 17)", "JAVA_HOME=/usr/lib/jvm/java-17-openjdk gradle --version", "gradle", "apk add openjdk17-jdk gradle"),
        DevTool("jq", "jq", "jq --version", "jq", "apk add jq"),
        DevTool("unzip", "ZIP tools", "unzip -v", "unzip", "apk add unzip zip")
    )

    /** ينشئ مساحة العمل والمجلدات الأساسية ويختبرها فعلياً (كتابة/قراءة/ضغط/فك/حذف). */
    fun ensureWorkspace(context: Context): WorkspaceReport {
        val checks = mutableListOf<EnvCheck>()
        val root = FileTools.root(context)
        checks += EnvCheck("مساحة العمل", root.isDirectory && root.canWrite(), root.absolutePath)
        DIRS.forEach { rel ->
            val dir = File(root, rel)
            val ok = dir.isDirectory || dir.mkdirs()
            checks += EnvCheck("مجلد $rel", ok, if (ok) "جاهز" else "تعذر الإنشاء")
        }
        val free = root.usableSpace / (1024 * 1024)
        checks += EnvCheck("المساحة الحرة", free >= 200, "$free MB" + if (free < 200) " (أقل من 200MB)" else "")

        val test = File(root, ".lion-selftest")
        try {
            test.deleteRecursively()
            test.mkdirs()
            val src = File(test, "hello.txt")
            src.writeText("lion-ok")
            checks += EnvCheck("كتابة وقراءة ملف", src.readText() == "lion-ok", "hello.txt")
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("a/hello.txt"))
                zip.write(src.readBytes())
                zip.closeEntry()
            }
            checks += EnvCheck("ضغط ZIP", bytes.size() > 0, "${bytes.size()} bytes")
            var restored = ""
            ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == "a/hello.txt") restored = zip.readBytes().decodeToString()
                    entry = zip.nextEntry
                }
            }
            checks += EnvCheck("فك ZIP", restored == "lion-ok", "a/hello.txt")
            val project = File(test, "demo/src/main").apply { mkdirs() }
            File(project, "Main.kt").writeText("fun main() = println(\"ok\")")
            val listed = File(test, "demo").walkTopDown().count { it.isFile }
            checks += EnvCheck("إنشاء مشروع ومجلدات", listed == 1, "demo/src/main/Main.kt")
        } catch (e: Exception) {
            checks += EnvCheck("اختبار الملفات", false, e.message ?: e.javaClass.simpleName)
        } finally {
            test.deleteRecursively()
        }
        return WorkspaceReport(root.absolutePath, checks)
    }

    private fun probe(context: Context, command: String): Pair<Boolean, String> = try {
        val root = FileTools.root(context)
        val bin = File(root, "tools/bin").absolutePath
        val pythonBin = File(root, "tools/python/bin").absolutePath
        val builder = ProcessBuilder("sh", "-c", command).redirectErrorStream(true)
        builder.environment()["PATH"] = bin + File.pathSeparator + pythonBin + File.pathSeparator +
            (System.getenv("PATH") ?: "/system/bin")
        builder.environment()["HOME"] = root.absolutePath
        builder.environment()["PYTHONUSERBASE"] = File(root, "tools/python").absolutePath
        val process = builder.start()
        val done = process.waitFor(8, TimeUnit.SECONDS)
        if (!done) process.destroyForcibly()
        val text = process.inputStream.bufferedReader().readText().lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }.take(1).joinToString("").take(120)
        (done && process.exitValue() == 0) to text
    } catch (e: Exception) {
        false to (e.message ?: "")
    }

    private fun caStatus(context: Context): DevToolStatus {
        val tool = DevTool("ca", "شهادات CA", "", null, "apk add ca-certificates")
        if (LinuxEnv.ready(context)) {
            val ok = LinuxEnv.run(context, "test -s /etc/ssl/certs/ca-certificates.crt", 20).ok
            return DevToolStatus(tool, ok, if (ok) "installed" else "غير موجودة")
        }
        val dirs = listOf("/system/etc/security/cacerts", "/etc/ssl/certs")
        val found = dirs.any { File(it).isDirectory && (File(it).list()?.isNotEmpty() == true) }
        return DevToolStatus(tool, found, if (found) "installed (النظام)" else "غير موجودة")
    }

    fun inventory(context: Context): List<DevToolStatus> {
        val inEnv = LinuxEnv.ready(context)
        val statuses = TOOLS.map { tool ->
            if (inEnv) {
                val res = LinuxEnv.run(context, tool.probe, 25)
                val line = res.output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(120)
                DevToolStatus(tool, res.ok, if (res.ok) line else "غير مثبتة")
            } else {
                val (ok, text) = probe(context, tool.probe)
                DevToolStatus(tool, ok, if (ok) text else "غير مثبتة")
            }
        }.toMutableList()
        statuses.add(6, caStatus(context))
        return statuses
    }

    /** تثبيت أداة ناقصة داخل بيئة Linux (apk). يتطلب تفعيل البيئة أولاً. */
    fun install(context: Context, tool: DevTool): ShellResult {
        if (!LinuxEnv.ready(context)) {
            return ShellResult(false, "فعّل بيئة Linux أولاً (زر التفعيل أعلى الشاشة) ثم ثبّت الأداة.", 1, 0)
        }
        return LinuxEnv.installTool(context, tool.id)
    }
}
