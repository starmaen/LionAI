package com.lion.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * ملف GGUF معروف يستطيع المحرّك المدمج تنزيله وتشغيله.
 * `bytes` حجم الملف الفعلي على الخادم (يُستخدم للتحقق من اكتمال التنزيل).
 */
data class LionGguf(
    val id: String,
    val label: String,
    val url: String,
    val file: String,
    val bytes: Long,
    val note: String
)

/**
 * كتالوج نماذج Lion-1 المدمجة — بيانات صرفة بلا سياق أندرويد، لذا تُختبر على JVM.
 *
 * النموذج الافتراضي هو قاعدة Lion-1 (Qwen2.5-Instruct) بترميز Q4_K_M: تعمل على هواتف
 * 4–6GB. عند تدريب نسخة Lion-1 الخاصة بك (model/train_lora.py → merge_export.py → GGUF)
 * ضع رابطها أو مسارها في «نموذج مخصص» فيصبح المحرّك يشغّل نموذجك أنت.
 */
object LionCatalog {
    const val DEFAULT_ID = "lion-1-mini"

    val MODELS: List<LionGguf> = listOf(
        LionGguf(
            id = "lion-1-mini",
            label = "Lion-1 Mini — Qwen2.5 1.5B · Q4_K_M",
            url = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/" +
                "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            file = "lion-1-mini-q4_k_m.gguf",
            bytes = 986_048_768L,
            note = "≈940MB — الأنسب للهواتف 4–6GB · سرعة جيدة"
        ),
        LionGguf(
            id = "lion-1-nano",
            label = "Lion-1 Nano — Qwen2.5 0.5B · Q4_K_M",
            url = "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/" +
                "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
            file = "lion-1-nano-q4_k_m.gguf",
            bytes = 397_808_192L,
            note = "≈380MB — للأجهزة 3–4GB · أسرع بكثير، أضعف في التخطيط"
        ),
        LionGguf(
            id = "lion-1-base",
            label = "Lion-1 Base — Qwen2.5 3B · Q4_K_M",
            url = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/" +
                "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            file = "lion-1-base-q4_k_m.gguf",
            bytes = 1_929_903_264L,
            note = "≈1.9GB — أذكى في البرمجة · يحتاج 8GB+ ومساحة أكبر"
        )
    )

    fun find(id: String?): LionGguf =
        MODELS.firstOrNull { it.id == id?.trim() } ?: MODELS.first()
}

/**
 * المحرّك المدمج لـ Lion-1: `llama-server` (llama.cpp) يعمل **داخل** بيئة Alpine/proot التي
 * يملكها التطبيق، على 127.0.0.1:44045، مع نموذج GGUF منزَّل في مساحة العمل.
 *
 * لماذا هذا هو «المزود الحقيقي»: لم يبقَ lion-1 واجهةً تُمرّر النص إلى بوابة أو مزوّد خلفي.
 * بعد التثبيت يعمل الاستدلال على الجهاز نفسه بلا إنترنت وبلا أي مفتاح:
 *   • llama-server يقدّم /v1/chat/completions متوافقاً مع OpenAI (بما فيها tools مع --jinja)،
 *     فيستفيد lion-1 من **أدوات التطبيق** الأصلية (function calling) لا من بروتوكول نصي فقط.
 *   • كل شيء داخل Alpine الموجود: لا مكتبات native جديدة ولا NDK.
 *   • البوابة/الخادم الخارجي يبقيان مساراً أخيراً فقط عند فشل المحرّك (خيار المستخدم).
 *
 * الحالات: بيئة Linux ناقصة ← حزمة llama-server ناقصة ← النموذج ناقص ← جاهز ← يعمل.
 */
object LionEngine {
    const val PORT = 44045
    const val BASE_URL = "http://127.0.0.1:$PORT"
    const val CHAT_URL = "$BASE_URL/v1/chat/completions"
    const val MODELS_URL = "$BASE_URL/v1/models"
    const val HEALTH_URL = "$BASE_URL/health"

