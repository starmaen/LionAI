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
        "gemini", "claude", "cohere", "aihorde", "deepai", "cloudflare", "openai", "qwen",
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
            "apinex" -> LlmClient.endpoint("apinex", "https://api.apinex.bond/v1/chat/completions") to emptyMap()
            else -> {
                val info = PROVIDERS.firstOrNull { it.id == provider } ?: return null
                if (info.webviewUrl != null || info.category != "chat") return null
                val target = LlmClient.endpoint(provider, info.endpoint)
                if (target.isBlank()) null else LlmClient.normalizeCustomUrl(target) to emptyMap()
            }
        }
    }
}
