package com.lion.ai

import android.content.SharedPreferences

/**
 * جسر «صورة ← نص» للدردشات والمزودات التي لا تقرأ الصور (صفحات الويب والموديلات بلا رؤية).
 *
 * نحوّل الصور إلى نص بموديل رؤية من مزودات API المتاحة لدى المستخدم (استخراج حرفي للنص الظاهر
 * + وصف دقيق للصور العادية)، ثم يُلحق النص بالرسالة أو يُعاد كنتيجة أداة `image_read`.
 * عدة صور تُرسل معاً في طلب رؤية واحد. عند فشل مزود (حصة/خطأ) ننتقل تلقائياً إلى التالي.
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

    /** كل المزودات الجاهزة (لها مفتاح وموديلها يقرأ الصور) بترتيب التفضيل، والمفضَّل أولاً إن كان صالحاً. */
    fun candidates(prefs: SharedPreferences, preferred: String? = null): List<String> {
        val ready = ORDER.filter { p ->
            val key = prefs.getString("api_key_$p", "").orEmpty()
            val model = prefs.getString("model_$p", "").orEmpty()
            key.isNotBlank() && LlmClient.supportsVision(p, model)
        }
        return if (preferred != null && preferred in ready) listOf(preferred) + ready.filter { it != preferred } else ready
    }

    /** مزود رؤية جاهز (له مفتاح وموديله يقرأ الصور)، أو null. */
    fun pickProvider(prefs: SharedPreferences): String? = candidates(prefs).firstOrNull()

    /**
     * الاحتياط الدائم: OCR محلي فقط (بلا مفتاح وبلا إنترنت) لمرفقات الصور.
     * يقرأ النص اللاتيني والأرقام والأكواد؛ العربية تحتاج مزود رؤية. يعيد null إن لم يُعثر على نص.
     * حاجب: يُستدعى من خيط IO.
     */
    fun localDescribe(images: ImageAttachment): String? = ImageTools.localOcrAttachments(images)

    fun asPromptBlock(names: String, analysis: String): String =
        "[صور مرفقة ($names) — حلّلها التطبيق بنموذج رؤية لأن هذه الدردشة لا تستقبل الصور؛ " +
            "اعتمد على التحليل التالي كأنك ترى الصور]\n$analysis\n[نهاية تحليل الصور]"

    /**
     * كتلة الصور المحوّلة محلياً (OCR بلا مزود رؤية). تُرسل دائماً حتى لو لم يُستخرج نص،
     * لأن رفض الرفع كلياً ليس خياراً — الصورة قد تكون فاشلة القراءة لكن سؤال المستخدم يبقى صالحاً.
     */
    fun asLocalBlock(names: String, ocr: String?): String = buildString {
        append("[صور مرفقة ($names) — هذه الدردشة لا تستقبل الصور مباشرة]\n")
        if (ocr.isNullOrBlank()) {
            append("لم يُستخرج نص من الصور محلياً (OCR المحلي يقرأ اللاتينية والأرقام فقط؛ العربية تحتاج موديل رؤية). ")
            append("أجب على سؤال المستخدم بما يمكن، وإن كان الجواب يتوقف على محتوى الصورة فاطلب منه لصق النص الظاهر فيها.")
        } else {
            append("النص المستخرج محلياً (OCR — لاتيني/أرقام):\n$ocr")
        }
        append("\n[نهاية تحليل الصور]")
    }

    private fun askOnce(prefs: SharedPreferences, provider: String, images: ImageAttachment, question: String): String? {
        val key = prefs.getString("api_key_$provider", "").orEmpty()
        val model = RoutePolicy.modelFor(prefs, provider)
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

    /**
     * يعيد نص الاستخراج/الوصف، أو null إن لم يتوفر مزود رؤية أو فشلت كل المحاولات.
     * [instruction] يستبدل التعليمة الافتراضية (أوضاع OCR/واجهة/جدول/خطأ). (حاجب: استدعِه خارج الخيط الرئيسي)
     */
    fun describe(
        prefs: SharedPreferences,
        images: ImageAttachment,
        userText: String,
        instruction: String? = null,
        preferred: String? = null,
        maxAttempts: Int = 3
    ): String? {
        val base = instruction?.takeIf { it.isNotBlank() } ?: INSTRUCTION
        val question = if (userText.isBlank()) base else "$base\nسؤال المستخدم المرتبط بالصور (للتركيز فقط): $userText"
        for (provider in candidates(prefs, preferred).take(maxAttempts)) {
            askOnce(prefs, provider, images, question)?.let { return it }
        }
        return null
    }
}
