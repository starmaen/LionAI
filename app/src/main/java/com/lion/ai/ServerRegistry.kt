package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * سجلّ الخوادم: مصدر الحقيقة لتبويب «الاتصال».
 *
 * - الخادم المحلي المدمج (داخل التطبيق) و«الخادم الخارجي» المعروف (WebGateway) خادمان مدمجان.
 * - يستطيع المستخدم إضافة أي عدد من الخوادم (محلية مثل Termux/OmniRoute أو خارجية) تتكلم
 *   OpenAI-Compatible وتقبل الموديل بصيغة provider/model.
 * - الاختيار: «مباشر» (بلا خوادم) / «خادم واحد» / «كل الخوادم معاً».
 * - المباشر هو الأولوية افتراضياً؛ الخوادم طرق إضافية عند الحاجة، ولا علاقة لها بوضع المحادثة.
 *
 * التوافق: مفتاح [WebGateway.KEY_MODE] ما زال المفتاح الرئيسي (off = مباشر بلا خوادم)،
 * لذلك لا يتغير سلوك من يستخدمه مباشرة (الاختبارات والشريط السفلي).
 */
object ServerRegistry {
    const val KEY_LIST = "servers_custom_v1"
    const val KEY_SCOPE = "servers_scope"
    const val KEY_LOCAL_ON = "server_on_local"

    const val SCOPE_ONE = "one"
    const val SCOPE_ALL = "all"

    const val BUILTIN_GATEWAY = "builtin_gateway"
    const val BUILTIN_LOCAL = "builtin_local"

    const val KIND_LOCAL = "local"
    const val KIND_EXTERNAL = "external"

    data class Server(
        val id: String,
        val name: String,
        val kind: String,
        val url: String,
        val token: String,
        val enabled: Boolean
    )

    /** نقطة وصول فعلية يستخدمها LlmClient للمرور عبر الخادم. */
    data class Relay(val name: String, val url: String, val token: String)

    fun list(prefs: SharedPreferences): List<Server> {
        val raw = prefs.getString(KEY_LIST, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "")
                if (id.isBlank()) return@mapNotNull null
                Server(
                    id = id,
                    name = o.optString("name", id).ifBlank { id },
                    kind = if (o.optString("kind", KIND_EXTERNAL) == KIND_LOCAL) KIND_LOCAL else KIND_EXTERNAL,
                    url = o.optString("url", ""),
                    token = o.optString("token", ""),
                    enabled = o.optBoolean("enabled", true)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(prefs: SharedPreferences, servers: List<Server>) {
        val arr = JSONArray()
        servers.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("kind", it.kind)
                    .put("url", it.url)
                    .put("token", it.token)
                    .put("enabled", it.enabled)
            )
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).commit()
    }

