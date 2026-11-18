package com.lion.ai

import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * صفحات ويب يضيفها المستخدم بالرابط وحده — بلا مفتاح وبلا تعديل للكود.
 *
 * مزود الويب في هذا التطبيق لا يحتاج أكثر من [ProviderInfo] فيه `webviewUrl`: فمجموعته تُشتق
 * منه ([ProviderGroup.WEB])، والجسر يبني محدداته لكل مزود ويب في [WebSiteAdapters.all]،
 * و[WebChatRuntime] يوجّهه إلى جلسة WebView، وبطاقة «Lion Chat» تعرضه للفحص والمهلة و
 * «محددات المطور». لذلك تكفي إضافته إلى [PROVIDERS] ليُربط تلقائياً بكل الطبقات: الإعدادات،
 * نافذة اختيار المزود، العقل المركزي، أدوات الجسر، والمحادثة — دون أي قائمة مكرَّرة.
 *
 * المحددات التي تُكتشف من رابط الصفحة تُخزَّن في بطاقة الصفحة نفسها، فيقرأها الجسر قبل
 * المحددات العامة (union): إن تغيّرت الصفحة رجعت تلقائياً إلى المحددات العامة، ولا تُلغى
 * عامةً بما يكتبه المستخدم في «محددات المطور» (فهو الذي يستبدل القائمة أخيراً).
 */
object UserWebSites {
    const val KEY = "user_web_sites_json"
    const val PREFIX = "web_"
    /** مهلة اكتمال الرد لصفحة غير معروفة: أطول قليلاً من الافتراضي لأن المحررات غير موحّدة. */
    const val DEFAULT_SETTLE_MS = 3000
    private const val MAX_HTML_CHARS = 600_000

    data class Entry(
        val id: String,
        val label: String,
        val url: String,
        /** نطاقات إضافية يقبلها الجسر (تحويلات الصفحة مثل www → chat). */
        val hosts: List<String> = emptyList(),
        /** محددات وُجدت في HTML الصفحة؛ فارغ = يعتمد على المحددات العامة. */
        val input: String = "",
        val send: String = "",
        val stop: String = "",
        val answer: String = "",
        val settleMs: Int = DEFAULT_SETTLE_MS
    )

    /** ما يُستخرج من HTML الصفحة الخام (بلا JavaScript): اسمها ومحددات محررها. */
    data class Detected(
        val title: String = "",
        val input: String = "",
        val send: String = "",
        val stop: String = "",
        val answer: String = ""
    ) {
        val isEmpty: Boolean
            get() = title.isBlank() && input.isBlank() && send.isBlank() && stop.isBlank() && answer.isBlank()

        /** نص قصير للواجهة: ما وُجد فعلاً من المحددات. */
        fun summary(): String = listOf(
            "حقل" to input, "إرسال" to send, "إيقاف" to stop, "رد" to answer
        ).filter { it.second.isNotBlank() }.joinToString(" · ") { "${it.first}: ${it.second}" }
    }

    @Volatile private var cacheRaw: String? = null
    @Volatile private var cacheEntries: List<Entry> = emptyList()
    @Volatile private var cacheInfos: List<ProviderInfo> = emptyList()
    private val lock = Any()

    // ───────────── دوال نقية ─────────────

    /** اسم قصير صالح كمعرّف: حروف إنجليزية وأرقام فقط (مثل UserPlatforms.slug). */
    fun slug(name: String): String {
        val s = name.trim().lowercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
            .joinToString("").replace(Regex("-+"), "-").trim('-')
        return s.take(30)
    }

    fun hostOf(url: String): String = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")

