package com.lion.ai

import android.content.SharedPreferences

/**
 * جسر «صورة ← نص» للدردشات التي لا تقرأ الصور (صفحات الويب والموديلات بلا رؤية).
 *
 * صفحات الويب لا تستقبل الصور آلياً، فنحوّل الصور إلى نص بموديل رؤية من مزودات API المتاحة لدى المستخدم
 * (استخراج حرفي للنص الظاهر في لقطات الشاشة + وصف دقيق للصور العادية)، ثم نُلحق النص بالرسالة.
 * عدة صور تُرسل معاً في طلب رؤية واحد.
 */
object ImageToText {
    /** ترتيب التفضيل: الأقوى في العربية/OCR أولاً. */
    private val ORDER = listOf(
        "gemini", "openai", "claude", "mistral", "openrouter", "groq", "together",
        "nvidia", "cloudflare", "zai", "ollama", "deepseek", "ovh", "custom"
    )

    private const val INSTRUCTION =
        "حلّل الصور المرفقة بدقة لتُستخدم نتيجتك بديلاً عن رؤيتها. لكل صورة بالترتيب: " +
            "1) إن كانت لقطة شاشة أو صورة تحوي نصاً فانسخ النص الظاهر حرفياً وبنفس لغته وترتيبه (OCR) دون تلخيص. " +
            "2) ثم صف ما فيها بإيجاز دقيق (عناصر الواجهة، الأخطاء، الأرقام، الأشخاص والأشياء والألوان). " +
            "لا تخمّن ما لا يُرى، واذكر بوضوح ما هو غير مقروء."

    /** مزود رؤية جاهز (له مفتاح وموديله يقرأ الصور)، أو null. */
    fun pickProvider(prefs: SharedPreferences): String? = ORDER.firstOrNull { p ->
        val key = prefs.getString("api_key_$p", "").orEmpty()
        val model = prefs.getString("model_$p", "").orEmpty()
        (key.isNotBlank() || p == "custom") && LlmClient.supportsVision(p, model) && key.isNotBlank()
    }

    /** يعيد نص الاستخراج/الوصف، أو null إن لم يتوفر مزود رؤية أو فشل الطلب. (حاجب: استدعِه خارج الخيط الرئيسي) */
    fun describe(prefs: SharedPreferences, images: ImageAttachment, userText: String): String? {
        val provider = pickProvider(prefs) ?: return null
        val key = prefs.getString("api_key_$provider", "").orEmpty()
        val model = RoutePolicy.modelFor(prefs, provider)
        val question = if (userText.isBlank()) INSTRUCTION else "$INSTRUCTION\nسؤال المستخدم المرتبط بالصور (للتركيز فقط): $userText"
        val reply = runCatching {
            LlmClient.ask(
                message = question,
                apiKey = key,
                provider = provider,
                history = emptyList(),
                modelOverride = model,
                accountId = prefs.getString("cf_account_id", "").orEmpty(),
                image = images,
                readTimeoutMs = 90_000,
                forceDirect = true
            )
        }.getOrNull() ?: return null
        if (LlmClient.isErrorReply(reply) || reply.isBlank()) return null
        return reply.trim()
    }

    fun asPromptBlock(names: String, analysis: String): String =
        "[صور مرفقة ($names) — حلّلها التطبيق بنموذج رؤية لأن هذه الدردشة لا تستقبل الصور؛ " +
            "اعتمد على التحليل التالي كأنك ترى الصور]\n$analysis\n[نهاية تحليل الصور]"
}
