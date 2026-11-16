package com.lion.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
 * حزمة تحسينات مزود Lion-1 (كلها خاصة بالمزود، لا تمسّ بقية التطبيق):
 *   LionText / LionTokens   تطبيع عربي وتقدير الرموز
 *   LionContext             فهم السياق: ضغط التاريخ ضمن ميزانية النموذج مع ملخص لما حُذف
 *   LionToolPicker          وصول ذكي للأدوات والموصلات: يرسل الأدوات الأنسب فقط
 *   LionExperience          تراكم الخبرة: نجاح المسارات والأدوات ودروس الأخطاء (محفوظة مشفّرة)
 *   LionHeal                معالجة الأخطاء تلقائياً: تصنيف ثم خطة إصلاح
 *   LionEngineTuning        أعلام تشغيل llama-server الأسرع
 *   warmUp / selfTestDeep   تسخين المحرّك وفحص عميق (سرعة + استدعاء أداة فعلي)
 */

internal object LionText {
    private val MARKS = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(s: String): String = s.lowercase().replace(MARKS, "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ة', 'ه').replace('ى', 'ي')

    fun words(s: String): List<String> = normalize(s).split(SPLIT).filter { it.length >= 2 }

    fun isArabic(c: Char): Boolean = c.code in 0x0600..0x06FF

    fun hasArabic(s: String): Boolean = s.any { isArabic(it) }
}

internal object LionTokens {
    /** تقدير تقريبي لرموز Qwen: العربية ≈ 2.2 حرف/رمز وغيرها ≈ 3.8. */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var ar = 0
        var other = 0
        for (c in text) if (LionText.isArabic(c)) ar++ else other++
        return Math.ceil(ar / 2.2 + other / 3.8).toInt() + 1
    }

    fun estimate(turns: List<ChatTurn>): Int = turns.sumOf { estimate(it.content) + 4 }
}

/** فهم السياق: يبقي المحادثة داخل ميزانية النموذج دون أن يضيع الخيط. */
internal object LionContext {
    fun truncateMiddle(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val head = (maxChars * 0.6).toInt()
        val tail = (maxChars - head).coerceAtLeast(0)
        return text.take(head) + "\n…[قُصّ ${text.length - maxChars} حرفاً]…\n" + text.takeLast(tail)
    }

    private fun digest(turns: List<ChatTurn>): String =
        turns.joinToString(" | ") {
            (if (it.role == "user") "م: " else "أ: ") + it.content.replace(Regex("\\s+"), " ").take(90)
        }.take(700)

    /** ميزانية رموز التاريخ بعد خصم النظام والرسالة والأدوات ومساحة الرد. */
    fun historyBudget(ctx: Int, system: String, message: String, toolsTokens: Int, scale: Double = 1.0): Int {
        val reserve = minOf(1024, ctx / 4)
        val raw = ctx - LionTokens.estimate(system) - LionTokens.estimate(message) - toolsTokens - reserve - 64
        return (maxOf(raw, 256) * scale).toInt().coerceAtLeast(128)
    }

    /**
     * 1) يقصّ الرسائل الضخمة (مخرجات أدوات طويلة) من المنتصف.
     * 2) إن بقي التاريخ أكبر من الميزانية: يبقي أحدث الرسائل ويدمج ملخصاً قصيراً لما حُذف في أولها.
     */
    fun fit(history: List<ChatTurn>, budgetTokens: Int): List<ChatTurn> {
        if (history.isEmpty()) return history
        val budget = budgetTokens.coerceAtLeast(128)
        val perTurnChars = (budget * 0.8).toInt().coerceAtLeast(200)
        val capped = history.map {
            if (it.content.length > perTurnChars) ChatTurn(it.role, truncateMiddle(it.content, perTurnChars)) else it
        }
        if (LionTokens.estimate(capped) <= budget) return capped

        val keepBudget = (budget * 0.75).toInt()
        val kept = mutableListOf<ChatTurn>()
        var used = 0
        for (i in capped.indices.reversed()) {
            val cost = LionTokens.estimate(capped[i].content) + 4
            if (kept.size >= 2 && used + cost > keepBudget) break
            kept.add(0, capped[i])
            used += cost
        }
        val dropped = capped.size - kept.size
        if (dropped <= 0) return kept
        val first = kept[0]
        kept[0] = ChatTurn(first.role, "[ملخص ما سبق: ${digest(capped.take(dropped))}]\n" + first.content)
        return kept
    }
}

