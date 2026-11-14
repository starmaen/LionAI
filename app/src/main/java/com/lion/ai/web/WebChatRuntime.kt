package com.lion.ai

import android.content.SharedPreferences

/** Direct browser-session providers. Never route a web identity to an API backend. */
object WebChatRuntime {
    const val HUB = "lion_chat"
    const val CENTRAL = "web_chat_central"
    const val ENABLED = "web_sessions_enabled_v1"
    @Volatile private var settings: SharedPreferences? = null
    fun load(prefs: SharedPreferences) { settings = prefs }
    fun prefs(): SharedPreferences? = settings
    fun isWeb(id: String): Boolean = PROVIDERS.any { it.id == id && it.group == ProviderGroup.WEB }
    fun handles(id: String): Boolean = id == HUB || isWeb(id)
    fun enabled(prefs: SharedPreferences, id: String): Boolean =
        prefs.getBoolean(ENABLED, false) &&
            (id == HUB || prefs.getBoolean("web_chat_enabled_$id", false))
    fun model(prefs: SharedPreferences, id: String): String = if (handles(id)) "web-session" else ""
    fun configured(prefs: SharedPreferences, id: String): Boolean =
        enabled(prefs, id) && WebSiteAdapters.get(id, prefs) != null
    fun members(prefs: SharedPreferences): List<ProviderInfo> =
        PROVIDERS.filter { it.group == ProviderGroup.WEB && providerEnabled(prefs, it.id) && configured(prefs, it.id) }
    fun available(prefs: SharedPreferences, id: String): Boolean =
        providerEnabled(prefs, id) && if (id == HUB) enabled(prefs, HUB) && members(prefs).isNotEmpty()
        else configured(prefs, id)
    // Central auto-routing only uses sites that actually returned a complete response.
    fun routes(prefs: SharedPreferences): List<Route> =
        if (!prefs.getBoolean(CENTRAL, true)) emptyList() else members(prefs)
            .filter { prefs.getBoolean("web_session_verified_${it.id}", false) }
            .map { Route(it.id, model(prefs, it.id), Channel.WEB) }

    fun test(prefs: SharedPreferences, id: String): String {
        if (!isWeb(id)) return "❌ الفحص لموقع واحد فقط — اختر الموقع من زر فحص بجانبه."
        LlmClient.reloadOverrides(prefs)
        val start = System.currentTimeMillis()
        val reply = LlmClient.ask(
            "أجب بكلمة واحدة: تم", "", id, emptyList(),
            systemPrompt = "",
            readTimeoutMs = timeoutMs(prefs, id).toInt()
        )
        val ok = !LlmClient.isErrorReply(reply) && reply.isNotBlank()
        SmartRouter.record(Route(id, "web-session", Channel.WEB), ok, System.currentTimeMillis() - start)
        return (if (ok) "✅ " else "❌ ") + providerInfo(id).shortName + ": " + reply.take(240)
    }

    /** مهلة الرد لكل موقع (ثوانٍ مخزنة في web_timeout_<id>). مواقع التفكير العميق تحتاج أكثر من 90ث،
     *  وتمديد الانتظار بالنشاط (bump) يعمل فوقها بينما الصفحة تكتب فعلاً. */
    const val DEFAULT_TIMEOUT_MS = 90_000L
    const val MIN_TIMEOUT_MS = 15_000L
    const val MAX_TIMEOUT_MS = 300_000L
    fun timeoutMs(prefs: SharedPreferences, id: String): Long {
        if (!handles(id)) return DEFAULT_TIMEOUT_MS
        fun storedSeconds(key: String): Long? = runCatching {
            if (prefs.contains(key)) prefs.getLong(key, -1L) else -1L
        }.getOrNull()?.takeIf { it > 0 }
        val seconds = storedSeconds("web_timeout_$id") ?: storedSeconds("web_timeout_$HUB")
            ?: DEFAULT_TIMEOUT_MS / 1000L
        return (seconds * 1000L).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }
    fun setTimeoutSeconds(prefs: SharedPreferences, id: String, seconds: Long) {
        val clamped = seconds.coerceIn(MIN_TIMEOUT_MS / 1000L, MAX_TIMEOUT_MS / 1000L)
        prefs.edit().putLong("web_timeout_$id", clamped).apply()
    }
    fun timeoutSeconds(prefs: SharedPreferences, id: String): Long = timeoutMs(prefs, id) / 1000L
}

val ProviderInfo.supportsAppChat: Boolean
    get() = group == ProviderGroup.API || group == ProviderGroup.LION || group == ProviderGroup.WEB
