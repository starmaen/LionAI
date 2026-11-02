package com.lion.ai

import android.content.SharedPreferences

/** Web-page identities are independent of their explicitly configured inference backend.
 * Never infer an API endpoint or credentials from a browser URL/cookie.
 * The local proxy uses this same runtime; this runtime never calls the local proxy.
 */
object WebChatRuntime {
    const val HUB = "lion_chat"
    const val CENTRAL = "web_chat_central"
    @Volatile private var settings: SharedPreferences? = null
    fun load(prefs: SharedPreferences) { settings = prefs }
    fun prefs(): SharedPreferences? = settings
    fun isWeb(id: String): Boolean = PROVIDERS.any { it.id == id && it.group == ProviderGroup.WEB }
    fun handles(id: String): Boolean = id == HUB || isWeb(id)
    fun enabled(prefs: SharedPreferences, id: String): Boolean =
        prefs.getBoolean("web_chat_enabled", false) &&
            (id == HUB || prefs.getBoolean("web_chat_enabled_$id", false))
    fun model(prefs: SharedPreferences, id: String): String =
        prefs.getString("web_chat_model_$id", "").orEmpty().trim()
    fun validModel(model: String): Boolean =
        model.contains('/') && model.substringBefore('/').matches(Regex("[a-z0-9_.-]{1,32}")) &&
            model.substringAfter('/').isNotBlank() && model.none { it.isWhitespace() }
    fun configured(prefs: SharedPreferences, id: String): Boolean =
        enabled(prefs, id) && validModel(model(prefs, id))
    fun members(prefs: SharedPreferences): List<ProviderInfo> =
        PROVIDERS.filter { it.group == ProviderGroup.WEB && providerEnabled(prefs, it.id) && configured(prefs, it.id) }
    fun available(prefs: SharedPreferences, id: String): Boolean {
        val gateway = WebGateway.load(prefs)
        if (!gateway.enabled || !gateway.configured || gateway.token.isBlank() ||
            gateway.mode == WebGateway.MODE_OFF || !RoutePolicy.channelEnabled(prefs, Channel.GATEWAY)) return false
        return if (id == HUB) enabled(prefs, HUB) && members(prefs).isNotEmpty() else providerEnabled(prefs, id) && configured(prefs, id)
    }
    fun routes(prefs: SharedPreferences): List<Route> =
        if (!prefs.getBoolean(CENTRAL, true)) emptyList() else members(prefs)
            .filter { available(prefs, it.id) && providerEnabled(prefs, it.id) }
            .map { Route(it.id, model(prefs, it.id)) }

    /** Tests use exactly the chat request path, not an HTTP page-load check. */
    fun test(prefs: SharedPreferences, id: String): String {
        LlmClient.reloadOverrides(prefs)
        val start = System.currentTimeMillis()
        val reply = LlmClient.ask("أجب بكلمة واحدة: تم", "", id, emptyList(), readTimeoutMs = 15_000)
        val ok = !LlmClient.isErrorReply(reply) && reply.isNotBlank()
        SmartRouter.record(Route(id, model(prefs, id)), ok, System.currentTimeMillis() - start)
        return (if (ok) "✅ " else "❌ ") + providerInfo(id).shortName + " → " +
            model(prefs, id) + ": " + reply.take(220)
    }
}

/** Chat capability must not be confused with the visual provider category. */
val ProviderInfo.supportsAppChat: Boolean
    get() = group == ProviderGroup.API || group == ProviderGroup.LION || group == ProviderGroup.WEB