    /**
     * ينظّف رابط الصفحة الملصوق: يضيف https:// الناقصة، ويحذف المسافات والاقتباسات والشرائط
     * والرقم المرجعي (#)، ويبقي المسار والاستعلام (بعض الصفحات تحتاجهما).
     * يرفض ما لا يستطيع الجسر العمل عليه: غير https، أو نطاق بلا نقطة، أو رابط فيه اسم مستخدم.
     */
    fun normalizeUrl(raw: String): String {
        var url = raw.trim().trim('"', '\'', '<', '>', '`').trim()
        require(url.isNotBlank()) { "ألصق رابط صفحة الدردشة (مثل: https://deepai.org/chat)" }
        if (!url.contains("://")) url = "https://$url"
        url = url.substringBefore('#').trim()
        val schemeEnd = url.indexOf("://")
        require(schemeEnd > 0) { "الرابط غير مفهوم" }
        val scheme = url.substring(0, schemeEnd).lowercase()
        require(scheme == "https") { "الجسر يعمل على الصفحات الآمنة فقط — ابدأ الرابط بـ https://" }
        val rest = url.substring(schemeEnd + 3)
        val authority = rest.substringBefore('/').substringBefore('?')
        require(authority.isNotBlank() && authority.contains('.')) { "الرابط بلا اسم نطاق صحيح" }
        require(!authority.contains('@')) { "الرابط لا يجب أن يحتوي اسم مستخدم أو كلمة مرور" }
        val port = authority.substringAfter(':', "")
        require(port.isEmpty() || port.all { it.isDigit() }) { "منفذ غير صالح في الرابط" }
        return url.trimEnd('/')
    }

    /** معرّف فريد: نطاق الصفحة أولاً (اسم المستخدم قد يكون عربياً)، مع لاحقة رقمية عند التكرار. */
    fun idFor(taken: Set<String>, label: String, url: String): String {
        val base = slug(hostOf(url).removePrefix("www.")).ifBlank { slug(label) }.ifBlank { "site" }
        var id = PREFIX + base
        var n = 2
        while (id in taken) { id = "$PREFIX$base-${n++}" }
        return id
    }