/** وصول ذكي للأدوات والموصلات: ينتقي الأدوات الأنسب للطلب بدل إغراق سياق النموذج الصغير. */
internal object LionToolPicker {
    private val CORE = setOf("tools_catalog")

    /** كلمات في الطلب ← أجزاء أسماء أدوات (الموصلات والملفات والتشغيل والويب…). */
    private val HINTS: List<Pair<List<String>, List<String>>> = listOf(
        listOf("gmail", "mail", "بريد", "ايميل", "اميل", "inbox", "رسايل") to listOf("gmail"),
        listOf("درايف", "drive", "سحاب") to listOf("drive"),
        listOf("تقويم", "موعد", "اجتماع", "calendar", "event") to listOf("calendar"),
        listOf("notion", "نوشن") to listOf("notion"),
        listOf("slack", "سلاك") to listOf("slack"),
        listOf("تيليجرام", "تلغرام", "telegram") to listOf("telegram"),
        listOf("واتساب", "واتس", "whatsapp") to listOf("whatsapp"),
        listOf("github", "جيت", "مستودع", "repo", "commit", "pull", "issue", "branch", "فرع") to listOf("github", "git_"),
        listOf("firebase", "فايربيس", "firestore") to listOf("firebase", "firestore", "storage_"),
        listOf("ملف", "مجلد", "اقرا", "اكتب", "احفظ", "file", "folder", "read", "write", "عدل") to
            listOf("file", "read", "write", "list", "edit", "patch", "grep", "search"),
        listOf("شغل", "نفذ", "ثبت", "بناء", "gradle", "npm", "pip", "install", "build", "run", "test", "اختبار", "اوامر") to
            listOf("shell", "exec", "build", "install", "test"),
        listOf("موقع", "رابط", "ويب", "انترنت", "http", "url", "site", "ابحث", "بحث") to
            listOf("web", "site", "fetch", "http", "search"),
        listOf("فحص", "اسرار", "امان", "secret", "scan", "audit") to listOf("scan", "secret", "security", "audit"),
        listOf("تذكر", "ذاكره", "memory", "remember") to listOf("memory", "remember", "note"),
        listOf("محرك", "lion", "engine", "نموذج") to listOf("lion_engine")
    ).map { (keys, frags) -> keys.map { LionText.normalize(it) } to frags }

    private class Item(val index: Int, val name: String, val desc: String, val tokens: Int)

    private fun nameOf(tool: JSONObject): String =
        tool.optJSONObject("function")?.optString("name")?.takeIf { it.isNotBlank() } ?: tool.optString("name")

    private fun descOf(tool: JSONObject): String =
        tool.optJSONObject("function")?.optString("description")?.takeIf { it.isNotBlank() }
            ?: tool.optString("description")

    fun names(tools: JSONArray): List<String> =
        (0 until tools.length()).mapNotNull { tools.optJSONObject(it)?.let(::nameOf)?.takeIf { n -> n.isNotBlank() } }