    const val KEY_MODEL = "lion_engine_model"
    const val KEY_CUSTOM_URL = "lion_engine_custom_url"
    const val KEY_CUSTOM_NAME = "lion_engine_custom_name"
    const val KEY_CTX = "lion_engine_ctx"
    const val KEY_THREADS = "lion_engine_threads"
    const val KEY_AUTOSTART = "lion_engine_autostart"

    /** يُضبط مرة واحدة عند أول تشغيل: التركيبة المناسبة لجهاز المستخدم. */
    const val KEY_PROFILE_SET = "lion_engine_profile_set"

    const val DEFAULT_CTX = 8192
    val CTX_CHOICES = listOf(4096, 8192, 16384)

    /** أول حالة ناقصة تمنع التشغيل — تُعرض في الواجهة برسالة واحدة صريحة. */
    enum class Phase { LINUX_MISSING, SERVER_MISSING, MODEL_MISSING, READY, STARTING, RUNNING, ERROR }

    var phase by mutableStateOf(Phase.LINUX_MISSING)
        private set
    var progress by mutableStateOf("")
        private set
    var lastError by mutableStateOf("")
        private set
    var logTail by mutableStateOf("")
        private set

    @Volatile private var process: Process? = null
    @Volatile private var starting = false
    @Volatile private var lastProbe: Pair<Long, Boolean> = 0L to false
    private val logLines = ArrayDeque<String>()

    // ─────────────────────────── الإعدادات ───────────────────────────

    private fun prefsOrNull(): SharedPreferences? =
        runCatching { ContextHolder.peek()?.let { SecurePrefs.get(it) } }.getOrNull()

    fun model(prefs: SharedPreferences): LionGguf = LionCatalog.find(prefs.getString(KEY_MODEL, LionCatalog.DEFAULT_ID))

    fun customUrl(prefs: SharedPreferences): String = prefs.getString(KEY_CUSTOM_URL, "").orEmpty().trim()

    fun ctx(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_CTX, DEFAULT_CTX).let { if (it in 1024..32768) it else DEFAULT_CTX }

