package com.lion.ai

import android.content.Context
import android.system.Os
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

data class AlpineRelease(val file: String, val sha256: String, val branch: String) {
    val url: String get() = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/$file"
}

/** Android لا يضيف مجلد مكتبات التطبيق تلقائياً عند exec لمكتبة كبرنامج مستقل. */
internal fun prootProcessEnvironment(nativeLibraryDir: String): Map<String, String> =
    mapOf("LD_LIBRARY_PATH" to nativeLibraryDir)

/** لا نعيد المحاولة عند خطأ حزمة/صلاحية، بل فقط عند عطل شبكة مؤقت قابل للزوال. */
internal fun isTransientPackageNetworkError(output: String): Boolean {
    val text = output.lowercase()
    if ("no such package" in text || "permission denied" in text || "initialization of vm" in text) return false
    return listOf(
        "dns: transient error", "temporary failure in name resolution", "temporarily unavailable",
        "network is unreachable", "connection timed out", "operation timed out", "timed out",
        "connection reset by peer", "connection refused", "temporary error", "try again later",
        "eai_again", "unexpected eof", "tls handshake timeout"
    ).any { it in text }
}

/**
 * بيئة Linux حقيقية داخل التطبيق: Alpine (aarch64) تعمل عبر proot.
 *
 * - proot و loader يُضمَّنان كمكتبات native (libproot.so / libproot-loader.so) لأن Android 10+
 *   يمنع تنفيذ أي ملف من مجلدات بيانات التطبيق؛ التنفيذ مسموح فقط من nativeLibraryDir.
 * - نظام Alpine يُنزَّل عند التفعيل (مع التحقق من sha256) ويُفك داخل filesDir/linux/alpine.
 * - مساحة عمل التطبيق تُربط داخل البيئة على /workspace، فتعمل كل الأدوات على نفس ملفات المشروع.
 */
object LinuxEnv {
    var installing by mutableStateOf(false)
        private set
    var progress by mutableStateOf("")
        private set
    var lastError by mutableStateOf("")
        private set

    /** حزم الأساسيات: git/sh/bash وPython/pip جزء إلزامي من بيئة التطوير. */
    private val CORE = "bash git curl openssh-client ripgrep ca-certificates python3 py3-pip"
    private val DEV_ALIASES =
        "mkdir -p /usr/local/bin /workspace/tools/bin /workspace/tools/python && " +
            "ln -sf /usr/bin/python3 /usr/local/bin/python && " +
            "ln -sf /usr/bin/pip3 /usr/local/bin/pip && " +
            "git --version && bash --version && sh -c ':' && python --version && python3 -m pip --version"

    private val APK = mapOf(
        "git" to "git", "python" to "python3 py3-pip", "node" to "nodejs npm",
        "java" to "openjdk17-jdk", "gradle" to "openjdk17-jdk gradle", "curl" to "curl", "jq" to "jq",
        "icu" to "icu-data-full", "sqlite" to "sqlite sqlite-dev",
        "unzip" to "unzip", "zip" to "zip", "tar" to "tar", "ffmpeg" to "ffmpeg",
        "build-essential" to "build-base", "openssh" to "openssh-client", "rg" to "ripgrep",
        "make" to "make", "cmake" to "cmake", "clang" to "clang", "go" to "go", "cargo" to "cargo",
        "ruby" to "ruby", "sqlite3" to "sqlite", "shellcheck" to "shellcheck", "patch" to "patch",
        "tree" to "tree", "fd" to "fd", "wget" to "wget", "openssl" to "openssl",
        "bash" to "bash", "adb" to "android-tools", "ca" to "ca-certificates"
    )

