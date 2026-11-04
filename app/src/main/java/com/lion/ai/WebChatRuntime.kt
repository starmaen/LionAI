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
        LlmClient.reloadOverrides(prefs)
        val start = System.currentTimeMillis()
        val reply = LlmClient.ask("أجب بكلمة واحدة: تم", "", id, emptyList(),
            systemPrompt = "أنت تختبر اتصال صفحة ويب مع تطبيق LionAI. أجب فقط بكلمة تم.", readTimeoutMs = 90_000)
        val ok = !LlmClient.isErrorReply(reply) && reply.isNotBlank()
        SmartRouter.record(Route(id, "web-session", Channel.WEB), ok, System.currentTimeMillis() - start)
        return (if (ok) "✅ " else "❌ ") + providerInfo(id).shortName + ": " + reply.take(240)
    }
}

val ProviderInfo.supportsAppChat: Boolean
    get() = group == ProviderGroup.API || group == ProviderGroup.LION || group == ProviderGroup.WEB
