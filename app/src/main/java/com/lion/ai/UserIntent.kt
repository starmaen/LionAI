package com.lion.ai

/**
 * نية رسالة المستخدم: سؤال قدرة أو معرفة يُجاب مباشرة، أم طلب تنفيذ.
 *
 * لا يعتمد على علامة «؟» ولا على \b (غير موثوقة مع العربية): يطبّع النص ثم يفحص بداية الجملة،
 * فيُفرّق «هل بإمكانك إنشاء مجلدات وملفات؟» (سؤال قدرة → جواب مباشر)
 * عن «هل يمكنك إصلاح الملف Main.kt» (طلب مهذب على هدف محدد → تنفيذ).
 */
object UserIntent {
    enum class Kind { CAPABILITY, QUESTION, REQUEST }

    private val INTERROGATIVE = Regex(
        "^(?:و|ف)?(?:هل|ايمكن|اليس|ما|ماذا|كيف|لماذا|لما|متي|اين|كم|ايش|شو|ليش|اشرح|وضح|لخص|فسر|عرف|" +
            "what|how|why|when|where|which|who|can|could|do|does|is|are|will|would|explain)(?:\\s|$)"
    )

    private val CAPABILITY = Regex(
        "^(?:و|ف)?(?:هل\\s+)?(?:(?:بامكانك|يمكنك|تستطيع|تقدر|تعرف|تدعم|تجيد|عندك|لديك|تقوم|ممكن|من الممكن)|" +
            "can you|could you|are you able|do you)(?:\\s|$)"
    )

    /** هدف محدد (مسار/ملف بامتداد/رابط/إشارة معرّفة) يحوّل «هل تستطيع…؟» من سؤال قدرة إلى طلب. */
    private val SPECIFIC_TARGET = Regex(
        "[\\w-]+/[\\w./-]+|" +
            "[\\w-]+\\.(?:kt|kts|java|py|js|ts|tsx|json|xml|ya?ml|md|gradle|sh|zip|pdf|txt|apk)\\b|" +
            "github\\.com|https?://|" +
            "(?:^|\\s)(?:هذا|هذه|هذي|ذلك|تلك|الملف|المشروع|المستودع|الكود|التطبيق|الريبو)(?:\\s|$)"
    )

    fun classify(text: String): Kind {
        val t = TextNorm.forMatch(text)
        if (t.isBlank()) return Kind.REQUEST
        val specific = SPECIFIC_TARGET.containsMatchIn(t)
        if (CAPABILITY.containsMatchIn(t)) return if (specific) Kind.REQUEST else Kind.CAPABILITY
        val question = t.endsWith("؟") || t.endsWith("?") || INTERROGATIVE.containsMatchIn(t)
        return if (question) Kind.QUESTION else Kind.REQUEST
    }

    /** سؤال قدرة عام: جوابه «نعم/لا + ما أستطيعه» ولا يبدأ أي تنفيذ ولا بطاقة قرار. */
    fun isCapabilityQuestion(text: String): Boolean = classify(text) == Kind.CAPABILITY
}
