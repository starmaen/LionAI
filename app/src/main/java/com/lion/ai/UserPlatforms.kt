package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * منصات يضيفها المستخدم بنفسه (واجهة متوافقة مع OpenAI: Base URL + مفتاح + موديلات).
 * تُحفظ في prefs وتُدمج ديناميكياً في [PROVIDERS] فتظهر في الإعدادات والمنتقي وتعمل عبر
 * فرع OpenAI-Compatible العام في LlmClient. المفتاح يُحفظ في api_key_<id> كباقي المزودين.
 */
object UserPlatforms {
    const val KEY = "user_platforms_json"
    const val PREFIX = "usr_"

    data class Entry(val id: String, val label: String, val baseUrl: String, val models: List<String>, val keyless: Boolean = false)

    @Volatile private var cacheRaw: String? = null
    @Volatile private var cacheInfos: List<ProviderInfo> = emptyList()
    private val lock = Any()

    /** مقاطع تُلصق عادةً من متصفح لوحة التحكم وليست جزءاً من واجهة API. */
    private val UI_SEGMENTS = setOf(
        "dashboard", "console", "login", "signin", "sign-in", "signup", "account",
        "keys", "api-keys", "settings", "panel", "portal", "playground", "billing"
    )

    // ───────────── دوال نقية ─────────────

    fun slug(name: String): String {
        val s = name.trim().lowercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
            .joinToString("").replace(Regex("-+"), "-").trim('-')
        return s.take(30)
    }

    fun parseModels(text: String): List<String> =
        text.split(',', '\n', '،').map { it.trim() }.filter { it.isNotBlank() }.distinct()

    /** ينظّف Base URL الملصوق: المسافات والاقتباس والشرطة الأخيرة والـ query ومقاطع لوحة التحكم (…/dashboard). */
    fun sanitizeBaseUrl(raw: String): String {
        var url = raw.trim().trim('"', '\'').substringBefore('?').substringBefore('#').trimEnd('/')
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        var changed = true
        while (changed) {
            changed = false
            val path = url.substring(schemeEnd + 3)
            if (path.indexOf('/') < 0) break
            val last = path.substringAfterLast('/').lowercase()
            if (last in UI_SEGMENTS) {
                url = url.substringBeforeLast('/').trimEnd('/')
                changed = true
            }
        }
        return url
    }

    /** مسارات جلب الموديلات المحتملة: الأول بنفس تطبيع /v1 الذي تستخدمه المحادثة. */
    internal fun modelsUrls(baseUrl: String): List<String> {
        val base = sanitizeBaseUrl(baseUrl).removeSuffix("/chat/completions").trimEnd('/')
        val normalized = LlmClient.normalizeCustomUrl(base).removeSuffix("/chat/completions").trimEnd('/')
        return listOf("$normalized/models", "$base/models").distinct()
    }

