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
    const val DEFAULT_URL = "https://lionai-gateway.starsyria2500.workers.dev/v1"

    /** معرّف مفتاح التفعيل المستقل لخادم الويب (settings → enabled_web_gateway). */
    const val ENABLED_ID = "web_gateway"

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
        GatewayProvider("unorouter", "UnoRouter", "deepseek-v4-pro:free"),
        GatewayProvider("deepseek", "DeepSeek", "deepseek-flash"),
        GatewayProvider("openai", "OpenAI", "gpt-6-luna"),
        GatewayProvider("claude", "Claude", "claude-sonnet-5-5"),
        GatewayProvider("kilo", "Kilo", "kilo-auto/free"),
        GatewayProvider("zai", "Z AI", "glm-4.7-flash"),
        GatewayProvider("ollama", "Ollama", "gpt-oss:120b"),
        GatewayProvider("ovh", "OVH", "gpt-oss-120b"),
        GatewayProvider("perplexity", "Perplexity", "sonar"),
        GatewayProvider("grok", "Grok", "grok-2-latest")
    )

    val supported: Set<String> = providers.mapTo(linkedSetOf()) { it.id }

    fun defaultModel(provider: String): String =
        providers.firstOrNull { it.id == provider }?.defaultModel.orEmpty()

    fun providerLabel(provider: String): String =
        providers.firstOrNull { it.id == provider }?.label ?: provider

    data class Config(val url: String, val token: String, val mode: String, val enabled: Boolean = true) {
        val configured: Boolean get() = url.isNotBlank()
    }

    @Volatile
    private var cached = Config("", "", MODE_OFF)

    /** نقاط الوصول الفعلية (الخادم الخارجي + الخوادم المضافة) حسب اختيار المستخدم. */
    @Volatile
    private var relayCache: List<ServerRegistry.Relay> = emptyList()

    fun relays(): List<ServerRegistry.Relay> = relayCache

    fun load(prefs: SharedPreferences): Config {
        val config = Config(
            url = (prefs.getString(KEY_URL, "") ?: "").trim(),
            token = (prefs.getString(KEY_TOKEN, "") ?: "").trim().filterNot { it.isWhitespace() },
            mode = normalizeMode(prefs.getString(KEY_MODE, MODE_OFF)),
            enabled = providerEnabled(prefs, ENABLED_ID)
        )
        cached = config
        relayCache = ServerRegistry.relays(prefs)
        return config
    }

    /** Persist URL/token/mode synchronously so the next HTTP call cannot race SharedPreferences.apply(). */
    fun save(prefs: SharedPreferences, url: String, token: String, mode: String): Config {
        val normalized = normalizeBase(url)
        prefs.edit()
            .putString(KEY_MODE, normalizeMode(mode))
            .putString(KEY_URL, normalized)
            .putString(KEY_TOKEN, token.filterNot { it.isWhitespace() })
            .commit()
        return load(prefs)
    }

    internal data class HttpResult(val code: Int, val body: String, val url: String, val error: String = "") {
        val ok: Boolean get() = code in 200..299
        val html: Boolean get() = body.trimStart().startsWith("<") ||
            body.contains("<html", ignoreCase = true)
    }

    private fun http(url: String, method: String, token: String = "", body: String? = null, readMs: Int = 15_000): HttpResult {
        if (url.isBlank()) return HttpResult(-1, "", url, "رابط فارغ")
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = readMs
                requestMethod = method
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Accept", "application/json, text/plain;q=0.8, */*;q=0.5")
                setRequestProperty("User-Agent", LLM_USER_AGENT)
                setRequestProperty("Accept-Language", "ar,en;q=0.8")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("X-Api-Key", token)
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            HttpResult(code, raw, url)
        } catch (e: java.net.UnknownHostException) {
            HttpResult(-1, "", url, "تعذّر حل اسم النطاق (DNS) لـ ${e.message ?: "الخادم"}. جرّب إيقاف Private DNS أو ضبطه على 1.1.1.1، أو VPN.")
        } catch (e: javax.net.ssl.SSLException) {
            HttpResult(-1, "", url, "فشل TLS/SSL: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: java.net.SocketTimeoutException) {
            HttpResult(-1, "", url, "انتهت مهلة الاتصال بـ $url")
        } catch (e: Exception) {
            HttpResult(-1, "", url, e.message ?: e.javaClass.simpleName)
        }
    }

    internal fun describeHttp(result: HttpResult): String {
        if (result.error.isNotBlank() && result.code < 0) return "❌ ${result.error}\n${result.url}"
        if (result.html) {
            return "❌ الخادم أعاد صفحة HTML وليس JSON (HTTP ${result.code}). " +
                "غالباً حماية Cloudflare أو رابط خاطئ.\n${result.url}"
        }
        if (result.code == 401 || result.code == 403) {
            return "❌ HTTP ${result.code}: مفتاح RELAY_TOKEN مرفوض أو غير مُرسل. " +
                "ضعه في التطبيق مطابقاً لسر Cloudflare.\n${result.url}"
        }
        if (result.code !in 200..299) {
            val message = runCatching { org.json.JSONObject(result.body).optJSONObject("error")?.optString("message") }
                .getOrNull().orEmpty().ifBlank { result.body.take(180) }
            return "❌ الخادم رد HTTP ${result.code}: $message\n${result.url}"
        }
        return ""
    }

    fun current(): Config = cached

    fun normalizeMode(raw: String?): String = when (raw?.trim()?.lowercase()) {
        MODE_AUTO -> MODE_AUTO
        MODE_ONLY -> MODE_ONLY
        else -> MODE_OFF
    }

    /** المدمج + ما يعلنه الخادم فعلياً (/health): الـ Worker يدعم مزودات أكثر وأضافية عبر EXTRA_PROVIDERS. */
    fun supports(provider: String): Boolean = supported.contains(provider) || provider in remoteIds

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
            relayCache.isNotEmpty() &&
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
    fun test(prefs: SharedPreferences, urlOverride: String? = null, tokenOverride: String? = null): String {
        val config = if (urlOverride != null || tokenOverride != null) {
            val url = (urlOverride ?: (prefs.getString(KEY_URL, "") ?: "")).trim()
            val token = (tokenOverride ?: (prefs.getString(KEY_TOKEN, "") ?: "")).filterNot { it.isWhitespace() }
            if (url.isNotBlank()) save(prefs, url, token, prefs.getString(KEY_MODE, MODE_OFF) ?: MODE_OFF)
            else load(prefs)
        } else load(prefs)
        if (config.url.isBlank()) return "⚠️ أدخل رابط الخادم أولاً (مثل $DEFAULT_URL)."
        val url = healthUrl(config.url)
        val result = http(url, "GET", config.token, readMs = 12_000)
        if (!result.ok || result.html) return describeHttp(result).ifBlank { "❌ تعذّر فحص الخادم.\n$url" }
        val json = runCatching { org.json.JSONObject(result.body) }.getOrNull()
        val providers = json?.optJSONObject("providers")
        if (providers != null) remoteIds = providers.keys().asSequence().toSet()
        val active = providers?.length() ?: 0
        val authRequired = json?.optBoolean("auth_required", true) ?: true
        val names = providers?.keys()?.asSequence()?.joinToString(", ").orEmpty()
        val tokenNote = when {
            !authRequired -> "المحادثة مفتوحة بدون مفتاح."
            config.token.isBlank() -> "⚠️ الفحص نجح بدون مفتاح لأن /health عام. أدخل RELAY_TOKEN ثم اضغط «2) المزود» وإلا ستفشل المحادثة بـ 401."
            else -> "المفتاح مُرسل. جرّب «2) المزود» للتأكد من المحادثة."
        }
        return "✅ الخادم يعمل (HTTP ${result.code}) — $active مزود مفعّل" +
            (if (names.isNotBlank()) " ($names)" else "") +
            ".\n$url\n$tokenNote"
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
        val result = http(chatUrl(config.url), "POST", config.token, body, readMs = 45_000)
        val ms = System.currentTimeMillis() - started
        if (!result.ok || result.html) {
            return describeHttp(result).ifBlank { "❌ تعذّر فحص $gatewayModel" }
                .replaceFirst("❌ ", "❌ $gatewayModel — ")
        }
        val json = runCatching { org.json.JSONObject(result.body) }.getOrNull()
        val content = json?.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content", "").orEmpty().trim()
        return if (content.isBlank()) "⚠️ رد فارغ من $gatewayModel (HTTP ${result.code}، ${ms}ms)"
        else "✅ $gatewayModel يعمل عبر الخادم (${ms}ms): ${content.take(80)}"
    }

    /** فحص خادم بدون مزود: /health ثم /models كبديل، دون لمس إعدادات الخادم الخارجي المحفوظة. */
    fun testServerHealth(url: String, token: String): String {
        if (url.isBlank()) return "⚠️ أدخل رابط الخادم أولاً."
        val health = healthUrl(url)
        val first = http(health, "GET", token, readMs = 12_000)
        if (first.ok && !first.html) {
            val json = runCatching { org.json.JSONObject(first.body) }.getOrNull()
            val providers = json?.optJSONObject("providers")
            val names = providers?.keys()?.asSequence()?.joinToString(", ").orEmpty()
            return "✅ الخادم يعمل (HTTP ${first.code})" +
                (if (providers != null) " — ${providers.length()} مزود مفعّل" else "") +
                (if (names.isNotBlank()) " ($names)" else "") + ".\n$health"
        }
        val models = modelsUrl(url)
        val second = http(models, "GET", token, readMs = 15_000)
        if (second.ok && !second.html) {
            val count = runCatching { org.json.JSONObject(second.body).optJSONArray("data")?.length() }.getOrNull() ?: 0
            return "✅ الخادم يعمل (لا يوفّر /health لكن /models يرد: $count موديل).\n$models"
        }
        return describeHttp(second).ifBlank { describeHttp(first) }.ifBlank { "❌ تعذّر فحص الخادم.\n$health" }
    }

    /** فحص خادم مع مزود محدد (طلب محادثة حقيقي قصير) دون لمس الإعدادات المحفوظة. */
    fun testServerWithProvider(url: String, token: String, provider: String, model: String): String {
        if (url.isBlank()) return "⚠️ أدخل رابط الخادم أولاً."
        val pid = provider.trim()
        val actualModel = model.trim().ifBlank { defaultModel(pid) }
        val gatewayModel = gatewayModel(pid, actualModel)
        val body = org.json.JSONObject().apply {
            put("model", gatewayModel)
            put(
                "messages",
                org.json.JSONArray().put(
                    org.json.JSONObject().put("role", "user").put("content", "أجب بكلمة واحدة فقط: تم")
                )
            )
            put("max_tokens", 8)
            put("temperature", 0)
        }.toString()
        val started = System.currentTimeMillis()
        val result = http(chatUrl(url), "POST", token, body, readMs = 45_000)
        val ms = System.currentTimeMillis() - started
        if (!result.ok || result.html) {
            return describeHttp(result).ifBlank { "❌ تعذّر فحص $gatewayModel" }
                .replaceFirst("❌ ", "❌ $gatewayModel — ")
        }
        val json = runCatching { org.json.JSONObject(result.body) }.getOrNull()
        val content = json?.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content", "").orEmpty().trim()
        return if (content.isBlank()) "⚠️ رد فارغ من $gatewayModel (HTTP ${result.code}، ${ms}ms)"
        else "✅ $gatewayModel يعمل عبر هذا الخادم (${ms}ms): ${content.take(80)}"
    }

    /** جلب موديلات البوابة (اختياري) — يتطلب RELAY_TOKEN. */
    fun listModels(prefs: SharedPreferences): List<String> {
        val config = load(prefs)
        if (config.url.isBlank() || config.token.isBlank()) {
            lastModelsError = if (config.url.isBlank()) "أدخل رابط الخادم أولاً."
            else "أدخل RELAY_TOKEN أولاً — /v1/models يتطلب المفتاح."
            return emptyList()
        }
        val result = http(modelsUrl(config.url), "GET", config.token, readMs = 20_000)
        lastModelsError = if (result.ok && !result.html) "" else describeHttp(result)
        if (!result.ok || result.html) return emptyList()
        val data = runCatching { org.json.JSONObject(result.body).optJSONArray("data") }.getOrNull()
            ?: return emptyList()
        val ids = buildList<String> {
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id", "").orEmpty().trim()
                if (id.isNotBlank()) add(id)
            }
        }
        if (ids.isNotEmpty()) remoteIds = ids.map { it.substringBefore('/') }.toSet()
        return ids
    }

    @Volatile var lastModelsError: String = ""
        private set
}