    fun parse(raw: String): List<Entry> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").trim()
                val url = o.optString("url").trim()
                if (id.isBlank() || url.isBlank()) return@mapNotNull null
                val hosts = o.optJSONArray("hosts")
                    ?.let { a -> (0 until a.length()).map { a.optString(it).trim().lowercase() } }
                    .orEmpty().filter { it.isNotBlank() }.distinct()
                Entry(
                    id = id,
                    label = o.optString("label", id).ifBlank { id },
                    url = url,
                    hosts = hosts,
                    input = o.optString("input").trim(),
                    send = o.optString("send").trim(),
                    stop = o.optString("stop").trim(),
                    answer = o.optString("answer").trim(),
                    settleMs = o.optInt("settleMs", DEFAULT_SETTLE_MS)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun serialize(entries: List<Entry>): String {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject().put("id", it.id).put("label", it.label).put("url", it.url)
                    .put("hosts", JSONArray(it.hosts)).put("settleMs", it.settleMs)
                    .put("input", it.input).put("send", it.send)
                    .put("stop", it.stop).put("answer", it.answer)
            )
        }
        return array.toString()
    }

    /** بطاقة المزود التي تُدمج في [PROVIDERS]: webviewUrl وحده يجعلها دردشة ويب كاملة. */
    fun toInfo(e: Entry) = ProviderInfo(
        id = e.id,
        label = e.label,
        tagline = "صفحة ويب أضفتها (${e.displayHost()}) — يقودها جسر التطبيق",
        docsUrl = e.url,
        defaultModel = "default",
        free = true,
        shortName = e.label,
        modelHint = "تُفتح داخل التطبيق، فيكتب فيها رسالتك ثم يقرأ ردّه الجديد. من بطاقة «Lion Chat»: " +
            "فحص الصفحة، وضبط المهلة، و«محددات المطور» لتعديل حقل الكتابة وزر الإرسال والرد، و«اختبار الأدوات» " +
            "لدورة الأدوات الكاملة. سجّل الدخول داخل «الجلسة» إن طلبته الصفحة.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = e.url
    )

    private fun Entry.displayHost(): String = hostOf(url).removePrefix("www.").ifBlank { url }

    // ───────────── كشف محددات الصفحة من HTML ─────────────

    private val TAG = Regex("(?is)<(textarea|button|input)\\b([^>]*)>")
    private val ATTR = Regex("(?i)([a-zA-Z_:][-a-zA-Z0-9_.:]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")
    private val SAFE_ID = Regex("[A-Za-z_][A-Za-z0-9_-]*")
    private val INPUT_HINT = Regex("(?i)(message|prompt|chat|ask|compos|query|تفاعل|رسالة)")
    private val SEND_HINT = Regex("(?i)(send|submit)")
    private val SEND_REJECT = Regex("(?i)stop|cancel|regenerat|retry|preview|feedback|newsletter|subscribe|search|login|sign|upload|attach|copy|edit|delete|share|voice|mic|model|preset")
    private val STOP_HINT = Regex("(?i)(stop|cancel|abort)")
    private val SKIP_INPUT_TYPE = setOf("file", "hidden", "checkbox", "radio", "number", "search", "password", "email", "submit", "button")
    private val ANSWER_MARKERS = listOf(
        Regex("(?i)data-message-author-role\\s*=\\s*[\"']?assistant") to "[data-message-author-role='assistant']",
        Regex("(?i)data-message-role\\s*=\\s*[\"']?assistant") to "[data-message-role='assistant']",
        Regex("(?i)data-testid\\s*=\\s*[\"']?assistant-message") to "[data-testid='assistant-message']",
        Regex("(?i)\\boutputBox\\b") to ".outputBox",
        Regex("(?i)\\bmodel-response\\b") to "model-response .model-response-text"
    )

    internal fun attrsOf(tagText: String): Map<String, String> =
        ATTR.findAll(tagText).associate { m ->
            val value = m.groupValues[3].ifEmpty { m.groupValues[4] }.ifEmpty { m.groupValues[5] }
            m.groupValues[1].lowercase() to decodeEntities(value)
        }

    internal fun decodeEntities(text: String): String = text
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
        .replace(Regex("(?i)&#x([0-9a-f]+);")) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }
        .replace(Regex("\\s+"), " ").trim()

    /** `#id` إن كان الاسم آمناً في CSS، وإلا محدد سمة. */
    internal fun idSelector(id: String): String =
        if (id.matches(SAFE_ID)) "#$id" else "[id='${id.replace("'", "\\'")}']"

    /** أفضل‑جهد من HTML الصفحة: عنوانها ومحددات المحرر والإرسال والإيقاف والرد. */
    fun detect(html: String): Detected {
        if (html.isBlank()) return Detected()
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
            ?.let { decodeEntities(it) }?.take(60).orEmpty()
        var input = ""
        var inputScore = 0
        var send = ""
        var stop = ""
        for (m in TAG.findAll(html)) {
            val tag = m.groupValues[1].lowercase()
            val a = attrsOf(m.value)
            if (a.containsKey("disabled") || a.containsKey("readonly") || a.containsKey("aria-hidden")) continue
            val selector = a["id"]?.takeIf { it.isNotBlank() }?.let { idSelector(it) } ?: continue
            val key = listOfNotNull(a["id"], a["class"], a["data-testid"], a["data-test-id"], a["name"], a["placeholder"])
                .joinToString(" ")
            when (tag) {
                "textarea" -> {
                    // placeholder سمة محرر الكتابة وحدها: فقاعات الحوار textarea بلا نصّ إرشادي.
                    val score = (if (INPUT_HINT.containsMatchIn(key)) 1 else 0) +
                        (if (INPUT_HINT.containsMatchIn(a["placeholder"].orEmpty())) 1 else 0)
                    if (score > inputScore) { inputScore = score; input = selector }
                }
                "input" -> {
                    val type = (a["type"] ?: "text").lowercase()
                    val score = (if (INPUT_HINT.containsMatchIn(key)) 1 else 0) +
                        (if (INPUT_HINT.containsMatchIn(a["placeholder"].orEmpty())) 1 else 0)
                    if (type !in SKIP_INPUT_TYPE && score > 0 && score > inputScore) {
                        inputScore = score
                        input = "$selector[type='$type']"
                    }
                }
                "button" -> {
                    val type = (a["type"] ?: "button").lowercase()
                    val spoken = "${a["aria-label"].orEmpty()} ${a["title"].orEmpty()}"
                    when {
                        STOP_HINT.containsMatchIn(key) && stop.isBlank() -> stop = selector
                        type != "submit" && SEND_HINT.containsMatchIn(key) &&
                            !SEND_REJECT.containsMatchIn("$key $spoken") && send.isBlank() -> send = selector
                    }
                }
            }
        }
        val answer = ANSWER_MARKERS.firstOrNull { it.first.containsMatchIn(html) }?.second.orEmpty()
        return Detected(title, input, send, stop, answer)
    }

    // ───────────── الشبكة (من خيط خلفي فقط) ─────────────

    data class Fetched(val html: String, val finalUrl: String) {
        val finalHost: String get() = hostOf(finalUrl)
    }

    /** يجلب HTML الصفحة لقراءته بلا JavaScript. يرمي عند الفشل؛ المستدعي الخلفي يتجاهله. */
    fun fetch(url: String): Fetched {
        val clean = normalizeUrl(url)
        val conn = (URL(clean).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
            setRequestProperty("User-Agent", LLM_USER_AGENT)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return Fetched(readCapped(conn), conn.url.toString())
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(conn: HttpURLConnection): String =
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val sb = StringBuilder()
            val buf = CharArray(8192)
            while (sb.length < MAX_HTML_CHARS) {
                val read = reader.read(buf, 0, minOf(buf.size, MAX_HTML_CHARS - sb.length))
                if (read <= 0) break
                sb.append(buf, 0, read)
            }
            sb.toString()
        }

    // ───────────── التخزين ─────────────

    fun list(prefs: SharedPreferences): List<Entry> = parse(prefs.getString(KEY, "") ?: "")

    private fun prefsOrNull(): SharedPreferences? =
        ContextHolder.peek()?.let { runCatching { SecurePrefs.get(it) }.getOrNull() }

    /** القائمة من نسخة مخزَّنة واحدة لكل عملية؛ تُقرأ من [PROVIDERS] عند كل وصول. */
    private fun cachedEntries(): List<Entry> {
        val prefs = prefsOrNull() ?: return emptyList()
        val raw = runCatching { prefs.getString(KEY, "") ?: "" }.getOrDefault("")
        if (raw == cacheRaw) return cacheEntries
        val entries = parse(raw)
        cacheEntries = entries
        cacheInfos = entries.map { toInfo(it) }
        cacheRaw = raw
        return entries
    }

    /** تُستدعى من [PROVIDERS]: آمنة في اختبارات JVM (تعيد قائمة فارغة بلا سياق). */
    fun infos(): List<ProviderInfo> {
        if (prefsOrNull() == null) return emptyList()
        cachedEntries()
        return cacheInfos
    }

    /** بطاقات الصفحات المضافة يدوياً (فارغة بلا سياق أندرويد: اختبارات JVM). */
    fun entries(): List<Entry> = cachedEntries()

    /** بطاقة صفحة مضافة يدوياً (نطاقاتها ومهلةها ومحدداتها المكتشفة)؛ null لغيرها. */
    fun byId(id: String): Entry? =
        if (!id.startsWith(PREFIX)) null else entries().firstOrNull { it.id == id }

    /** يضيف صفحة؛ الاسم اختياري (يُؤخذ من النطاق، ثم من عنوان الصفحة عند الكشف). */
    fun add(prefs: SharedPreferences, name: String, url: String): Entry {
        val clean = normalizeUrl(url)
        require(hostOf(clean).isNotBlank()) { "الرابط بلا اسم نطاق" }
        val label = name.trim().ifBlank { hostOf(clean).removePrefix("www.") }
        return synchronized(lock) {
            val existing = list(prefs)
            val covered = WebSiteAdapters.byHost(clean)?.takeIf { !it.id.startsWith(PREFIX) }
            require(covered == null) {
                "هذه الصفحة مضافة أصلاً باسم «${providerInfo(covered!!.id).shortName}» — فعّلها من بطاقة «Lion Chat» بدل إضافة نسخة."
            }
            val entry = Entry(idFor(existing.map { it.id }.toSet(), label, clean), label, clean)
            prefs.edit().putString(KEY, serialize(existing + entry)).apply()
            cacheRaw = null
            if (ContextHolder.peek() != null) detectAsync(prefs, entry.id, entry.url, entry.label)
            entry
        }
    }

    /** حذف نهائي: بطاقة الصفحة وإعدادات الجسر والمهلة والمحددات الخاصة بها. */
    fun remove(prefs: SharedPreferences, id: String) {
        synchronized(lock) {
            val kept = list(prefs).filterNot { it.id == id }
            val editor = prefs.edit().putString(KEY, serialize(kept))
            listOf(
                "enabled_$id", "model_$id", "api_key_$id", "endpoint_$id",
                "web_chat_enabled_$id", "web_timeout_$id", "web_session_verified_$id",
                "web_session_status_$id", "web_session_dom_$id"
            ).forEach { editor.remove(it) }
            runCatching { prefs.all.keys }.getOrDefault(emptySet())
                .filter { it.startsWith("${WebSiteAdapters.DOM_PREFIX}${id}_") }
                .forEach { editor.remove(it) }
            editor.apply()
            cacheRaw = null
        }
    }

    /** يفعّل الصفحة في جسر جلسات المواقع أو يلغي تفعيلها (المفتاح العام لا يُطفأ تلقائياً). */
    fun setBridgeEnabled(prefs: SharedPreferences, id: String, enabled: Boolean) {
        prefs.edit()
            .putBoolean(WebChatRuntime.ENABLED, prefs.getBoolean(WebChatRuntime.ENABLED, false) || enabled)
            .putBoolean("web_chat_enabled_$id", enabled)
            .apply()
    }

    /**
     * يحفظ ما كُشف في بطاقة الصفحة: المحددات الفارغة لا تُمس، ويُضاف نطاق التحويل النهائي،
     * ويتبنّى عنوان الصفحة بديلاً عن اسم النطاق إن لم يكتب المستخدم اسماً.
     */
    fun storeDetection(prefs: SharedPreferences, id: String, d: Detected, finalHost: String = "", hostLabel: String = "") {
        if (d.isEmpty && finalHost.isBlank()) return
        synchronized(lock) {
            val all = list(prefs)
            val target = all.firstOrNull { it.id == id } ?: return
            val extra = listOfNotNull(finalHost.takeIf { it.isNotBlank() && it != hostOf(target.url) })
            val label = d.title.takeIf { it.isNotBlank() && target.label == hostLabel } ?: target.label
            val updated = all.map {
                if (it.id != id) it else it.copy(
                    label = label.ifBlank { it.label },
                    hosts = (it.hosts + extra).distinct(),
                    input = d.input.ifBlank { it.input },
                    send = d.send.ifBlank { it.send },
                    stop = d.stop.ifBlank { it.stop },
                    answer = d.answer.ifBlank { it.answer }
                )
            }
            prefs.edit().putString(KEY, serialize(updated)).apply()
            cacheRaw = null
        }
    }

    /**
     * كشف خلفي عند الإضافة: عنوان الصفحة ومحدداتها ونطاق التحويل النهائي.
     * لا شيء منه إلزامي — عند فشل الشبكة تبقى الصفحة بالمحددات العامة وتُربط كبقية المواقع.
     */
    fun detectAsync(prefs: SharedPreferences, id: String, url: String, hostLabel: String = "") {
        val thread = Thread {
            runCatching {
                val f = fetch(url)
                storeDetection(prefs, id, detect(f.html), f.finalHost, hostLabel)
            }
        }
        thread.isDaemon = true
        thread.start()
    }
}