    fun threads(prefs: SharedPreferences): Int {
        val saved = prefs.getInt(KEY_THREADS, 0)
        if (saved in 1..16) return saved
        return (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
    }

    fun autoStart(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_AUTOSTART, true)

    fun setModel(prefs: SharedPreferences, id: String) {
        prefs.edit().putString(KEY_MODEL, id).apply()
        refreshPhase()
    }

    fun setCustomModel(prefs: SharedPreferences, url: String, name: String) {
        prefs.edit()
            .putString(KEY_CUSTOM_URL, url.trim())
            .putString(KEY_CUSTOM_NAME, name.trim())
            .apply()
        refreshPhase()
    }

    fun setCtx(prefs: SharedPreferences, value: Int) {
        prefs.edit().putInt(KEY_CTX, value).apply()
    }

    fun setAutoStart(prefs: SharedPreferences, value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()
    }

    // ─────────────────────────── الملفات والمسارات ───────────────────────────

    fun modelsDir(context: Context): File = File(FileTools.root(context), "models/lion").apply { mkdirs() }

    /** ملف النموذج الفعلي: النموذج المخصص (إن وُجد) وإلا النموذج المختار من الكتالوج. */
    fun modelFile(context: Context, prefs: SharedPreferences): File {
        val custom = prefs.getString(KEY_CUSTOM_NAME, "").orEmpty().trim()
        if (customUrl(prefs).isNotBlank() && custom.isNotBlank()) {
            val local = File(custom)
            if (local.isAbsolute && local.exists()) return local
            return File(modelsDir(context), sanitize(custom))
        }
        return File(modelsDir(context), model(prefs).file)
    }

    /** المسار داخل البيئة (مساحة العمل مربوطة على /workspace). */
    fun guestPath(context: Context, file: File): String {
        val root = runCatching { FileTools.root(context).canonicalFile }.getOrNull()
        val rel = runCatching { file.canonicalFile.relativeToOrNull(root!!)?.path }.getOrNull()
        return if (!rel.isNullOrBlank()) "/workspace/$rel" else file.absolutePath
    }

    internal fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifBlank { "lion-custom.gguf" }

    fun hasModel(context: Context, prefs: SharedPreferences): Boolean {
        val f = modelFile(context, prefs)
        if (!f.exists() || f.length() < 1024L * 1024L) return false
        // نموذج الكتالوج: نتأكد أن التنزيل اكتمل (الملف الجزئي له امتداد .part).
        return !f.name.endsWith(".part")
    }

    fun modelSizeText(context: Context, prefs: SharedPreferences): String {
        val f = modelFile(context, prefs)
        if (!f.exists()) return "غير منزَّل"
        val mb = f.length() / (1024 * 1024)
        return if (mb > 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"
    }

    // ─────────────────────────── ملفّ الجهاز والتوصية ───────────────────────────

    /** قياسات الجهاز الفعلية: الرام الكلية/المتاحة والمساحة الحرة. */
    data class DeviceProfile(
        val totalRamMb: Long,
        val availRamMb: Long,
        val freeStorageMb: Long,
        val lowRamDevice: Boolean
    ) {
        val ramText: String get() = "%.1f GB".format(totalRamMb / 1024.0)
        val availText: String get() = "%.1f GB".format(availRamMb / 1024.0)
        val storageText: String get() = "%.1f GB".format(freeStorageMb / 1024.0)
    }

    fun deviceProfile(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        var totalMb = 0L
        var availMb = 0L
        var lowRam = false
        if (am != null) {
            am.getMemoryInfo(info)
            totalMb = info.totalMem / (1024 * 1024)
            availMb = info.availMem / (1024 * 1024)
            lowRam = am.isLowRamDevice
        }
        val freeMb = try {
            val stat = android.os.StatFs(context.filesDir.absolutePath)
            stat.availableBytes / (1024 * 1024)
        } catch (e: Exception) {
            -1L
        }
        return DeviceProfile(totalMb, availMb, freeMb, lowRam)
    }

    /** تركيبة موصى بها لنموذج وسياق حسب الرام الفعلية (دالة صرفة قابلة للاختبار). */
    data class Pick(val modelId: String, val ctx: Int, val why: String)

    fun recommendFor(totalRamMb: Long, lowRamDevice: Boolean = false): Pick = when {
        // أقل من 3.5GB: النموذج الصغير فقط
        totalRamMb in 1L..3_500L -> Pick("lion-1-nano", 4096, "رام أقل من 3.5GB — Nano أخف")
        // 3.5–5.5GB (هواتف 4GB): Mini بسياق متوسط
        totalRamMb in 3_501L..5_500L -> Pick("lion-1-mini", 4096, "رام 4GB — Mini بسياق 4096")
        // 5.5–9.5GB (6–8GB): Mini بسياق أوسع
        totalRamMb in 5_501L..9_500L -> Pick("lion-1-mini", 8192, "رام 6–8GB — Mini بسياق 8192")
        // 12GB+ : النموذج 3B
        totalRamMb > 9_500L -> Pick("lion-1-base", 8192, "رام 12GB+ — 3B بسياق 8192")
        else -> Pick(LionCatalog.DEFAULT_ID, if (lowRamDevice) 4096 else DEFAULT_CTX, "قياس غير متاح — الافتراضي الآمن")
    }

    /** يُطبَّق مرة واحدة فقط حتى لا يلغى اختيار المستخدم اليدوي في كل فتح. */
    fun applyRecommendedOnce(context: Context, prefs: SharedPreferences): Pick? {
        if (prefs.getBoolean(KEY_PROFILE_SET, false)) return null
        val pick = recommendFor(deviceProfile(context).totalRamMb)
        prefs.edit()
            .putString(KEY_MODEL, pick.modelId)
            .putInt(KEY_CTX, pick.ctx)
            .putBoolean(KEY_PROFILE_SET, true)
            .apply()
        return pick
    }

    fun applyRecommended(prefs: SharedPreferences, pick: Pick) {
        prefs.edit()
            .putString(KEY_MODEL, pick.modelId)
            .putInt(KEY_CTX, pick.ctx)
            .putBoolean(KEY_PROFILE_SET, true)
            .apply()
        refreshPhase()
    }

    /** حاجة الذاكرة التقديرية: أوزان النموذج + ذاكرة السياق + Alpine + هامش. */
    internal fun neededRamMb(modelBytes: Long, ctx: Int): Long =
        modelBytes / (1024 * 1024) + (ctx / 1024L) * 40L + 350L

    /**
     * فحص قبل التحميل/التشغيل. يعيد null إذا كان كل شيء كافياً، أو رسالة عربية صريحة
     * تذكر الأرقام الفعلية بدل أن يفشل التشغيل لاحقاً بخطأ غامض.
     */
    fun preflight(context: Context, prefs: SharedPreferences): String? {
        val profile = deviceProfile(context)
        val model = model(prefs)
        val needStorage = if (hasModel(context, prefs)) 200L else model.bytes / (1024 * 1024) + 450L
        if (profile.freeStorageMb in 0..needStorage) {
            return "المساحة الحرة ${profile.storageText} لا تكفي (المطلوب ≈ %.1f GB). حرّر مساحة ثم أعد المحاولة."
                .format(needStorage / 1024.0)
        }
        val need = neededRamMb(model.bytes, ctx(prefs))
        if (profile.availRamMb in 1..(need - 150L)) {
            return "الرام المتاحة الآن ${profile.availText} أقل من المطلوب ≈ %.1f GB لهذا النموذج. " +
                "أغلق تطبيقات الخلفية، أو اختر Lion-1 Nano، أو أنقص السياق.".format(need / 1024.0)
        }
        return null
    }

    /** تحذير فقط — لا يمنع التشغيل لأن الذاكرة المتاحة تتغير بسرعة. */
    fun ramWarning(context: Context, prefs: SharedPreferences): String? {
        val profile = deviceProfile(context)
        val need = neededRamMb(model(prefs).bytes, ctx(prefs))
        if (profile.availRamMb in 1..(need - 150L)) {
            return "⚠️ الرام المتاحة ${profile.availText} والمطلوب ≈ %.1f GB — قد يتوقف المحرّك."
                .format(need / 1024.0)
        }
        return null
    }

    // ─────────────────────────── الحالة والفحص ───────────────────────────

    fun refreshPhase(context: Context? = ContextHolder.peek()) {
        if (starting) {
            phase = Phase.STARTING
            return
        }
        if (isRunning()) {
            phase = Phase.RUNNING
            return
        }
        val ctx = context ?: run {
            phase = Phase.LINUX_MISSING
            return
        }
        phase = when {
            !LinuxEnv.ready(ctx) -> Phase.LINUX_MISSING
            !serverInstalled(ctx) -> Phase.SERVER_MISSING
            !hasModel(ctx, prefsOrNull() ?: return) -> Phase.MODEL_MISSING
            else -> Phase.READY
        }
    }

    /** حزمة llama-server موجودة داخل Alpine؟ (نتيجة الفحص مؤقتة لتفادي استدعاء proot دائماً). */
    @Volatile private var serverCache: Pair<Long, Boolean> = 0L to false

    fun serverInstalled(context: Context, recheck: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val (at, ok) = serverCache
        if (!recheck && at > 0 && now - at < 60_000L) return ok
        if (!LinuxEnv.ready(context)) return false
        val result = LinuxEnv.run(context, "command -v llama-server", 30)
        val found = result.ok && result.output.contains("/llama-server")
        serverCache = now to found
        return found
    }

    fun isRunning(): Boolean {
        val now = System.currentTimeMillis()
        val (at, ok) = lastProbe
        if (now - at < 1_500L) return ok
        val alive = httpHealth()
        lastProbe = now to alive
        return alive
    }

    private fun httpHealth(): Boolean = try {
        val conn = URL(HEALTH_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 700
        conn.readTimeout = 1_200
        conn.requestMethod = "GET"
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
        conn.disconnect()
        parseHealth(body)
    } catch (e: Exception) {
        false
    }

    /** llama-server: {"status":"ok"} عند الجاهزية، و{"status":"loading model"} أثناء التحميل. */
    internal fun parseHealth(body: String): Boolean {
        val clean = body.trim()
        if (clean.isEmpty()) return false
        val json = runCatching { JSONObject(clean) }.getOrNull() ?: return false
        val status = json.optString("status").lowercase()
        return status == "ok" || (status.isBlank() && json.has("slots"))
    }

    internal fun phaseLabel(value: Phase): String = when (value) {
        Phase.LINUX_MISSING -> "بيئة Linux غير مفعّلة"
        Phase.SERVER_MISSING -> "محرّك llama.cpp غير مثبّت"
        Phase.MODEL_MISSING -> "النموذج (GGUF) غير منزَّل"
        Phase.READY -> "جاهز للتشغيل"
        Phase.STARTING -> "يبدأ الآن…"
        Phase.RUNNING -> "يعمل — الاستدلال على الجهاز"
        Phase.ERROR -> "خطأ"
    }

    fun statusText(context: Context): String {
        val prefs = prefsOrNull()
        val sb = StringBuilder()
        sb.append("🦁 Lion-1 — المحرّك المدمج\n")
        sb.append("• الحالة: ").append(phaseLabel(phase)).append('\n')
        sb.append("• محرّك llama-server: ").append(if (serverInstalled(context)) "مثبّت ✅" else "غير مثبّت ❌").append('\n')
        if (prefs != null) {
            val file = modelFile(context, prefs)
            sb.append("• النموذج: ").append(if (customUrl(prefs).isNotBlank()) "مخصص" else model(prefs).id)
                .append(" — ").append(modelSizeText(context, prefs)).append('\n')
            sb.append("• الملف: ").append(file.absolutePath).append('\n')
            sb.append("• السياق: ").append(ctx(prefs)).append(" رمز · الخيوط: ").append(threads(prefs)).append('\n')
            sb.append("• التشغيل التلقائي: ").append(if (autoStart(prefs)) "مفعّل" else "متوقف").append('\n')
        }
        runCatching {
            val profile = deviceProfile(context)
            sb.append("• الجهاز: رام ").append(profile.ramText)
                .append(" (متاح ").append(profile.availText).append(") · مساحة حرة ").append(profile.storageText).append('\n')
        }
        sb.append("• المنفذ: ").append(BASE_URL).append(if (isRunning()) " (يستجيب ✅)" else " (لا يستجيب)").append('\n')
        if (progress.isNotBlank()) sb.append("• التقدّم: ").append(progress).append('\n')
        if (lastError.isNotBlank()) sb.append("• آخر خطأ: ").append(lastError).append('\n')
        val tail = logTail.trim()
        if (tail.isNotBlank()) sb.append("\nآخر مخرجات المحرّك:\n").append(tail.takeLast(1200))
        return sb.toString()
    }

    // ─────────────────────────── التثبيت ───────────────────────────

    /** تثبيت حزمة llama-server داخل Alpine (مستودع community في نفس إصدار التوزيعة). */
    @Synchronized
    fun installServer(context: Context, onLine: ((String) -> Unit)? = null): ShellResult {
        if (!LinuxEnv.ready(context)) {
            val err = "فعّل بيئة Linux أولاً (البيئة المحلية ← تحميل وتفعيل)."
            lastError = err
            phase = Phase.LINUX_MISSING
            return ShellResult(false, err, 1, 0)
        }
        progress = "تثبيت llama-server…"
        val script = """
            set -e
            apk update || true
            apk add --no-cache llama-server
            command -v llama-server
            llama-server --version 2>&1 | head -3 || true
        """.trimIndent()
        var result = LinuxEnv.run(context, script, 1500, onLine = onLine)
        if (!result.ok) {
            // بعض الإصدارات تُبقي الحزمة في community فقط: نحاول إضافة المستودع صراحةً.
            val branch = runCatching {
                LinuxEnv.run(context, "cat /etc/alpine-release", 30).output.trim().lines().first()
            }.getOrDefault("")
            val rel = branch.substringBefore('.').ifBlank { "latest-stable" }
            val fallback = """
                set -e
                echo "https://dl-cdn.alpinelinux.org/alpine/v$rel/community" >> /etc/apk/repositories
                apk update || true
                apk add --no-cache llama-server
                command -v llama-server
            """.trimIndent()
            result = LinuxEnv.run(context, fallback, 1500, onLine = onLine)
        }
        serverCache = System.currentTimeMillis() to result.ok
        progress = if (result.ok) "✅ llama-server مثبّت" else "❌ فشل تثبيت llama-server"
        if (!result.ok) lastError = "تثبيت llama-server فشل (exit ${result.exitCode})"
        refreshPhase(context)
        return result.copy(output = "=== llama-server ===\n" + result.output.takeLast(12_000))
    }

    /** تنزيل النموذج (قابل للاستئناف) إلى مساحة العمل، مع تحقق من الحجم المتوقع. */
    @Synchronized
    fun downloadModel(
        context: Context,
        prefs: SharedPreferences,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean {
        val custom = customUrl(prefs)
        val target = modelFile(context, prefs)
        val expected = if (custom.isNotBlank()) 0L else model(prefs).bytes
        val url = if (custom.isNotBlank()) custom else model(prefs).url
        if (url.isBlank()) {
            lastError = "لا يوجد رابط تنزيل للنموذج."
            return false
        }
        if (target.exists() && expected > 0 && target.length() == expected) {
            progress = "✅ النموذج منزَّل مسبقاً"
            refreshPhase(context)
            return true
        }
        val profile = deviceProfile(context)
        val needMb = (if (expected > 0) expected else 1_200L * 1024 * 1024) / (1024 * 1024) + 400L
        if (profile.freeStorageMb in 0..needMb) {
            lastError = "المساحة الحرة ${profile.storageText} لا تكفي لتنزيل النموذج (المطلوب ≈ %.1f GB)."
                .format(needMb / 1024.0)
            progress = "❌ مساحة غير كافية"
            return false
        }
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        return try {
            val existing = if (part.exists()) part.length() else 0L
            if (expected > 0 && existing > expected) part.delete()
            val skip = if (part.exists()) part.length() else 0L
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 25_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            if (skip > 0) conn.setRequestProperty("Range", "bytes=$skip-")
            val code = conn.responseCode
            if (code !in 200..299) {
                lastError = "HTTP $code أثناء تنزيل النموذج"
                progress = "❌ فشل التنزيل (HTTP $code)"
                return false
            }
            val total = if (code == 206) conn.contentLengthLong + skip else conn.contentLengthLong
            val append = code == 206 && skip > 0
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buffer = ByteArray(128 * 1024)
                    var done = if (append) skip else 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - lastReport >= 2L * 1024 * 1024) {
                            lastReport = done
                            progress = progressText(done, total)
                            onProgress?.invoke(done, total)
                        }
                    }
                    progress = progressText(done, total)
                    onProgress?.invoke(done, total)
                }
            }
            if (part.length() < 1024L * 1024L) {
                lastError = "الملف المنزَّل صغير جداً — قد يكون رابطاً خاطئاً."
                progress = "❌ ملف غير صالح"
                part.delete()
                return false
            }
            if (target.exists()) target.delete()
            val moved = part.renameTo(target)
            if (!moved) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            progress = "✅ اكتمل تنزيل النموذج (" + modelSizeText(context, prefs) + ")"
            lastError = ""
            refreshPhase(context)
            true
        } catch (e: Exception) {
            lastError = "تعذّر التنزيل: ${e.message ?: e.javaClass.simpleName}"
            progress = "⏸️ توقف التنزيل — أعد المحاولة للاستئناف من حيث توقّف"
            false
        }
    }

