package com.lion.ai

import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL

/**
 * ربط اختياري بمزوّدات «خادم الويب» (Cloudflare Worker أو Render أو أي بوابة
 * OpenAI-Compatible) دون إلزام المستخدم بها.
 *
 * الأوضاع:
 * - [MODE_OFF]  : مباشر فقط (الافتراضي). كل مزود يستخدم مفتاحه وعنوانه كما هو.
 * - [MODE_AUTO] : يجرّب الخادم الخارجي أولاً، وإن فشل/توقف يعود تلقائياً للمباشر.
 * - [MODE_ONLY] : عبر الخادم فقط (بدون رجوع) — يبقى المباشر متاحاً بتبديل الوضع.
 *
 * لا تُخزَّن المفاتيح هنا: مفتاح الخادم هو RELAY_TOKEN فقط، ومفاتيح المزودات
 * تبقى في [SharedPreferences] كما هي للوضع المباشر.
 */
object WebGateway {

    const val KEY_URL = "web_gateway_url"
    const val KEY_TOKEN = "web_gateway_token"
    const val KEY_MODE = "web_gateway_mode"

    const val MODE_OFF = "off"
    const val MODE_AUTO = "auto"
    const val MODE_ONLY = "only"

    /** المزودات التي تدعمها بوابة الويب (نفس معرّفات gateway/server.py). */
    val supported: Set<String> = linkedSetOf(
        "gemini", "groq", "openrouter", "cohere", "mistral", "together",
        "cloudflare", "nvidia", "qwen", "hf", "fireworks", "pollinations", "llm7"
    )

    data class Config(val url: String, val token: String, val mode: String) {
        val configured: Boolean get() = url.isNotBlank()
    }

    @Volatile
    private var cached = Config("", "", MODE_OFF)

    fun load(prefs: SharedPreferences): Config {
        val config = Config(
            url = (prefs.getString(KEY_URL, "") ?: "").trim(),
            token = (prefs.getString(KEY_TOKEN, "") ?: "").trim().filterNot { it.isWhitespace() },
            mode = normalizeMode(prefs.getString(KEY_MODE, MODE_OFF))
        )
        cached = config
        return config
    }

    fun current(): Config = cached

    fun normalizeMode(raw: String?): String = when (raw?.trim()?.lowercase()) {
        MODE_AUTO -> MODE_AUTO
        MODE_ONLY -> MODE_ONLY
        else -> MODE_OFF
    }

    fun supports(provider: String): Boolean = supported.contains(provider)

    /** هل نمرّر هذا المزود عبر الخادم الخارجي في الوضع الحالي؟ */
    fun shouldRoute(provider: String): Boolean {
        val config = cached
        return config.mode != MODE_OFF &&
            config.configured &&
            config.token.isNotBlank() &&
            supports(provider)
    }

    /** صيغة الموديل في البوابة: provider/model */
    fun gatewayModel(provider: String, model: String): String {
        val clean = model.trim()
        if (clean.isBlank()) return provider
        return if (clean.startsWith("$provider/")) clean else "$provider/$clean"
    }

    fun chatUrl(raw: String): String = LlmClient.normalizeCustomUrl(raw)

    fun healthUrl(raw: String): String =
        chatUrl(raw).removeSuffix("/chat/completions").removeSuffix("/v1").trimEnd('/') + "/health"

    fun modelsUrl(raw: String): String =
        chatUrl(raw).removeSuffix("/chat/completions").trimEnd('/') + "/models"

    fun modeLabel(mode: String): String = when (normalizeMode(mode)) {
        MODE_AUTO -> "🌐 خادم أولاً"
        MODE_ONLY -> "☁️ خادم فقط"
        else -> "🔌 مباشر"
    }

    /** اختبار سريع متزامن (يُستدعى من Dispatchers.IO). */
    fun test(prefs: SharedPreferences): String {
        val config = load(prefs)
        if (config.url.isBlank()) return "⚠️ أدخل رابط الخادم أولاً (مثل https://name.workers.dev/v1)."
        val url = healthUrl(config.url)
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                if (config.token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${config.token}")
                }
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code !in 200..299) {
                "❌ الخادم رد HTTP $code: ${body.take(160)}"
            } else {
                val providers = runCatching { org.json.JSONObject(body).optJSONObject("providers") }
                    .getOrNull()
                val active = providers?.length() ?: 0
                "✅ الخادم يعمل (HTTP $code) — $active مزود مفعّل.\n$url"
            }
        } catch (e: Exception) {
            "❌ تعذّر الوصول للخادم: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** جلب موديلات البوابة (اختياري) — يتطلب RELAY_TOKEN. */
    fun listModels(prefs: SharedPreferences): List<String> {
        val config = load(prefs)
        if (config.url.isBlank() || config.token.isBlank()) return emptyList()
        return try {
            val connection = (URL(modelsUrl(config.url)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${config.token}")
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code !in 200..299) return emptyList()
            val data = org.json.JSONObject(body).optJSONArray("data") ?: return emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id", "").orEmpty().trim()
                    if (id.isNotBlank()) add(id)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