    fun pick(
        tools: JSONArray,
        message: String,
        history: List<ChatTurn>,
        budgetTokens: Int,
        maxTools: Int = 18,
        minTools: Int = 8,
        boost: (String) -> Double = { 0.0 }
    ): JSONArray {
        val items = (0 until tools.length()).mapNotNull { i ->
            tools.optJSONObject(i)?.let { Item(i, nameOf(it), descOf(it), LionTokens.estimate(it.toString())) }
        }
        if (items.isEmpty()) return tools
        if (items.size <= maxTools && items.sumOf { it.tokens } <= budgetTokens) return tools

        val msgWords = LionText.words(message).toSet()
        val ctxWords = LionText.words(history.takeLast(2).joinToString(" ") { it.content.take(300) }).toSet() - msgWords
        val query = LionText.normalize(message + " " + history.takeLast(2).joinToString(" ") { it.content.take(300) })
        val recent = history.takeLast(4).joinToString(" ") { it.content }.lowercase()

        fun hit(word: String, pool: Set<String>): Boolean =
            word in pool || pool.any { it.length >= 4 && word.length >= 4 && (it.startsWith(word) || word.startsWith(it)) }

        val scored = items.map { item ->
            val lname = item.name.lowercase()
            var score = 0.0
            if (item.name in CORE) score += 100.0
            if (lname.length >= 4 && query.contains(lname)) score += 50.0
            lname.split('_', '-').filter { it.length >= 2 }.forEach { part ->
                if (hit(part, msgWords)) score += 3.0 else if (hit(part, ctxWords)) score += 1.0
            }
            score += LionText.words(item.desc).count { it in msgWords }.coerceAtMost(4).toDouble()
            HINTS.forEach { (keys, frags) ->
                if (keys.any { query.contains(it) } && frags.any { lname.contains(it) }) score += 4.0
            }
            if (lname.length >= 4 && recent.contains(lname)) score += 2.0
            score += boost(item.name) * 2.0
            item to score
        }.sortedWith(compareByDescending<Pair<Item, Double>> { it.second }.thenBy { it.first.index })

        val chosen = mutableListOf<Item>()
        var used = 0
        for ((item, score) in scored) {
            if (chosen.size >= maxTools) break
            val fillOnly = score <= 0.0
            if (fillOnly && chosen.size >= minTools) break
            if (used + item.tokens > budgetTokens && chosen.size >= 3) continue
            chosen.add(item)
            used += item.tokens
        }
        val out = JSONArray()
        chosen.forEach { out.put(tools.get(it.index)) }
        return out
    }
}