    internal fun progressText(done: Long, total: Long): String {
        val mb = done / (1024 * 1024)
        return if (total > 0) {
            val pct = (done * 100 / total).coerceIn(0, 100)
            "⬇️ تنزيل النموذج $pct% ($mb / ${total / (1024 * 1024)} MB)"
        } else {
            "⬇️ تنزيل النموذج $mb MB"
        }
    }

    // ─────────────────────────── التشغيل والإيقاف ───────────────────────────

    /** يشغّل llama-server إن لم يكن يعمل. يعيد true إذا صار جاهزاً للاستدعاء. */
    @Synchronized
    fun start(context: Context, waitSeconds: Int = 240): Boolean {
        if (isRunning()) {
            phase = Phase.RUNNING
            return true
        }
        if (!LinuxEnv.ready(context)) {
            lastError = "بيئة Linux غير مفعّلة."
            phase = Phase.LINUX_MISSING
            return false
        }
        val prefs = prefsOrNull() ?: run {
            lastError = "لم يُهيأ سياق التطبيق."
            return false
        }
        if (!serverInstalled(context)) {
            lastError = "محرّك llama-server غير مثبّت."
            phase = Phase.SERVER_MISSING
            return false
        }
        if (!hasModel(context, prefs)) {
            lastError = "النموذج غير منزَّل."
            phase = Phase.MODEL_MISSING
            return false
        }
        // فحص قبل التشغيل: نقص المساحة يمنع، ونقص الرام يُحذَّر فقط (يتغير بسرعة).
        preflight(context, prefs)?.let { problem ->
            if (problem.contains("المساحة")) {
                lastError = problem
                phase = Phase.ERROR
                progress = "❌ مساحة غير كافية"
                return false
            }
            appendLog("⚠️ $problem")
        }
        starting = true
        phase = Phase.STARTING
        lastError = ""
        progress = "تشغيل المحرّك…"
        logLines.clear()
        logTail = ""
        val modelPath = guestPath(context, modelFile(context, prefs))
        val contextSize = ctx(prefs)
        val threadCount = threads(prefs)
        // أعلام التشغيل من LionEngineTuning: batch أكبر + إعادة استخدام ذاكرة المطالبة (cache-reuse)
        // + threads للـbatch، لتسريع قراءة المطالبة والأسئلة المتتابعة في llama.cpp.
        val cores = Runtime.getRuntime().availableProcessors()
        val tuningArgs = LionEngineTuning.launchArgs(contextSize, threadCount, cores)
        val script = "exec llama-server -m '$modelPath' --host 127.0.0.1 --port $PORT $tuningArgs"
        return try {
            val proc = LinuxEnv.start(
                context,
                "cd /workspace/models/lion 2>/dev/null || cd /workspace; $script",
                FileTools.root(context)
            )
            process = proc
            thread(name = "lion-engine-log", isDaemon = true) {
                runCatching {
                    proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line -> appendLog(line) }
                    }
                }
            }
            val deadline = System.currentTimeMillis() + waitSeconds * 1000L
            var ok = false
            while (System.currentTimeMillis() < deadline) {
                if (isRunning()) {
                    ok = true
                    break
                }
                if (!proc.isAlive) break
                Thread.sleep(400)
            }
            if (ok) {
                phase = Phase.RUNNING
                progress = "✅ المحرّك يعمل على $BASE_URL"
                lastError = ""
            } else {
                phase = Phase.ERROR
                val tail = logTail.takeLast(800)
                lastError = if (!proc.isAlive) {
                    "توقف llama-server قبل الجاهزية.\n$tail"
                } else {
                    "لم يستجب llama-server خلال $waitSeconds ثانية.\n$tail"
                }
                progress = "❌ فشل التشغيل"
                runCatching { proc.destroyForcibly() }
                process = null
            }
            ok
        } catch (e: Exception) {
            phase = Phase.ERROR
            lastError = "تعذّر تشغيل المحرّك: ${e.message ?: e.javaClass.simpleName}"
            progress = "❌ فشل التشغيل"
            false
        } finally {
            starting = false
        }
    }

    /** يضمن أن المحرّك جاهز — يستدعى من مسار lion-1 قبل الإرسال. لا ينزّل شيئاً بنفسه. */
    fun ensureReady(): Boolean {
        if (isRunning()) return true
        val prefs = prefsOrNull() ?: return false
        if (!autoStart(prefs)) return false
        val context = ContextHolder.peek() ?: return false
        if (starting) {
            // تشغيل جارٍ: ننتظر بدل فتح نسخة ثانية على نفس المنفذ.
            val deadline = System.currentTimeMillis() + 240_000L
            while (starting && System.currentTimeMillis() < deadline) Thread.sleep(300)
            return isRunning()
        }
        if (!LinuxEnv.ready(context) || !serverInstalled(context) || !hasModel(context, prefs)) {
            refreshPhase(context)
            return false
        }
        return start(context)
    }

    fun stop(): String {
        val proc = process
        process = null
        runCatching { proc?.destroy() }
        runCatching { proc?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
        runCatching { if (proc?.isAlive == true) proc.destroyForcibly() }
        // احتياط: أي نسخة يتيمة داخل البيئة (بعد إعادة تشغيل التطبيق لا نملك Process).
        val context = ContextHolder.peek()
        if (context != null && LinuxEnv.ready(context)) {
            runCatching { LinuxEnv.run(context, "pkill -f llama-server || true", 30) }
        }
        lastProbe = 0L to false
        Thread.sleep(300)
        phase = if (isRunning()) Phase.ERROR else Phase.READY
        lastError = if (phase == Phase.ERROR) "المنفذ $PORT ما زال مشغولاً بعملية أخرى." else ""
        progress = "⏹️ أُوقف المحرّك"
        refreshPhase(context)
        return statusText(context ?: ContextHolder.require())
    }

    fun shutdown() {
        runCatching { process?.destroy() }
        process = null
    }

    /** إظهار سطر من مخرجات التثبيت/التشغيل في الواجهة دون انتظار انتهاء العملية. */
    internal fun debugLine(line: String) {
        val clean = line.trim()
        if (clean.isNotEmpty()) appendLog(clean)
    }

    private fun appendLog(line: String) {
        synchronized(logLines) {
            logLines.addLast(line.take(300))
            while (logLines.size > 40) logLines.removeFirst()
            logTail = logLines.joinToString("\n")
        }
    }

    // ─────────────────────────── الاختبار الذاتي ───────────────────────────

    /** فحص من طرف إلى طرف: صحة المنفذ + رد فعلي من النموذج على الجهاز. */
    fun selfTest(context: Context): String {
        if (!isRunning() && !ensureReady()) {
            return "❌ المحرّك لا يعمل: " + (lastError.ifBlank { phaseLabel(phase) })
        }
        val prefs = prefsOrNull()
        val model = prefs?.let { if (customUrl(it).isNotBlank()) "lion-1" else "lion-1" } ?: "lion-1"
        return try {
            val started = System.currentTimeMillis()
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 48)
                .put("temperature", 0.0)
                .put("messages", org.json.JSONArray().put(
                    JSONObject().put("role", "user").put("content", "أجب بكلمة واحدة: هل أنت جاهز؟")
                ))
            val conn = URL(CHAT_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) return "❌ المحرّك رد HTTP $code\n" + text.take(600)
            val reply = runCatching {
                JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content", "").orEmpty()
            }.getOrDefault("")
            val ms = System.currentTimeMillis() - started
            "✅ المحرّك يعمل (${ms}ms)\n• الرد: " + reply.trim().take(300) +
                "\n• النموذج: " + (prefs?.let { modelFile(context, it).name } ?: "—")
        } catch (e: Exception) {
            "❌ فشل الاختبار: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** أداة التطبيق `lion_engine`: يدير الوكيل محرّكه بنفسه دون تدخّل المستخدم. */
    suspend fun toolAction(ctx: ToolContext, args: JSONObject): String {
        val action = args.optString("action", "status").trim().lowercase()
        val prefs = ctx.prefs
        return when (action) {
            "status" -> statusText(ctx.context)
            "models" -> LionCatalog.MODELS.joinToString("\n") { m ->
                "• ${m.id} — ${m.label}\n  ${m.note} | ${m.bytes / (1024 * 1024)} MB"
            } + "\n• custom — ضع رابط GGUF خاصاً بك من واجهة المحرّك."
            "install" -> {
                if (!ctx.allowWrite) "⛔ التثبيت يحتاج «السماح بالكتابة»."
                else {
                    val r = installServer(ctx.context)
                    "=== تثبيت المحرّك ===\n" + r.output.takeLast(4000)
                }
            }
            "download" -> {
                if (!ctx.allowWrite) "⛔ تنزيل النموذج يحتاج «السماح بالكتابة»."
                else if (downloadModel(ctx.context, prefs)) statusText(ctx.context)
                else "❌ " + lastError
            }
            "start" -> if (start(ctx.context)) statusText(ctx.context) else "❌ " + lastError
            "stop" -> stop()
            "test" -> selfTest(ctx.context)
            else -> "إجراء غير معروف: $action (status|models|install|download|start|stop|test)"
        }
    }
}
