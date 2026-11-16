package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * تعلّم Lion-1 التلقائي (محلي على الجهاز فقط):
 *  - يلتقط أسئلة/أجوبة المزودين الآخرين بعد فلترة صارمة (أسرار، بيانات شخصية، أسماء المزودين، الأخطاء).
 *  - يجلب صفحات من مصادر عامة (افتراضية + مصادر يضيفها المستخدم) ويحوّلها إلى مقاطع معرفة.
 *  - عند أي سؤال يسترجع الأنسب ويحقنه في تعليمات Lion-1 (ويتجاهله إن لم يصلح).
 * لا يرسل أي شيء إلى خارج الجهاز، ويمكن إيقافه بمفتاح lion_auto_learn.
 */
internal class LionFact(
    val key: String,
    val q: String,
    val a: String,
    val src: String,
    val kind: String,
    val at: Long,
    var hits: Int
)

internal class LionKnowledge(
    private val load: () -> String,
    private val save: (String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val MAX_FACTS = 300
        const val MIN_ANSWER = 80
        const val MAX_ANSWER = 1_200
        private const val STALE_MS = 30L * 24 * 3600 * 1000

        private val SECRET = Regex(
            "(?i)(ghp_|gho_|github_pat_|sk-[a-z0-9]|aiza[0-9a-z_-]{10}|xox[bp]-|akia[0-9a-z]{8}|" +
                "bearer\\s+[a-z0-9._-]{16,}|-----begin [a-z ]*private key)"
        )
        private val CONTACT = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+|\\+?\\d[\\d\\s-]{8,}\\d")
        private val PROVIDERS = Regex(
            "(?i)(chatgpt|openai|gpt-?\\d[\\w.-]*|gemini|bard|claude|anthropic|deepseek|qwen|mistral|copilot|" +
                "llama|grok|perplexity|kimi|phind|blackbox|groq|openrouter|cohere)"
        )
        private val SELF_ID = Regex("(?i)(انا|أنا|i am|i'm)\\s+(نموذج|مساعد|ذكاء|a language model|an ai|لغوي)")
        private val CONTEXTUAL = Regex("(السابق|هذا الملف|الملف المرفق|📎|محتوى الملف|رد على ردك)")
        private val REFUSAL = Regex("^\\s*(عذراً|عذرا|آسف|اسف|لا أستطيع|لا استطيع|لا يمكنني|sorry|i can't|i cannot)")
        private val STOP = setOf(
            "في", "من", "على", "علي", "الى", "إلى", "عن", "ان", "أن", "إن", "هو", "هي", "هذا", "هذه", "التي",
            "الذي", "كان", "مع", "كل", "او", "أو", "ثم", "لا", "ما", "ماذا", "كيف", "لماذا", "هل", "ممكن",
            "اريد", "اعطني", "اشرح", "وضح", "the", "and", "for", "with", "that", "this", "from", "are", "how",
            "what", "why", "can", "you", "of", "to", "in", "on", "is", "it", "a", "an"
        )

        fun inMemory(): LionKnowledge {
            var box = ""
            return LionKnowledge({ box }, { box = it })
        }

        fun keywords(text: String): List<String> =
            LionText.words(text).filter { it !in STOP && it.length >= 3 }.distinct()

        fun scrub(answer: String): String =
            answer.substringBefore("\n\n✅").substringBefore("\n\n🧩").substringBefore("\n\n↪️").trim()

        /** سبب الرفض أو null إن كان الزوج صالحاً للتعلّم. */
        fun rejection(q: String, a: String): String? = when {
            q.trim().length < 8 -> "سؤال قصير"
            a.trim().length < MIN_ANSWER -> "جواب قصير"
            a.contains("⚠️") || a.startsWith("❌") -> "جواب خطأ"
            ToolMarkup.hasMarkup(a) || a.contains("[[ASK") -> "بروتوكول أدوات"
            SECRET.containsMatchIn(q) || SECRET.containsMatchIn(a) -> "سر"
            CONTACT.containsMatchIn(q) || CONTACT.containsMatchIn(a) -> "بيانات شخصية"
            CONTEXTUAL.containsMatchIn(q) -> "سؤال مرتبط بسياق خاص"
            PROVIDERS.containsMatchIn(a) || SELF_ID.containsMatchIn(a) -> "اسم مزود"
            REFUSAL.containsMatchIn(a) -> "اعتذار"
            else -> null
        }

        fun trimAnswer(a: String): String {
            if (a.length <= MAX_ANSWER) return a
            val cut = a.take(MAX_ANSWER)
            val end = maxOf(cut.lastIndexOf('.'), cut.lastIndexOf('؟'), cut.lastIndexOf('\n'), cut.lastIndexOf('。'))
            return (if (end > MAX_ANSWER / 2) cut.take(end + 1) else cut).trim()
        }
    }

    private val lock = Any()
    private var facts: MutableList<LionFact>? = null

    private fun all(): MutableList<LionFact> {
        facts?.let { return it }
        val list = mutableListOf<LionFact>()
        runCatching {
            val arr = JSONArray(load().ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list += LionFact(
                    o.optString("k"), o.optString("q"), o.optString("a"), o.optString("s"),
                    o.optString("t", "chat"), o.optLong("at"), o.optInt("h")
                )
            }
        }
        facts = list
        return list
    }

    private fun persist() {
        val arr = JSONArray()
        all().forEach {
            arr.put(JSONObject().put("k", it.key).put("q", it.q).put("a", it.a).put("s", it.src).put("t", it.kind).put("at", it.at).put("h", it.hits))
        }
        runCatching { save(arr.toString()) }
    }

    fun size(): Int = synchronized(lock) { all().size }

    /** يضيف معرفة جديدة بعد الفلترة. يعيد true إن أُضيفت أو حُدّثت. */
    fun add(question: String, answer: String, source: String, kind: String): Boolean {
        val q = question.trim().replace(Regex("\\s+"), " ").take(300)
        val a = trimAnswer(scrub(answer))
        if (rejection(q, a) != null) return false
        val words = keywords(q).take(8)
        if (words.size < 2) return false
        val key = words.sorted().joinToString("|")
        return synchronized(lock) {
            val list = all()
            val existing = list.firstOrNull { it.key == key }
            val now = clock()
            if (existing != null && now - existing.at < STALE_MS) {
                existing.hits++
                persist()
                return@synchronized false
            }
            if (existing != null) list.remove(existing)
            list += LionFact(key, q, a, source, kind, now, 0)
            if (list.size > MAX_FACTS) {
                list.sortWith(compareBy<LionFact> { it.hits }.thenBy { it.at })
                while (list.size > MAX_FACTS) list.removeAt(0)
            }
            persist()
            true
        }
    }

    /** الأنسب للسؤال: يشترط تطابقاً حقيقياً في الكلمات المفتاحية كي لا تُحقن معرفة بعيدة. */
    fun retrieve(query: String, k: Int = 3): List<LionFact> {
        val qw = keywords(query)
        if (qw.isEmpty()) return emptyList()
        val need = minOf(2, qw.size)
        return synchronized(lock) {
            val scored = all().mapNotNull { f ->
                val fq = keywords(f.q).toSet()
                val fa = keywords(f.a).toSet()
                val inQ = qw.count { it in fq }
                val inA = qw.count { it in fa && it !in fq }
                val total = inQ + inA
                if (inQ < 1 || total < need || total.toDouble() / qw.size < 0.5) null
                else f to (inQ * 2.0 + inA + minOf(f.hits, 5) * 0.2)
            }
            val top = scored.sortedByDescending { it.second }.take(k).map { it.first }
            if (top.isNotEmpty()) {
                top.forEach { it.hits++ }
                persist()
            }
            top
        }
    }

    fun block(query: String, maxChars: Int = 1_400): String {
        val found = retrieve(query)
        if (found.isEmpty()) return ""
        val sb = StringBuilder("\n\nمعارف تعلّمتها سابقاً (استعن بها إن وافقت السؤال وتجاهلها إن لم تصلح، ولا تذكر مصدرها):\n")
        for (f in found) {
            val line = "• " + f.q.take(120) + " ← " + f.a.take(450).replace("\n", " ") + "\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        return sb.toString()
    }

    fun exportJson(): String = synchronized(lock) { all(); JSONArray().also { arr ->
        all().forEach { arr.put(JSONObject().put("q", it.q).put("a", it.a).put("source", it.src).put("kind", it.kind)) }
    }.toString() }
}

internal object LionLearn {
    const val PREF_ENABLED = "lion_auto_learn"
    const val PREF_SOURCES = "lion_learn_sources"
    private const val PREF_LAST_INGEST = "lion_learn_last_ingest"
    private const val INGEST_EVERY_MS = 24L * 3600 * 1000
    private const val MAX_PAGES_PER_RUN = 6
    private const val MAX_PASSAGES_PER_PAGE = 12

    /** مصادر افتراضية عامة (جلبها أفضل جهد؛ فشل أي رابط لا يؤثر). */
    val DEFAULT_SOURCES = listOf(
        "https://kotlinlang.org/docs/basic-syntax.html",
        "https://developer.android.com/develop/ui/compose/state",
        "https://docs.github.com/en/actions/about-github-actions/understanding-github-actions",
        "https://kotlinlang.org/docs/coroutines-basics.html",
        "https://developer.android.com/topic/architecture",
        "https://developer.android.com/develop/ui/compose/lists",
        "https://docs.github.com/en/actions/writing-workflows/about-workflows",
        "https://docs.gradle.org/current/userguide/build_environment.html",
        "https://huggingface.co/docs/peft/index",
        "https://huggingface.co/docs/trl/sft_trainer"
    )

    @Volatile private var kb: LionKnowledge? = null

    fun knowledge(): LionKnowledge? {
        kb?.let { return it }
        val ctx = ContextHolder.peek() ?: return null
        val file = File(ctx.filesDir, "lion_learn.json")
        return synchronized(this) {
            kb ?: LionKnowledge(
                { runCatching { file.readText() }.getOrDefault("") },
                { runCatching { file.writeText(it) } }
            ).also { kb = it }
        }
    }

    /** خبرة المسارات تُحفظ على القرص بدل الذاكرة المؤقتة. */
    fun experienceStore(): LionExperience {
        val ctx = ContextHolder.peek() ?: return LionExperience.inMemory()
        val file = File(ctx.filesDir, "lion_experience.json")
        return LionExperience({ runCatching { file.readText() }.getOrDefault("") }, { runCatching { file.writeText(it) } })
    }

    private fun prefsOrNull(): SharedPreferences? =
        runCatching { ContextHolder.peek()?.let { SecurePrefs.get(it) } }.getOrNull()

    fun enabled(prefs: SharedPreferences?): Boolean = prefs?.getBoolean(PREF_ENABLED, true) ?: false

    /** يُستدعى بعد رد ناجح من أي مزود غير Lion-1 في دردشة بلا أدوات. */
    fun observe(prefs: SharedPreferences, question: String, answer: String, provider: String, urls: List<String>): Boolean {
        if (!enabled(prefs) || provider == LionOne.ID) return false
        val store = knowledge() ?: return false
        val src = urls.firstOrNull()?.let { hostOf(it) }.orEmpty().ifBlank { "chat" }
        return store.add(question, answer, src, if (urls.isEmpty()) "chat" else "web")
    }

    /** الكتلة التي تُضاف لتعليمات Lion-1 (فارغة إن كان التعلّم متوقفاً أو لا معرفة مناسبة). */
    fun contextFor(message: String): String {
        val prefs = prefsOrNull() ?: return ""
        if (!enabled(prefs)) return ""
        return runCatching { knowledge()?.block(message).orEmpty() }.getOrDefault("")
    }

    fun userSources(prefs: SharedPreferences): List<String> =
        (prefs.getString(PREF_SOURCES, "") ?: "").lines().map { it.trim() }.filter { isSafeUrl(it) }.distinct().take(20)

    fun isSafeUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = hostOf(url)
        if (host.isBlank() || host == "localhost" || host.endsWith(".local")) return false
        if (Regex("^(10\\.|127\\.|192\\.168\\.|169\\.254\\.|172\\.(1[6-9]|2\\d|3[01])\\.|0\\.)").containsMatchIn(host)) return false
        return true
    }

    fun hostOf(url: String): String = runCatching { URL(url).host.lowercase() }.getOrDefault("")

    fun ingestDue(prefs: SharedPreferences): Boolean =
        enabled(prefs) && System.currentTimeMillis() - prefs.getLong(PREF_LAST_INGEST, 0L) >= INGEST_EVERY_MS

    /** يجلب المصادر مرة كل 24 ساعة بحد أقصى صفحات قليلة وبفواصل زمنية؛ يعيد عدد المعارف الجديدة. */
    fun autoIngest(prefs: SharedPreferences): Int {
        if (!ingestDue(prefs)) return 0
        val store = knowledge() ?: return 0
        prefs.edit().putLong(PREF_LAST_INGEST, System.currentTimeMillis()).apply()
        var added = 0
        // تدوير يومي على كل المصادر (مصادر المستخدم أولاً) حتى تُغطّى القائمة كلها عبر الأيام.
        val pool = (userSources(prefs) + DEFAULT_SOURCES).distinct()
        val start = if (pool.isEmpty()) 0 else ((System.currentTimeMillis() / INGEST_EVERY_MS) % pool.size).toInt()
        val picked = (pool.drop(start) + pool.take(start)).take(MAX_PAGES_PER_RUN)
        for (url in picked) {
            val page = runCatching { fetchPage(url) }.getOrNull() ?: continue
            for (p in passages(page.second).take(MAX_PASSAGES_PER_PAGE)) {
                val q = page.first + " — " + p.take(110)
                if (store.add(q, p, hostOf(url), "source")) added++
            }
            runCatching { Thread.sleep(1_000) }
        }
        return added
    }

    private fun fetchPage(url: String): Pair<String, String>? {
        if (!isSafeUrl(url)) return null
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 10_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LionAI-Learner/1.0")
            setRequestProperty("Accept", "text/html,text/plain")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val type = conn.contentType.orEmpty().lowercase()
            if (!type.contains("html") && !type.contains("text")) return null
            val raw = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8_192)
                while (out.size() < 300_000) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }.toString(Charsets.UTF_8)
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(raw)?.groupValues?.get(1)?.let { decode(it).trim() }.orEmpty()
            val text = htmlToText(raw)
            return if (text.length < 200) null else title.ifBlank { hostOf(url) } to text
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(s: String): String = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace(Regex("\\s+"), " ")

    fun htmlToText(html: String): String {
        var t = html
        for (tag in listOf("script", "style", "nav", "footer", "header", "aside", "noscript", "svg", "form")) {
            t = t.replace(Regex("(?is)<$tag\\b.*?</$tag>"), " ")
        }
        t = t.replace(Regex("(?i)</(p|div|li|h[1-6]|tr|br)>|<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), " ")
        return decode(t.replace("\n", " ¶ ")).replace(" ¶ ", "\n").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** مقاطع ~700 حرف من فقرات حقيقية (ليست قوائم قصيرة). */
    fun passages(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (line in text.lines()) {
            if (line.length < 60) continue
            if (cur.length + line.length > 800 && cur.isNotEmpty()) { out += cur.toString().trim(); cur.clear() }
            cur.append(line).append(' ')
        }
        if (cur.length >= 120) out += cur.toString().trim()
        return out.filter { it.length >= LionKnowledge.MIN_ANSWER }
    }
}