    /** § = علامة الدولار (لتفادي استيفاء Kotlin داخل النص). */
    private val OPENCODE_INSTALL_SCRIPT = """
        set -e
        OC=/workspace/tools/opencode
        DL=/workspace/tools/downloads
        mkdir -p /workspace/tools/bin "§DL" "§OC"
        echo "== 1/5 الحزم المطلوبة =="
        apk add --no-cache libstdc++ libgcc curl jq tar ca-certificates
        echo "== 2/5 جلب بيانات الإصدار =="
        META=§(curl -fsSL --retry 3 --connect-timeout 20 https://registry.npmjs.org/opencode-linux-arm64-musl/latest)
        VER=§(echo "§META" | jq -r .version)
        URL=§(echo "§META" | jq -r .dist.tarball)
        SUM=§(echo "§META" | jq -r .dist.shasum)
        [ -n "§VER" ] && [ "§VER" != "null" ] || { echo "تعذر قراءة إصدار OpenCode من سجل npm"; exit 11; }
        echo "الإصدار: §VER"
        FILE="§DL/opencode-§VER.tgz"
        echo "== 3/5 التنزيل (قابل للاستئناف) =="
        curl -fL --retry 5 --retry-delay 3 --connect-timeout 20 -sS -C - -o "§FILE" "§URL" &
        PID=§!
        while kill -0 §PID 2>/dev/null; do
          echo "§(( §(stat -c %s "§FILE" 2>/dev/null || echo 0) / 1048576 )) MB"
          sleep 3
        done
        wait §PID || { echo "فشل التنزيل؛ أعد الضغط للاستئناف من حيث توقف"; exit 12; }
        echo "== 4/5 التحقق والفك =="
        echo "§SUM  §FILE" | sha1sum -c - || { rm -f "§FILE"; echo "فشل التحقق من سلامة الملف"; exit 13; }
        rm -rf "§OC"; mkdir -p "§OC"
        tar xzf "§FILE" -C "§OC"
        cp -a "§OC/package/." "§OC/" && rm -rf "§OC/package"
        chmod +x "§OC/bin/opencode"
        ln -sf "§OC/bin/opencode" /workspace/tools/bin/opencode
        rm -f "§FILE"
        echo "== 5/5 فحص التشغيل =="
        timeout 60 opencode --version || { echo "الملف نُزّل لكنه لا يعمل داخل proot (exit §?)"; ls -l "§OC/bin"; exit 14; }
    """.trimIndent()