    fun normalizeUrl(kind: String, raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.isBlank()) return ""
        if (!url.contains("://")) url = (if (kind == KIND_LOCAL) "http://" else "https://") + url
        return WebGateway.normalizeBase(url)
    }

    /** يضيف خادماً (id = null) أو يعدّله. يعيد نص الخطأ أو null عند النجاح. */
    fun upsert(
        prefs: SharedPreferences,
        id: String?,
        name: String,
        kind: String,
        url: String,
        token: String
    ): String? {
        val cleanUrl = normalizeUrl(kind, url)
        if (cleanUrl.isBlank()) return "أدخل رابط الخادم (مثل http://127.0.0.1:20128/v1)."
        val host = cleanUrl.substringAfter("://", "").substringBefore('/')
        if (host.isBlank()) return "الرابط غير صالح."
        val current = list(prefs)
        val cleanToken = token.filterNot { it.isWhitespace() }
        if (id == null) {
            val label = name.trim().ifBlank { "خادم ${current.size + 1}" }
            val fresh = Server(
                id = "srv_" + System.currentTimeMillis().toString(36) + current.size,
                name = label, kind = kind, url = cleanUrl, token = cleanToken,
                enabled = false
            )
            write(prefs, current + fresh)
        } else {
            if (current.none { it.id == id }) return "الخادم غير موجود."
            write(
                prefs,
                current.map {
                    if (it.id == id) it.copy(
                        name = name.trim().ifBlank { it.name },
                        kind = kind, url = cleanUrl, token = cleanToken
                    ) else it
                }
            )
        }
        applyChannels(prefs)
        return null
    }

    fun remove(prefs: SharedPreferences, id: String) {
        write(prefs, list(prefs).filterNot { it.id == id })
        applyChannels(prefs)
    }

    // ---------- الحالة ----------

    fun isOff(prefs: SharedPreferences): Boolean =
        WebGateway.normalizeMode(prefs.getString(WebGateway.KEY_MODE, WebGateway.MODE_OFF)) == WebGateway.MODE_OFF

    fun scope(prefs: SharedPreferences): String =
        if (prefs.getString(KEY_SCOPE, SCOPE_ALL) == SCOPE_ONE) SCOPE_ONE else SCOPE_ALL

    /** الخادم المحلي المدمج: افتراضه ما كانت عليه قناته قبل هذه الميزة. */
    fun localOn(prefs: SharedPreferences): Boolean =
        if (prefs.contains(KEY_LOCAL_ON)) prefs.getBoolean(KEY_LOCAL_ON, false)
        else RoutePolicy.channelEnabled(prefs, Channel.LOCAL)

    fun gatewayOn(prefs: SharedPreferences): Boolean = providerEnabled(prefs, WebGateway.ENABLED_ID)

    fun isOn(prefs: SharedPreferences, id: String): Boolean = when (id) {
        BUILTIN_LOCAL -> localOn(prefs)
        BUILTIN_GATEWAY -> gatewayOn(prefs)
        else -> list(prefs).firstOrNull { it.id == id }?.enabled == true
    }

    /** نقاط الوصول الفعلية الآن (فارغة في «مباشر»). */
    fun relays(prefs: SharedPreferences): List<Relay> {
        if (isOff(prefs)) return emptyList()
        val out = mutableListOf<Relay>()
        val url = (prefs.getString(WebGateway.KEY_URL, "") ?: "").trim()
        val token = (prefs.getString(WebGateway.KEY_TOKEN, "") ?: "").trim().filterNot { it.isWhitespace() }
        if (gatewayOn(prefs) && url.isNotBlank() && token.isNotBlank()) {
            out += Relay("الخادم الخارجي", url, token)
        }
        list(prefs).filter { it.enabled && it.url.isNotBlank() }.forEach {
            out += Relay(it.name, it.url, it.token)
        }
        return if (scope(prefs) == SCOPE_ONE) out.take(1) else out
    }

    // ---------- التعديل ----------

    private fun disableAllExcept(prefs: SharedPreferences, keepId: String) {
        if (keepId != BUILTIN_GATEWAY) setProviderEnabled(prefs, WebGateway.ENABLED_ID, false)
        if (keepId != BUILTIN_LOCAL) prefs.edit().putBoolean(KEY_LOCAL_ON, false).commit()
        val servers = list(prefs)
        if (servers.any { it.id != keepId && it.enabled }) {
            write(prefs, servers.map { if (it.id != keepId) it.copy(enabled = false) else it })
        }
    }

    fun setOn(prefs: SharedPreferences, id: String, on: Boolean) {
        ensureLocalFlag(prefs)
        if (on && scope(prefs) == SCOPE_ONE) disableAllExcept(prefs, id)
        when (id) {
            BUILTIN_LOCAL -> prefs.edit().putBoolean(KEY_LOCAL_ON, on).commit()
            BUILTIN_GATEWAY -> {
                setProviderEnabled(prefs, WebGateway.ENABLED_ID, on)
                prefs.edit().commit()
            }
            else -> write(prefs, list(prefs).map { if (it.id == id) it.copy(enabled = on) else it })
        }
        applyChannels(prefs)
    }

    fun setScope(prefs: SharedPreferences, scope: String) {
        ensureLocalFlag(prefs)
        val value = if (scope == SCOPE_ONE) SCOPE_ONE else SCOPE_ALL
        prefs.edit().putString(KEY_SCOPE, value).commit()
        if (value == SCOPE_ONE) {
            // نُبقي أول خادم مفعّل فقط.
            val firstOn = listOf(BUILTIN_GATEWAY, BUILTIN_LOCAL).firstOrNull { isOn(prefs, it) && hasSetup(prefs, it) }
                ?: list(prefs).firstOrNull { it.enabled }?.id
            if (firstOn != null) disableAllExcept(prefs, firstOn)
        }
        applyChannels(prefs)
    }

    private fun hasSetup(prefs: SharedPreferences, id: String): Boolean = when (id) {
        BUILTIN_GATEWAY -> (prefs.getString(WebGateway.KEY_URL, "") ?: "").isNotBlank() &&
            (prefs.getString(WebGateway.KEY_TOKEN, "") ?: "").isNotBlank()
        else -> true
    }

    /** مباشر (بلا خوادم) أو استخدام الخوادم المفعّلة. المباشر يبقى الأولوية افتراضياً. */
    fun selectMode(prefs: SharedPreferences, off: Boolean) {
        ensureLocalFlag(prefs)
        if (off) {
            prefs.edit().putString(WebGateway.KEY_MODE, WebGateway.MODE_OFF).commit()
            RoutePolicy.setChannelEnabled(prefs, Channel.DIRECT, true)
        } else {
            val direct = RoutePolicy.channelEnabled(prefs, Channel.DIRECT)
            prefs.edit().putString(WebGateway.KEY_MODE, if (direct) WebGateway.MODE_AUTO else WebGateway.MODE_ONLY).commit()
        }
        applyChannels(prefs)
    }

    /** المباشر أولاً (افتراضي). إيقافه = الخوادم وحدها، ولا يُسمح به في «مباشر». */
    fun setDirectFirst(prefs: SharedPreferences, on: Boolean) {
        if (isOff(prefs)) {
            RoutePolicy.setChannelEnabled(prefs, Channel.DIRECT, true)
            return
        }
        RoutePolicy.setChannelEnabled(prefs, Channel.DIRECT, on)
        prefs.edit().putString(WebGateway.KEY_MODE, if (on) WebGateway.MODE_AUTO else WebGateway.MODE_ONLY).commit()
        applyChannels(prefs)
    }

    private fun ensureLocalFlag(prefs: SharedPreferences) {
        if (!prefs.contains(KEY_LOCAL_ON)) {
            prefs.edit().putBoolean(KEY_LOCAL_ON, RoutePolicy.channelEnabled(prefs, Channel.LOCAL)).commit()
        }
    }

    /** اختيار من الشريط السفلي: يفعّل الخادم المطلوب (ويخرج من «مباشر» إن لزم). */
    fun enableFromHub(prefs: SharedPreferences, id: String) {
        if (isOff(prefs)) selectMode(prefs, off = false)
        setOn(prefs, id, true)
    }

    /** يطابق قنوات التنفيذ مع الاختيار الحالي حتى لا يختلف ما تعرضه الإعدادات عمّا يعمل فعلاً. */
    fun applyChannels(prefs: SharedPreferences) {
        ensureLocalFlag(prefs)
        val off = isOff(prefs)
        WebGateway.load(prefs)
        val hasRelays = relays(prefs).isNotEmpty()
        RoutePolicy.setChannelEnabled(prefs, Channel.GATEWAY, !off && hasRelays)
        RoutePolicy.setChannelEnabled(prefs, Channel.LOCAL, !off && localOn(prefs))
        prefs.edit().commit()
        WebGateway.load(prefs)
    }
}
