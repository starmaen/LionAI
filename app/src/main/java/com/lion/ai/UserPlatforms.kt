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

    // ───────────── دوال نقية ─────────────

    fun slug(name: String): String {
        val s = name.trim().lowercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
            .joinToString("").replace(Regex("-+"), "-").trim('-')
        return s.take(30)
    }

    fun parseModels(text: String): List<String> =
        text.split(',', '\n', '،').map { it.trim() }.filter { it.isNotBlank() }.distinct()

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
        val url = baseUrl.trim()
        require(url.isNotBlank()) { "أدخل Base URL" }
        require(LlmClient.isSafeEndpoint(url)) { "الرابط يجب أن يبدأ بـ https:// (أو http:// لشبكة محلية)" }
        val id = PREFIX + s
        val existing = list(prefs)
        require(existing.none { it.id == id }) { "منصة بهذا الاسم موجودة، احذفها أولاً أو غيّر الاسم" }
        val entry = Entry(id, label, url, parseModels(models), keyless = apiKey.isBlank())
        val editor = prefs.edit().putString(KEY, serialize(existing + entry))
        if (apiKey.isNotBlank()) editor.putString("api_key_$id", apiKey.trim())
        editor.apply()
        cacheRaw = null
        return entry
    }

    fun remove(prefs: SharedPreferences, id: String) {
        val kept = list(prefs).filterNot { it.id == id }
        prefs.edit()
            .putString(KEY, serialize(kept))
            .remove("api_key_$id").remove("model_$id").remove("enabled_$id").remove("endpoint_$id")
            .apply()
        cacheRaw = null
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

    /** يجلب موديلات المنصة من {base}/models (استدعِه من خيط خلفي). */
    fun fetchModels(baseUrl: String, apiKey: String): List<String> {
        val base = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
        require(LlmClient.isSafeEndpoint(base)) { "الرابط يجب أن يبدأ بـ https:// (أو http:// لشبكة محلية)" }
        val conn = (URL("$base/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(code in 200..299) { "HTTP $code: ${text.take(120)}" }
            val ids = parseModelIds(text)
            require(ids.isNotEmpty()) { "لم تُرجع المنصة قائمة موديلات" }
            return ids
        } finally {
            conn.disconnect()
        }
    }
}