    fun parse(raw: String): List<Entry> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").trim()
                val url = o.optString("baseUrl").trim()
                if (id.isBlank() || url.isBlank()) return@mapNotNull null
                val models = o.optJSONArray("models")?.let { m -> (0 until m.length()).map { m.optString(it) } }.orEmpty()
                Entry(id, o.optString("label", id).ifBlank { id }, url, models.filter { it.isNotBlank() }, o.optBoolean("keyless", false))
            }
        }.getOrDefault(emptyList())
    }

    fun serialize(entries: List<Entry>): String {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject().put("id", it.id).put("label", it.label).put("baseUrl", it.baseUrl)
                    .put("models", JSONArray(it.models)).put("keyless", it.keyless)
            )
        }
        return array.toString()
    }

    fun toInfo(e: Entry) = ProviderInfo(
        id = e.id,
        label = e.label,
        tagline = "منصة أضفتها (متوافقة مع OpenAI)",
        docsUrl = "",
        defaultModel = e.models.firstOrNull() ?: "default",
        free = false,
        shortName = e.label,
        modelHint = "الموديلات من القائمة التي أدخلتها عند إضافة المنصة.",
        models = e.models,
        keyless = e.keyless,
        endpoint = e.baseUrl
    )

    // ───────────── التخزين ─────────────

    fun list(prefs: SharedPreferences): List<Entry> = parse(prefs.getString(KEY, "") ?: "")

    /** يُستدعى من [PROVIDERS]: آمن في اختبارات JVM (يعيد قائمة فارغة بلا سياق). */
    fun infos(): List<ProviderInfo> {
        val prefs = runCatching { ContextHolder.peek()?.let { SecurePrefs.get(it) } }.getOrNull() ?: return emptyList()
        val raw = runCatching { prefs.getString(KEY, "") ?: "" }.getOrDefault("")
        if (raw == cacheRaw) return cacheInfos
        val infos = parse(raw).map { toInfo(it) }
        cacheInfos = infos
        cacheRaw = raw
        return infos
    }

    /** يضيف منصة ويحفظ مفتاحها؛ يرمي IllegalArgumentException برسالة عربية عند خطأ الإدخال. */
    fun add(prefs: SharedPreferences, name: String, baseUrl: String, apiKey: String, models: String): Entry {
        val label = name.trim()
        require(label.isNotBlank()) { "أدخل اسم المنصة" }
        val s = slug(label)
        require(s.isNotBlank()) { "الاسم يجب أن يحوي حروفاً إنجليزية أو أرقاماً" }
        val url = sanitizeBaseUrl(baseUrl)
        require(url.isNotBlank()) { "أدخل Base URL" }
        require(LlmClient.isSafeEndpoint(url)) { "الرابط يجب أن يبدأ بـ https:// (أو http:// لشبكة محلية)" }
        val id = PREFIX + s
        return synchronized(lock) {
            val existing = list(prefs)
            require(existing.none { it.id == id }) { "منصة بهذا الاسم موجودة، احذفها أولاً أو غيّر الاسم" }
            val entry = Entry(id, label, url, parseModels(models), keyless = apiKey.isBlank())
            val editor = prefs.edit().putString(KEY, serialize(existing + entry))
            if (apiKey.isNotBlank()) editor.putString("api_key_$id", apiKey.filterNot { it.isWhitespace() })
            editor.apply()
            cacheRaw = null
            // بلا موديلات مكتوبة: اجلبها تلقائياً في الخلفية (لا شبكة في اختبارات JVM لغياب السياق).
            if (entry.models.isEmpty() && ContextHolder.peek() != null) {
                autoFillModelsAsync(prefs, id, url, apiKey)
            }
            entry
        }
    }

    fun remove(prefs: SharedPreferences, id: String) {
        synchronized(lock) {
            val kept = list(prefs).filterNot { it.id == id }
            prefs.edit()
                .putString(KEY, serialize(kept))
                .remove("api_key_$id").remove("model_$id").remove("enabled_$id").remove("endpoint_$id")
                .apply()
            cacheRaw = null
        }
    }

    /**
     * يحفظ الموديلات المجلوبة داخل المنصة إن لم تكن لها قائمة، فيصير الموديل الافتراضي
     * موديلاً حقيقياً بدل «default» (الذي ترفضه أغلب المنصات).
     */
    fun storeModels(prefs: SharedPreferences, id: String, models: List<String>) {
        if (models.isEmpty()) return
        synchronized(lock) {
            val all = list(prefs)
            val target = all.firstOrNull { it.id == id } ?: return
            if (target.models.isNotEmpty()) return
            val updated = all.map { if (it.id == id) it.copy(models = models.take(200)) else it }
            prefs.edit().putString(KEY, serialize(updated)).apply()
            cacheRaw = null
        }
    }

    /** يجلب موديلات منصة جديدة في خيط خلفي ويحفظها (لا شبكة على الخيط الرئيسي). */
    fun autoFillModelsAsync(prefs: SharedPreferences, id: String, baseUrl: String, apiKey: String) {
        val thread = Thread {
            runCatching { fetchModels(baseUrl, apiKey) }.onSuccess { storeModels(prefs, id, it) }
        }
        thread.isDaemon = true
        thread.start()
    }

    /** يستخرج معرّفات الموديلات من رد /models (صيغة OpenAI {data:[{id}]} أو مصفوفة). */
    internal fun parseModelIds(text: String): List<String> {
        val t = text.trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else (JSONObject(t).optJSONArray("data") ?: JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.opt(i)) {
                is JSONObject -> v.optString("id").ifBlank { null }
                is String -> v.ifBlank { null }
                else -> null
            }
        }.distinct().sorted()
    }

    /** يجلب موديلات المنصة (استدعِه من خيط خلفي). يجرّب مسار /v1/models أولاً ثم {base}/models. */
    fun fetchModels(baseUrl: String, apiKey: String): List<String> {
        val cleaned = sanitizeBaseUrl(baseUrl)
        require(LlmClient.isSafeEndpoint(cleaned)) { "الرابط يجب أن يبدأ بـ https:// (أو http:// لشبكة محلية)" }
        var firstError: String? = null
        for (url in modelsUrls(cleaned)) {
            try {
                return fetchModelsFrom(url, apiKey)
            } catch (e: Exception) {
                if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
            }
        }
        throw IllegalStateException(firstError ?: "لم تُرجع المنصة قائمة موديلات")
    }

    private fun fetchModelsFrom(url: String, apiKey: String): List<String> {
        val key = apiKey.filterNot { it.isWhitespace() }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", LLM_USER_AGENT)
            if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException(describeHttpError(code, text))
            if (text.trimStart().startsWith("<")) {
                throw IllegalStateException("الرابط يعيد صفحة ويب وليس واجهة API ($url). تأكد أن Base URL ينتهي بـ /v1.")
            }
            val ids = runCatching { parseModelIds(text) }
                .getOrElse { throw IllegalStateException("رد غير مفهوم من $url") }
            if (ids.isEmpty()) throw IllegalStateException("لم تُرجع المنصة قائمة موديلات")
            return ids
        } finally {
            conn.disconnect()
        }
    }

    private fun describeHttpError(code: Int, body: String): String {
        val hint = when (code) {
            401, 403 -> " — المفتاح مرفوض أو المنصة تحجب الطلب؛ تحقق من المفتاح"
            404 -> " — المسار غير موجود؛ تأكد أن Base URL ينتهي بـ /v1"
            else -> ""
        }
        val clean = if (body.trimStart().startsWith("<")) "صفحة HTML" else body.take(120)
        return "HTTP $code$hint: $clean"
    }
}
