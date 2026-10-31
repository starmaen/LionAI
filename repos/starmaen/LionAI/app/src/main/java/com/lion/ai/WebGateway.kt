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

    /** مزود محادثة تدعمه بوابة الويب (نفس معرّفات worker/src/index.js). */
    data class GatewayProvider(val id: String, val label: String, val defaultModel: String)

    val providers: List<GatewayProvider> = listOf(
        GatewayProvider("cfai", "Workers AI", "@cf/meta/llama-3.3-70b-instruct-fp8-fast"),
        GatewayProvider("gemini", "Gemini", "gemini-3.5-flash"),
        GatewayProvider("groq", "Groq", "openai/gpt-oss-120b"),
        GatewayProvider("cerebras", "Cerebras", "gpt-oss-120b"),
        GatewayProvider("openrouter", "OpenRouter", "google/gemma-4-31b-it:free"),
        GatewayProvider("hf", "HuggingFace", "meta-llama/Llama-3.3-70B-Instruct"),
        GatewayProvider("mistral", "Mistral", "mistral-small-latest"),
        GatewayProvider("together", "Together", "meta-llama/Llama-3.3-70B-Instruct-Turbo-Free"),
        GatewayProvider("nvidia", "NVIDIA", "meta/llama-3.3-70b-instruct"),
        GatewayProvider("qwen", "Qwen", "qwen-turbo"),
        GatewayProvider("cohere", "Cohere", "command-r-08-2024"),
        GatewayProvider("fireworks", "Fireworks", "accounts/fireworks/models/llama-v3p3-70b-instruct"),
        GatewayProvider("pollinations", "Pollinations", "openai"),
        GatewayProvider("llm7", "LLM7", "default"),
        GatewayProvider("sambanova", "SambaNova", "Meta-Llama-3.3-70B-Instruct"),
        GatewayProvider("unorouter", "UnoRouter", "deepseek-v4-pro:free")
    )

    val supported: Set<String> = providers.mapTo(linkedSetOf()) { it.id }

    fun defaultModel(provider: String): String =
        providers.firstOrNull { it.id == provider }?.defaultModel.orEmpty()

    fun providerLabel(provider: String): String =
        providers.firstOrNull { it.id == provider }?.label ?: provider

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

    /** المزودات المفعّلة فعلاً على الخادم (تُحدَّث عند فحص الخادم أو جلب الموديلات). فارغة = غير معروف بعد. */
    @Volatile
    private var remoteIds: Set<String> = emptySet()

    fun remoteHas(provider: String): Boolean = remoteIds.isEmpty() || provider in remoteIds

    /** هل يستطيع العقل المركزي استخدام هذا المزود عبر الخادم الآن؟ */
    fun routedAvailable(provider: String): Boolean = shouldRoute(provider) && remoteHas(provider)

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

    /** يطبّع الرابط ليصبح https://host/v1 (يضيف المخطط والمسار عند نقصهما). */
    fun normalizeBase(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.isBlank()) return ""
        if (!url.contains("://")) url = "https://$url"
        val afterScheme = url.substringAfter("://", "")
        val path = afterScheme.substringAfter('/', "")
        return when {
            path.isBlank() -> "$url/v1"
            path == "v1" || path.endsWith("/v1") -> url
            else -> url
        }
    }

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
                if (providers != null) remoteIds = providers.keys().asSequence().toSet()
                val active = providers?.length() ?: 0
                "✅ الخادم يعمل (HTTP $code) — $active مزود مفعّل.\n$url"
            }
        } catch (e: java.net.UnknownHostException) {
            "❌ تعذّر حل اسم النطاق (DNS) لـ ${e.message ?: "الخادم"}.\n" +
                "جرّب: إيقاف Private DNS أو ضبطه على 1.1.1.1، أو VPN، أو افتح الرابط في المتصفح."
        } catch (e: Exception) {
            "❌ تعذّر الوصول للخادم: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** فحص مزود محدد عبر الخادم (طلب حقيقي قصير) قبل تفعيله. */
    fun testProvider(prefs: SharedPreferences, provider: String, model: String): String {
        val config = load(prefs)
        if (config.url.isBlank()) return "⚠️ أدخل رابط الخادم أولاً."
        if (config.token.isBlank()) return "⚠️ أدخل مفتاح الخادم (RELAY_TOKEN) أولاً."
        val pid = provider.trim()
        if (!supports(pid)) return "⚠️ هذا المزود غير مدعوم عبر الخادم — استخدم الوضع المباشر."
        val actualModel = model.trim().ifBlank { defaultModel(pid) }
        val gatewayModel = gatewayModel(pid, actualModel)
        val body = org.json.JSONObject().apply {
            put("model", gatewayModel)
            put(
                "messages",
                org.json.JSONArray().put(
                    org.json.JSONObject()
                        .put("role", "user")
                        .put("content", "أجب بكلمة واحدة فقط: تم")
                )
            )
            put("max_tokens", 8)
            put("temperature", 0)
        }.toString()
        val started = System.currentTimeMillis()
        return try {
            val connection = (URL(chatUrl(config.url)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 45_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${config.token}")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            val ms = System.currentTimeMillis() - started
            val json = runCatching { org.json.JSONObject(raw) }.getOrNull()
            if (code !in 200..299) {
                val message = json?.optJSONObject("error")?.optString("message").orEmpty()
                    .ifBlank { json?.optJSONObject("error")?.optString("code").orEmpty() }
                    .ifBlank { raw.take(180) }
                "❌ $gatewayModel — HTTP $code: $message"
            } else {
                val content = json?.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content", "").orEmpty().trim()
                if (content.isBlank()) "⚠️ رد فارغ من $gatewayModel (HTTP $code، ${ms}ms)"
                else "✅ $gatewayModel يعمل عبر الخادم (${ms}ms): ${content.take(80)}"
            }
        } catch (e: java.net.UnknownHostException) {
            "❌ تعذّر حل اسم النطاق (DNS) لخادم الويب.\n" +
                "جرّب إيقاف Private DNS أو ضبطه على 1.1.1.1، أو استخدم VPN."
        } catch (e: Exception) {
            "❌ تعذّر فحص $gatewayModel: ${e.message ?: e.javaClass.simpleName}"
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
            val ids = buildList<String> {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id", "").orEmpty().trim()
                    if (id.isNotBlank()) add(id)
                }
            }
            if (ids.isNotEmpty()) remoteIds = ids.map { it.substringBefore('/') }.toSet()
            ids
        } catch (e: Exception) {
            emptyList()
        }
    }
}
