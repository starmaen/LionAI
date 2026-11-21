package com.lion.ai

/**
 * استدعاء الأدوات الأصلي (function calling) لكل مزود API متوافق مع OpenAI، مجانياً كان أو مدفوعاً.
 * هو مسار إضافي لا شرط: عند أي رفض أو فشل نعود تلقائياً للبروتوكول النصي [[TOOL]] الذي يعمل مع كل المزودات
 * (بما فيها صفحات الويب)، ونتذكّر ألا نكرر المحاولة الأصلية لهذا المزود/النموذج.
 */
internal object NativeToolRoutes {
    /** رفض صريح من المزود لحقل tools أو لهذا النموذج: لا فائدة من التكرار. */
    internal val UNSUPPORTED_STATUS = setOf(400, 404, 405, 415, 422)

    /** مزودات بصيغة خاصة (غير OpenAI) أو بمعاملات مختلفة: تبقى على البروتوكول النصي. */
    private val TEXT_ONLY = setOf(
        "gemini", "claude", "cohere", "aihorde", "deepai", "cloudflare", "qwen",
        "custom", "local_proxy", "opencode-local"
    )

    private val broken = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun key(provider: String, model: String) = provider + "|" + model

    fun isBroken(provider: String, model: String) = key(provider, model) in broken
    fun markBroken(provider: String, model: String) { broken.add(key(provider, model)) }
    internal fun resetForTests() = broken.clear()

    /** (عنوان الدردشة، ترويسات إضافية) أو null = بروتوكول نصي فقط. */
    fun route(provider: String): Pair<String, Map<String, String>>? {
        if (provider in TEXT_ONLY || provider.startsWith("opencode") || provider == LionOne.ID) return null
        return when (provider) {
            "groq" -> LlmClient.endpoint("groq", "https://api.groq.com/openai/v1/chat/completions") to emptyMap()
            "openrouter" -> LlmClient.endpoint("openrouter", "https://openrouter.ai/api/v1/chat/completions") to
                mapOf("HTTP-Referer" to "https://github.com/starmaen/LionAI", "X-Title" to "Lion AI")
            "deepseek" -> LlmClient.endpoint("deepseek", "https://api.deepseek.com/chat/completions") to emptyMap()
            "openai" -> LlmClient.endpoint("openai", "https://api.openai.com/v1/chat/completions") to emptyMap()
            "apinex" -> LlmClient.endpoint("apinex", "https://api.apinex.bond/v1/chat/completions") to emptyMap()
            else -> {
                val info = PROVIDERS.firstOrNull { it.id == provider } ?: return null
                if (info.webviewUrl != null || info.category != "chat") return null
                val target = LlmClient.endpoint(provider, info.endpoint)
                if (target.isBlank()) null else LlmClient.normalizeCustomUrl(target) to emptyMap()
            }
        }
    }

    /** معاملات الطلب الخاصة بالمزود (اسم معامل الحد، هل نحذف temperature). OpenAI يستخدم max_completion_tokens بلا temperature. */
    fun requestParams(provider: String): Pair<String, Boolean> =
        if (provider == "openai") "max_completion_tokens" to true else "max_tokens" to false

    /** ماذا نفعل عند فشل المحاولة الأصلية؟ */
    internal enum class Fallback { TEXT_PROTOCOL, SURFACE_ERROR }

    private val SURFACE_STATUS = setOf(401, 402, 403, 408, 413, 429, 502, 503, 504, 529)

    private fun isContextError(e: Throwable): Boolean =
        QuotaGuard.classify(e.message.orEmpty()) == QuotaGuard.Kind.CONTEXT

    /**
     * الرجوع للبروتوكول النصي يفيد فقط حين يكون سبب الفشل هو «الأدوات الأصلية» نفسها (رفض الحقل/الشكل).
     * حدّ المعدّل والمفتاح والرصيد والمهلة والشبكة وطول السياق ستفشل بالمثل في المسار النصي،
     * فإعادة الإرسال تُهدر الحصة وتضاعف الانتظار (كانت حتى 2×120ث للخطوة): نعرض الخطأ ليعالجه QuotaGuard.
     */
    internal fun decide(e: Throwable): Fallback = when {
        e is LlmException ->
            if (isContextError(e) || e.status in SURFACE_STATUS) Fallback.SURFACE_ERROR else Fallback.TEXT_PROTOCOL
        e is java.io.IOException -> Fallback.SURFACE_ERROR
        else -> Fallback.TEXT_PROTOCOL
    }

    /** نحظر الأدوات الأصلية لهذا المزود/النموذج فقط عند رفض صريح لها، لا عند «السياق طويل» (400 أيضاً). */
    internal fun shouldMarkBroken(e: Throwable): Boolean =
        e is LlmException && e.status in UNSUPPORTED_STATUS && !isContextError(e)

    /** رد خطأ بأيقونات يصنّفها QuotaGuard (⏳ حد، 💳 رصيد، 🔑 مفتاح، مهلة/شبكة عابرة، سياق). */
    internal fun errorReply(e: Throwable, model: String): String {
        val msg = e.message.orEmpty().take(300)
        if (e is LlmException) {
            return when {
                e.status == 429 -> "⏳ تجاوزت حد الاستخدام مؤقتاً (429). $msg"
                isContextError(e) -> "⚠️ الطلب أطول من سياق الموديل (${e.status}) — طول السياق. $msg"
                e.status == 402 -> "💳 رصيد المزود غير كافٍ (402). $msg"
                e.status == 401 || e.status == 403 ->
                    "🔑 المفتاح غير صالح أو لا يملك صلاحية للموديل «$model» (${e.status}). $msg"
                e.status == 408 -> "⚠️ مهلة الاتصال بالمزود (408). $msg"
                else -> "⚠️ خطأ من المزود (${e.status}): $msg"
            }
        }
        return if (e is java.net.SocketTimeoutException) "⚠️ مهلة الاتصال بالمزود. $msg"
        else "⚠️ تعذر الاتصال بالمزود (الشبكة): ${msg.ifBlank { e.javaClass.simpleName }}"
    }
}