/** تراكم الخبرة: ما نجح وما فشل، يُحفظ بصيغة JSON صغيرة محدودة الحجم. */
internal class LionExperience(
    private val load: () -> String,
    private val save: (String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        private const val MAX_TOOLS_PER_TASK = 60
        private const val MAX_LESSONS = 20
        private const val LESSON_TTL_MS = 14L * 24 * 3600 * 1000

        fun inMemory(): LionExperience {
            var box = ""
            return LionExperience({ box }, { box = it })
        }
    }

    private val lock = Any()
    private var root: JSONObject? = null

    private fun data(): JSONObject {
        root?.let { return it }
        val parsed = runCatching { JSONObject(load().ifBlank { "{}" }) }.getOrDefault(JSONObject())
        root = parsed
        return parsed
    }

    private fun section(parent: JSONObject, name: String): JSONObject =
        parent.optJSONObject(name) ?: JSONObject().also { parent.put(name, it) }

    private fun persist() {
        runCatching { save(data().toString()) }
    }

    private fun prune(obj: JSONObject, max: Int, weight: (JSONObject) -> Long) {
        val keys = obj.keys().asSequence().toList()
        if (keys.size <= max) return
        keys.sortedBy { weight(obj.optJSONObject(it) ?: JSONObject()) }
            .take(keys.size - max).forEach { obj.remove(it) }
    }

    fun recordRoute(key: String, ok: Boolean, ms: Long) {
        synchronized(lock) {
            val routes = section(data(), "routes")
            val r = section(routes, key)
            var n = r.optInt("n") + 1
            var good = r.optInt("ok") + if (ok) 1 else 0
            if (n > 100) { n /= 2; good /= 2 }
            r.put("n", n).put("ok", good)
            if (ok) {
                val prev = r.optDouble("ms", 0.0)
                r.put("ms", if (prev <= 0.0) ms.toDouble() else prev * 0.8 + ms * 0.2)
            }
            persist()
        }
    }

    /** 0..1 تقريباً: نسبة النجاح مع عقوبة صغيرة للبطء. المجهول = 0.5. */
    fun routeScore(key: String): Double {
        synchronized(lock) {
            val r = section(data(), "routes").optJSONObject(key) ?: return 0.5
            val rate = (r.optInt("ok") + 1.0) / (r.optInt("n") + 2.0)
            return rate - minOf(r.optDouble("ms", 0.0), 60_000.0) / 600_000.0
        }
    }

    fun recordTool(task: String, tool: String, ok: Boolean) {
        synchronized(lock) {
            val perTask = section(section(data(), "tools"), task)
            val t = section(perTask, tool)
            if (ok) t.put("ok", t.optInt("ok") + 1) else t.put("bad", t.optInt("bad") + 1)
            prune(perTask, MAX_TOOLS_PER_TASK) { (it.optInt("ok") + it.optInt("bad")).toLong() }
            persist()
        }
    }

    /** -0.5..+0.7: الأدوات التي نجحت لهذه المهمة تتقدّم، والتي فشلت تتأخر. */
    fun toolBoost(task: String, tool: String): Double {
        synchronized(lock) {
            val t = section(section(data(), "tools"), task).optJSONObject(tool) ?: return 0.0
            val ok = t.optInt("ok")
            val bad = t.optInt("bad")
            return (ok + 1.0) / (ok + bad + 2.0) - 0.5 + minOf(ok, 10) * 0.02
        }
    }

    fun recordLesson(tool: String, error: String) {
        if (tool.isBlank() || error.isBlank()) return
        synchronized(lock) {
            val lessons = section(data(), "lessons")
            val l = section(lessons, tool)
            l.put("n", l.optInt("n") + 1).put("msg", error.take(160)).put("t", clock())
            prune(lessons, MAX_LESSONS) { it.optLong("t") }
            persist()
        }
    }

    /** دروس متكررة (مرتان فأكثر خلال أسبوعين) تخص الأدوات المعروضة الآن. */
    fun lessonsFor(names: Collection<String>, maxLines: Int = 3): List<String> {
        synchronized(lock) {
            val lessons = section(data(), "lessons")
            val now = clock()
            return names.mapNotNull { name ->
                val l = lessons.optJSONObject(name) ?: return@mapNotNull null
                if (l.optInt("n") < 2 || now - l.optLong("t") > LESSON_TTL_MS) null
                else Triple(l.optInt("n"), name, l.optString("msg"))
            }.sortedByDescending { it.first }.take(maxLines)
                .map { "- ${it.second}: تعثّرت ${it.first} مرات، السبب: ${it.third}" }
        }
    }

    fun summary(): String {
        synchronized(lock) {
            val d = data()
            val routes = section(d, "routes")
            val sb = StringBuilder("الخبرة المتراكمة\n")
            val keys = routes.keys().asSequence().toList()
            if (keys.isEmpty()) sb.append("• لا بيانات بعد\n")
            keys.forEach { k ->
                val r = routes.optJSONObject(k) ?: return@forEach
                sb.append("• مسار $k: ${r.optInt("ok")}/${r.optInt("n")} نجاح")
                    .append(" · متوسط ${r.optDouble("ms", 0.0).toLong()}ms\n")
            }
            val lessons = section(d, "lessons")
            sb.append("• دروس مسجّلة: ${lessons.length()}")
            return sb.toString()
        }
    }

    fun reset() {
        synchronized(lock) {
            root = JSONObject()
            persist()
        }
    }
}

/** معالجة الأخطاء تلقائياً: تصنيف السبب ثم خطة إصلاح (إعادة، ضغط سياق، إعادة تشغيل المحرّك…). */
internal object LionHeal {
    enum class Kind { CONTEXT_OVERFLOW, TIMEOUT, REFUSED, ENGINE_DEAD, RATE_LIMIT, AUTH, SERVER, EMPTY, OTHER }
    enum class Fix { NONE, RETRY, COMPACT, RESTART_ENGINE, COOLER }
    data class Plan(val fix: Fix, val delayMs: Long = 0L, val coolMs: Long = 0L)

    private val R_429 = Regex("\\b429\\b")
    private val R_AUTH = Regex("\\b(401|403)\\b")
    private val R_5XX = Regex("\\b(500|502|503|504)\\b")
    private val R_EOF = Regex("\\beof\\b|eofexception")