    fun base(context: Context): File = File(context.filesDir, "linux").apply { mkdirs() }
    fun rootfs(context: Context): File = File(base(context), "alpine")
    private fun marker(context: Context): File = File(base(context), ".installed")
    private fun proot(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
    private fun loader(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
    private val runtimeLibraries = listOf("libtalloc.so", "libandroid-shmem.so")

    /** proot واعتمادياته يجب أن تكون مضمّنة كلها؛ وجود proot وحده لا يكفي. */
    fun runtimeAvailable(context: Context): Boolean =
        proot(context).exists() && loader(context).exists() && runtimeLibraries.all {
            File(context.applicationInfo.nativeLibraryDir, it).isFile
        }

    /** اختبار تشغيل حقيقي يعرض خطأ dynamic linker بدل رسالة فشل عامة. */
    fun diagnoseRuntime(context: Context): ShellResult {
        if (!runtimeAvailable(context)) {
            val missing = (listOf(proot(context), loader(context)) + runtimeLibraries.map {
                File(context.applicationInfo.nativeLibraryDir, it)
            }).filterNot { it.isFile }.joinToString { it.name }
            return ShellResult(false, "مكوّنات Linux الناقصة من APK: $missing. ثبّت البناء المحدّث.", 1, 0)
        }
        return try {
            val builder = ProcessBuilder(proot(context).absolutePath, "--version")
                .redirectErrorStream(true)
            builder.environment().putAll(prootProcessEnvironment(context.applicationInfo.nativeLibraryDir))
            val process = builder.start()
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val code = if (finished) process.exitValue() else 124
            ShellResult(finished && code == 0, output.ifBlank { "proot --version: exit $code" }, code, 0, !finished)
        } catch (e: Exception) {
            ShellResult(false, "تعذر تشغيل proot: ${e.message ?: e.javaClass.simpleName}", 1, 0)
        }
    }

    /** فحص المشغل وDNS ومستودعات apk والمساحة دون تثبيت أي أداة. */
    fun diagnoseEnvironment(context: Context): ShellResult {
        val runtime = diagnoseRuntime(context)
        if (!runtime.ok) return runtime
        if (!ready(context)) return ShellResult(false, "مشغّل proot يعمل، لكن Alpine غير مفعّلة بعد.", 1, 0)
        val script = """
            status=0
            echo '=== Alpine ==='; cat /etc/alpine-release
            echo '=== Workspace ==='; df -h /workspace
            echo '=== Repositories ==='; cat /etc/apk/repositories
            echo '=== DNS ==='; nslookup dl-cdn.alpinelinux.org || status=1
            echo '=== APK index ==='; apk update || status=1
            echo '=== Tool versions ==='
            node --version 2>/dev/null || echo 'node: غير مثبت'
            python3 --version 2>/dev/null || echo 'python3: غير مثبت'
            /usr/lib/jvm/java-17-openjdk/bin/java -version 2>&1 || echo 'Java 17: غير مثبت أو لا يعمل'
            JAVA_HOME=/usr/lib/jvm/java-17-openjdk gradle --version 2>&1 || echo 'Gradle: غير مثبت أو لا يعمل'
            exit ${'$'}status
        """.trimIndent()
        return run(context, script, 120)
    }

    /** هل البيئة مثبّتة وجاهزة للتشغيل؟ */
    fun ready(context: Context): Boolean =
        runtimeAvailable(context) && marker(context).exists() && File(rootfs(context), "bin/busybox").exists()

    // ───────────── تشغيل الأوامر داخل البيئة ─────────────

    private fun guestCwd(context: Context, hostCwd: File?): String {
        if (hostCwd == null) return "/workspace"
        val ws = FileTools.root(context).canonicalFile
        val rel = runCatching { hostCwd.canonicalFile.relativeToOrNull(ws)?.path }.getOrNull()
        return if (rel.isNullOrEmpty()) "/workspace" else "/workspace/$rel"
    }

    private fun command(context: Context, script: String, hostCwd: File?): List<String> {
        val workspace = FileTools.root(context)
        val shell = if (File(rootfs(context), "bin/bash").exists()) "/bin/bash" else "/bin/sh"
        return listOf(
            proot(context).absolutePath,
            "--kill-on-exit", "--link2symlink", "-0",
            "-r", rootfs(context).absolutePath,
            "-w", guestCwd(context, hostCwd),
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", workspace.absolutePath + ":/workspace",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp",
            "SHELL=$shell",
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk",
            "NODE_PATH=/workspace/tools/node/lib/node_modules",
            // أدوات المستخدم وpip/npm داخل مساحة العمل تبقى في PATH بعد كل تشغيل/تحديث.
            "PATH=/usr/lib/jvm/java-17-openjdk/bin:/workspace/tools/bin:/workspace/tools/node/bin:/workspace/tools/python/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PYTHONUSERBASE=/workspace/tools/python",
            "PIP_DISABLE_PIP_VERSION_CHECK=1",
            "NPM_CONFIG_PREFIX=/workspace/tools/node",
            shell, "-c", script
        )
    }

    /** يبدأ عملية داخل البيئة (stderr مدموج في stdout). */
    fun start(context: Context, script: String, hostCwd: File? = null): Process {
        val tmp = File(base(context), "tmp").apply { mkdirs() }
        val builder = ProcessBuilder(command(context, script, hostCwd)).redirectErrorStream(true)
        builder.environment().putAll(prootProcessEnvironment(context.applicationInfo.nativeLibraryDir))
        builder.environment()["PROOT_LOADER"] = loader(context).absolutePath
        builder.environment()["PROOT_TMP_DIR"] = tmp.absolutePath
        builder.environment()["PROOT_NO_SECCOMP"] = "1"
        return builder.start()
    }

    fun run(
        context: Context,
        script: String,
        timeoutSec: Int = 120,
        hostCwd: File? = null,
        onLine: ((String) -> Unit)? = null
    ): ShellResult {
        val started = System.currentTimeMillis()
        return try {
            val process = start(context, script, hostCwd)
            val out = StringBuilder()
            val reader = thread(start = true, name = "lion-linux-reader") {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(out) {
                            out.append(line).append('\n')
                            if (out.length > 40_000) out.delete(0, out.length - 30_000)
                        }
                        onLine?.invoke(line)
                    }
                }
            }
            val finished = process.waitFor(timeoutSec.coerceIn(5, 1800).toLong(), TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(2000)
            val code = runCatching { process.exitValue() }.getOrDefault(if (finished) 1 else 124)
            val text = synchronized(out) { out.toString() }
            ShellResult(
                ok = finished && code == 0,
                output = if (text.isBlank()) "(لا مخرجات)" else text,
                exitCode = code,
                elapsedMs = System.currentTimeMillis() - started,
                timedOut = !finished
            )
        } catch (e: Exception) {
            ShellResult(false, "❌ تعذر التنفيذ: ${e.message ?: e.javaClass.simpleName}", 1, 0)
        }
    }

    /** apk/npm آمنان لإعادة المحاولة عند DNS/انقطاع مؤقت؛ ثلاث محاولات كحد أقصى. */
    private fun runPackageCommand(
        context: Context,
        script: String,
        timeoutSec: Int,
        onLine: ((String) -> Unit)? = null
    ): ShellResult {
        val attempts = mutableListOf<String>()
        repeat(3) { index ->
            val result = run(context, script, timeoutSec, onLine = onLine)
            attempts += "--- المحاولة ${index + 1}/3 • exit ${result.exitCode} ---\n${result.output}"
            if (result.ok) return result.copy(output = attempts.joinToString("\n"))
            if (!isTransientPackageNetworkError(result.output) || index == 2) {
                return result.copy(output = attempts.joinToString("\n"))
            }
            val notice = "⚠️ عطل شبكة مؤقت؛ إعادة المحاولة ${index + 2}/3 بعد ${index + 1} ثوانٍ…"
            onLine?.invoke(notice)
            attempts += notice
            Thread.sleep((index + 1) * 1500L)
        }
        error("unreachable")
    }

    // ───────────── التفعيل: تنزيل + فك + أساسيات ─────────────

    fun parseRelease(yaml: String): AlpineRelease? {
        for (block in yaml.split(Regex("(?m)^-"))) {
            if (!block.contains("flavor: alpine-minirootfs")) continue
            val file = Regex("(?m)^\\s*(?:file|iso):\\s*(\\S+)").find(block)?.groupValues?.get(1) ?: continue
            val sha = Regex("(?m)^\\s*sha256:\\s*([0-9a-fA-F]{64})").find(block)?.groupValues?.get(1) ?: continue
            val m = Regex("alpine-minirootfs-(\\d+)\\.(\\d+)\\.\\d+-aarch64\\.tar\\.gz").matchEntire(file) ?: continue
            return AlpineRelease(file, sha.lowercase(), "v${m.groupValues[1]}.${m.groupValues[2]}")
        }
        return null
    }

    fun startInstall(context: Context) {
        if (installing) return
        val app = context.applicationContext
        installing = true
        lastError = ""
        progress = "بدء التفعيل…"
        thread(name = "lion-linux-install") {
            try {
                install(app)
                progress = "✅ البيئة جاهزة"
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                progress = "❌ $lastError"
            } finally {
                installing = false
            }
        }
    }

    @Synchronized
    private fun install(context: Context) {
        val diagnostic = diagnoseRuntime(context)
        if (!diagnostic.ok) {
            throw IllegalStateException("proot لا يعمل:\n${diagnostic.output.takeLast(4000)}")
        }
        val root = rootfs(context)
        if (!(marker(context).exists() && File(root, "bin/busybox").exists())) {
            progress = "جلب معلومات أحدث إصدار Alpine…"
            val yaml = httpText("https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/latest-releases.yaml")
            val release = parseRelease(yaml) ?: throw IllegalStateException("تعذر قراءة إصدار Alpine من الخادم")
            val archive = File(base(context), "alpine.tar.gz")
            download(release.url, archive) { done, total ->
                progress = if (total > 0) "⬇️ تنزيل Alpine ${done * 100 / total}%" else "⬇️ تنزيل Alpine ${done / 1024} KB"
            }
            progress = "🔐 التحقق من سلامة الملف…"
            val actual = sha256(archive)
            if (actual != release.sha256) {
                archive.delete()
                throw IllegalStateException("فشل التحقق من sha256 لملف Alpine")
            }
            extract(archive, root)
            archive.delete()
            File(root, "etc/resolv.conf").apply { parentFile?.mkdirs(); delete() }
                .writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            File(root, "etc/hosts").apply { delete() }.writeText("127.0.0.1 localhost\n::1 localhost\n")
            File(root, "etc/apk").mkdirs()
            File(root, "etc/apk/repositories").apply { delete() }.writeText(
                "https://dl-cdn.alpinelinux.org/alpine/${release.branch}/main\n" +
                    "https://dl-cdn.alpinelinux.org/alpine/${release.branch}/community\n"
            )
            File(root, "tmp").mkdirs()
            File(root, "root").mkdirs()
            marker(context).writeText(release.file)
        }
        progress = "📦 تثبيت الحزم الأساسية فقط…"
        val core = runPackageCommand(context, "apk add --no-cache $CORE", 1200) { progress = "📦 " + it.take(100) }
        if (!core.ok) throw IllegalStateException("فشل تثبيت الأساسيات (exit ${core.exitCode}):\n" + core.output.takeLast(8000))
        val aliases = run(context, DEV_ALIASES, 120)
        if (!aliases.ok) throw IllegalStateException("فشل تجهيز أوامر python/pip:\n" + aliases.output.takeLast(4000))
        // ADB وNode وOpenCode وبقية الأدوات تُثبّت فقط عند اختيارها، لا ضمنياً هنا.
    }

    fun remove(context: Context) {
        rootfs(context).deleteRecursively()
        marker(context).delete()
        progress = ""
        lastError = ""
    }

    // ───────────── تثبيت الأدوات الناقصة داخل البيئة ─────────────

    @Synchronized
    fun installPackage(context: Context, key: String, timeoutSec: Int = 900): ShellResult {
        if (!ready(context)) return ShellResult(false, "فعّل بيئة Linux أولاً.", 1, 0)
        val pkgs = APK[key] ?: return ShellResult(false, "⛔ حزمة غير مدعومة داخل البيئة: $key", 126, 0)
        val result = runPackageCommand(context, "apk add --no-cache $pkgs", timeoutSec)
        return result.copy(output = "=== $key فقط: apk add $pkgs ===\n" + result.output.takeLast(16_000))
    }

    /** ترقية الحزم المثبتة فقط. زر مستقل وصريح؛ لا يستدعيه أي زر أداة. */
    @Synchronized
    fun updateEnvironment(context: Context): ShellResult {
        if (!ready(context)) return ShellResult(false, "فعّل بيئة Linux أولاً.", 1, 0)
        val result = runPackageCommand(context, "apk update && apk upgrade --no-cache && $DEV_ALIASES", 1800)
        return result.copy(output = "=== ترقية الحزم المثبتة فقط ===\n" + result.output.takeLast(16_000))
    }

    /** حزم مساعدة مستقلة: كل زر ينفّذ حزمة واحدة فقط ويعرض سجلها. */
    @Synchronized
    fun installSupportPackage(context: Context, id: String): ShellResult {
        if (!ready(context)) return ShellResult(false, "فعّل بيئة Linux أولاً.", 1, 0)
        val command = when (id) {
            "icu" -> "apk add --no-cache icu-data-full"
            "sqlite" -> "apk add --no-cache sqlite sqlite-dev && sqlite3 --version"
            "axios", "lodash", "cheerio", "sqlite3" ->
                "mkdir -p /workspace/tools/node && npm install --prefix /workspace/tools/node $id --no-audit --no-fund"
            else -> return ShellResult(false, "حزمة غير مدعومة: $id", 126, 0)
        }
        val result = runPackageCommand(context, command, 1200)
        return result.copy(output = "=== $id فقط • exit ${result.exitCode} ===\n" + result.output.takeLast(16_000))
    }

    /** عملية واحدة في كل مرة على مستوى التطبيق كله، وتستهدف الأداة المحددة فقط. */
    @Synchronized
    fun installTool(context: Context, toolId: String, timeoutSec: Int = 1200): ShellResult {
        if (!ready(context)) return ShellResult(false, "فعّل بيئة Linux أولاً.", 1, 0)
        val result = if (toolId == "opencode") {
            // تثبيت مباشر للملف التنفيذي الجاهز (musl/arm64) بدل npm: لا Node ولا postinstall،
            // مع تنزيل قابل للاستئناف وتحقق sha1 من سجل npm وسجل تقدّم ظاهر في الواجهة.
            runPackageCommand(context, OPENCODE_INSTALL_SCRIPT.replace('§', '$'), 2400) { progress = "⬇️ " + it.take(100) }
        } else {
            val key = when (toolId) {
                "ssh" -> "openssh"
                "python" -> "python"
                else -> toolId
            }
            val pkgs = APK[key] ?: return ShellResult(false, "⛔ أداة غير مدعومة: $toolId", 126, 0)
            val verification = when (key) {
                "python" -> " && $DEV_ALIASES"
                "java" -> " && /usr/lib/jvm/java-17-openjdk/bin/java -version"
                "gradle" -> " && JAVA_HOME=/usr/lib/jvm/java-17-openjdk gradle --version"
                "jq" -> " && jq --version"
                else -> ""
            }
            runPackageCommand(context, "apk add --no-cache $pkgs$verification", timeoutSec)
        }
        return result.copy(output = "=== ${if (result.ok) "نجحت" else "فشلت"} عملية $toolId فقط (exit ${result.exitCode}) ===\n" + result.output.takeLast(16_000))
    }

    // ───────────── مساعدات الشبكة والملفات ─────────────

    private fun httpText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode} من $url")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode} أثناء التنزيل")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    if (done - lastReport >= 256 * 1024) {
                        lastReport = done
                        onProgress(done, total)
                    }
                }
                onProgress(done, total)
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extract(archive: File, dest: File) {
        dest.deleteRecursively()
        dest.mkdirs()
        val destPath = dest.canonicalPath
        var count = 0
        TarArchiveInputStream(GZIPInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val rel = entry.name.removePrefix("./").trimStart('/')
                if (rel.isEmpty() || rel == ".") continue
                if (rel.split('/').any { it == ".." }) continue
                val target = File(dest, rel)
                val parent = target.parentFile ?: continue
                parent.mkdirs()
                if (!parent.canonicalPath.startsWith(destPath)) continue
                val mode = entry.mode and 0x1FF
                when {
                    entry.isDirectory -> {
                        target.mkdirs()
                        runCatching { Os.chmod(target.absolutePath, mode or 0x1C0) }
                    }
                    entry.isSymbolicLink -> {
                        target.delete()
                        runCatching { Os.symlink(entry.linkName, target.absolutePath) }
                    }
                    entry.isLink -> {
                        val source = File(dest, entry.linkName.removePrefix("./").trimStart('/'))
                        target.delete()
                        if (source.isFile) {
                            source.copyTo(target, overwrite = true)
                            runCatching { Os.chmod(target.absolutePath, mode or 0x180) }
                        }
                    }
                    entry.isFile -> {
                        target.delete()
                        FileOutputStream(target).use { out -> tar.copyTo(out) }
                        runCatching { Os.chmod(target.absolutePath, mode or 0x180) }
                    }
                    else -> Unit
                }
                count++
                if (count % 150 == 0) progress = "📂 فك الملفات ($count)…"
            }
        }
    }
}