    fun classify(error: Throwable?, text: String = ""): Kind {
        val b = (text + " " + (error?.message ?: "") + " " + (error?.javaClass?.simpleName ?: "")).lowercase().trim()
        return when {
            b.isEmpty() || b == "رد فارغ" -> Kind.EMPTY
            b.contains("n_ctx") || b.contains("n_keep") || b.contains("too long") ||
                b.contains("too many tokens") || b.contains("maximum context") ||
                (b.contains("context") && (b.contains("exceed") || b.contains("length") || b.contains("overflow"))) ->
                Kind.CONTEXT_OVERFLOW
            b.contains("timeout") || b.contains("timed out") -> Kind.TIMEOUT
            b.contains("econnrefused") || b.contains("connection refused") ||
                b.contains("failed to connect") || b.contains("connectexception") -> Kind.REFUSED
            b.contains("connection reset") || b.contains("broken pipe") ||
                b.contains("unexpected end of stream") || b.contains("stream was reset") ||
                R_EOF.containsMatchIn(b) -> Kind.ENGINE_DEAD
            R_429.containsMatchIn(b) || b.contains("rate limit") || b.contains("quota") ||
                b.contains("too many requests") -> Kind.RATE_LIMIT
            R_AUTH.containsMatchIn(b) || b.contains("unauthorized") || b.contains("forbidden") ||
                b.contains("invalid api key") -> Kind.AUTH
            R_5XX.containsMatchIn(b) || b.contains("loading model") || b.contains("service unavailable") ||
                b.contains("bad gateway") || b.contains("internal server") -> Kind.SERVER
            else -> Kind.OTHER
        }
    }

    fun plan(kind: Kind, isEngine: Boolean, attempt: Int): Plan {
        // المحرّك المحلي لا يُعاقَب طويلاً: قد يكون بطيئاً لا معطّلاً.
        val cool = { normal: Long -> if (isEngine) minOf(normal, 20_000L) else normal }
        if (attempt >= 2) return Plan(Fix.NONE, 0, cool(45_000L))
        return when (kind) {
            Kind.CONTEXT_OVERFLOW -> Plan(Fix.COMPACT)
            Kind.TIMEOUT -> if (isEngine && attempt == 0) Plan(Fix.COMPACT) else Plan(Fix.NONE, 0, cool(30_000L))
            Kind.REFUSED, Kind.ENGINE_DEAD ->
                if (isEngine && attempt == 0) Plan(Fix.RESTART_ENGINE) else Plan(Fix.NONE, 0, cool(60_000L))
            Kind.SERVER -> if (attempt == 0) Plan(Fix.RETRY, 1_500L) else Plan(Fix.NONE, 0, cool(30_000L))
            Kind.EMPTY -> if (attempt == 0) Plan(Fix.COOLER) else Plan(Fix.NONE, 0, cool(15_000L))
            Kind.RATE_LIMIT -> Plan(Fix.NONE, 0, 120_000L)
            Kind.AUTH -> Plan(Fix.NONE, 0, 600_000L)
            Kind.OTHER -> Plan(Fix.NONE, 0, cool(45_000L))
        }
    }

    fun label(kind: Kind): String = when (kind) {
        Kind.CONTEXT_OVERFLOW -> "السياق أكبر من طاقة النموذج"
        Kind.TIMEOUT -> "انتهت المهلة"
        Kind.REFUSED -> "الخادم لا يستجيب"
        Kind.ENGINE_DEAD -> "انقطع الاتصال أثناء الرد"
        Kind.RATE_LIMIT -> "حدّ الاستخدام"
        Kind.AUTH -> "مفتاح/صلاحية مرفوضة"
        Kind.SERVER -> "الخادم مشغول أو يحمّل النموذج"
        Kind.EMPTY -> "رد فارغ"
        Kind.OTHER -> "خطأ"
    }

    private val FAIL_MARKS = listOf("❌", "⛔", "فشل", "خطأ", "error", "exception", "denied", "not found", "غير موجود", "غير مسموح", "invalid")

    /** هل نتيجة الأداة تبدو فاشلة؟ تُفحص بداية النص فقط. */
    fun looksFailed(text: String): Boolean {
        val head = text.take(120).lowercase()
        return FAIL_MARKS.any { head.contains(it) }
    }

    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
    private val LONG_TOKEN = Regex("[A-Za-z0-9_\\-]{24,}")

    /** مقتطف خطأ آمن للحفظ: بلا بريد ولا مفاتيح طويلة. */
    fun errorSnippet(text: String): String =
        text.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(EMAIL, "<email>").replace(LONG_TOKEN, "<token>")
            .trimStart('❌', '⛔', ' ').take(140)
}

/** أعلام تشغيل llama-server: أسرع في قراءة المطالبة، وأخف في الذاكرة، مع إعادة استخدام ذاكرة المطالبة. */
internal object LionEngineTuning {
    fun launchArgs(ctx: Int, threads: Int, cores: Int): String {
        val batchThreads = maxOf(threads, minOf(cores - 2, 8))
        val ubatch = if (ctx <= 4096) 128 else 256
        return "-c $ctx -t $threads -tb $batchThreads -np 1 --jinja -b 512 -ub $ubatch --cache-reuse 256 --no-warmup --alias lion-1 --alias lion-1-summary"
    }
}

private fun postJson(url: String, body: String, readMs: Int): Pair<Int, String> {
    val conn = URL(url).openConnection() as HttpURLConnection
    return try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 5_000
        conn.readTimeout = readMs
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        code to text
    } finally {
        conn.disconnect()
    }
}

/** تسخين المحرّك: طلب من رمز واحد يحمّل صفحات النموذج في الذاكرة فيصير أول رد حقيقي أسرع. */
internal fun LionEngine.warmUp() {
    if (LionOne.warmed) return
    LionOne.warmed = true
    kotlin.concurrent.thread(name = "lion-engine-warm", isDaemon = true) {
        runCatching {
            val body = JSONObject().put("model", "lion-1").put("max_tokens", 1).put("temperature", 0.0)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
            postJson(LionEngine.CHAT_URL, body.toString(), 180_000)
        }
    }
}

/** فحص عميق: الاختبار الذاتي + سرعة التوليد + استدعاء أداة حقيقي + ملخص الخبرة. */
internal fun LionEngine.selfTestDeep(context: Context): String {
    val sb = StringBuilder(selfTest(context))
    if (!isRunning()) return sb.toString()

    // 1) السرعة: llama-server يعيد timings في الرد.
    runCatching {
        val body = JSONObject().put("model", "lion-1").put("max_tokens", 64).put("temperature", 0.0)
            .put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", "اكتب جملة عربية قصيرة عن الأسد.")))
        val (code, text) = postJson(LionEngine.CHAT_URL, body.toString(), 120_000)
        val t = JSONObject(text).optJSONObject("timings")
        if (code in 200..299 && t != null) {
            sb.append("\n⚡ توليد %.1f رمز/ث · قراءة المطالبة %.1f رمز/ث".format(
                t.optDouble("predicted_per_second", 0.0), t.optDouble("prompt_per_second", 0.0)))
        } else {
            sb.append("\n⚡ لم تُرجع الخدمة بيانات السرعة (HTTP $code)")
        }
    }.onFailure { sb.append("\n⚡ تعذّر قياس السرعة: ${it.message.orEmpty().take(80)}") }

    // 2) استدعاء أداة فعلي: هل يُنتج النموذج tool_call صالحاً بوسائط JSON صحيحة؟
    runCatching {
        val tool = JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "get_time").put("description", "Get the current time for a city")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("city", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("city"))))
        val body = JSONObject().put("model", "lion-1").put("max_tokens", 96).put("temperature", 0.0)
            .put("tools", JSONArray().put(tool))
            .put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", "What time is it in Riyadh? Use the tool.")))
        val started = System.currentTimeMillis()
        val (code, text) = postJson(LionEngine.CHAT_URL, body.toString(), 120_000)
        val call = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")
        val argsOk = call?.let { runCatching { JSONObject(it.optString("arguments", "{}")).has("city") }.getOrDefault(false) } == true
        val ms = System.currentTimeMillis() - started
        sb.append(
            if (code in 200..299 && call?.optString("name") == "get_time" && argsOk)
                "\n🛠️ استدعاء الأدوات: ✅ سليم (${ms}ms)"
            else "\n🛠️ استدعاء الأدوات: ❌ لم يُنتج tool_call صالحاً — تأكد من --jinja وأن النموذج يدعم الأدوات"
        )
    }.onFailure { sb.append("\n🛠️ تعذّر اختبار الأدوات: ${it.message.orEmpty().take(80)}") }

    sb.append("\n\n").append(LionOne.experience().summary())
    return sb.toString()
}
