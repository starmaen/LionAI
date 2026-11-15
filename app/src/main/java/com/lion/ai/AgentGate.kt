package com.lion.ai

/**
 * بوابة قرار الأدوات: الجواب المباشر هو الأصل.
 * تُفتح دورة الأدوات فقط لعمل فعلي على ملف/مشروع/مستودع أو لأمر تنفيذي صريح،
 * فلا تُستدعى الأدوات لسؤال تافه أو مجاملة أو كلام عادي يحتوي كلمة مثل «بناء» أو «سجل».
 */
internal object AgentGate {
    private const val P = "(?<![\\p{L}])(?:و|ف)?"

    /** ملف بامتداد، أو مسار مشروع معروف، أو مسار فيه اسم ملف. */
    private val FILE_TARGET = Regex(
        "[\\w-]+\\.(?:kt|kts|java|py|js|ts|tsx|json|xml|ya?ml|md|gradle|sh|zip|apk|txt|pdf)\\b|" +
            "(?:^|[\\s(])(?:app|src|docs|scripts|model|\\.github|gradle)/[\\w./-]+|" +
            "[\\w-]+/[\\w-]+\\.\\w{1,5}\\b"
    )

    /** مستودع أو مشروع المستخدم نفسه (لا كلمة «ملف» أو «كود» وحدها). */
    private val REPO_TARGET = Regex(
        "github\\.com|مستودع|ريبو|repo\\b|repository|readme|" +
            "(?<![\\p{L}])(?:المشروع|مشروعي|مشروعنا|الملفات|المجلد|workflow)(?![\\p{L}])"
    )

    private val FAILURE = Regex("(?:فشل|فشلت|خطأ|خطا|أخطاء|اخطاء|error|failed|failure|crash|exception|stack\\s?trace)")
    private val BUILD_CONTEXT = Regex("(?:بناء|build|workflow|gradle|compile|apk|اختبار|test|logs?|لوج|سجل)")

    /** أفعال تنفيذية لا تحتاج هدفاً صريحاً في «المطوّر». */
    private val STRONG_ACTION = Regex(
        P + "(?:عدّل|عدل|أصلح|اصلح|نفّذ|نفذ|شغّل|شغل|ثبّت|ثبت|احذف|أنشئ|انشئ|ابنِ|ابني|ادفع|اضغط|compress|extract|commit|push|build|install|execute)(?![\\p{L}])"
    )

    /** أفعال عامة تحتاج هدفاً (ملف/مستودع) كي لا تُفتح الأدوات لـ«اقرأ لي قصة». */
    private val TARGETED_ACTION = Regex(
        P + "(?:اقرأ|افحص|حلل|راجع|انسخ|انقل|احفظ|ارفع|اكتب|read|write|check|analyze)(?![\\p{L}])"
    )

    /** مادة نصية عادية: تعديل/كتابة نص لا ملف. */
    private val TEXT_OBJECT = Regex(
        "(?:الجملة|الفقرة|النص|الرسالة|الايميل|الإيميل|المقال|القصة|الترجمة|قصيدة|قصة|مقال|رسالة|إيميل|ايميل|تغريدة|منشور)"
    )

    private val GENERAL_REPO = Regex(
        "(?:مستودع|ريبو|repo\\b|repository|github|readme|" +
            "\\.(?:kt|kts|java|py|js|ts|tsx|json|xml|ya?ml|md|gradle|sh)\\b)"
    )

    fun hasTarget(text: String): Boolean =
        FILE_TARGET.containsMatchIn(text) || REPO_TARGET.containsMatchIn(text) || text.contains("github.com/")

    /** إشارة إلى مشروع/خطأ فعلي تستوجب قراءة الواقع (أضيق من القديم: بلا كلمات عامة مفردة). */
    fun projectReference(text: String, mode: ChatMode): Boolean {
        if (text.contains("github.com/")) return true
        return when (mode) {
            ChatMode.CODE -> hasTarget(text) ||
                (FAILURE.containsMatchIn(text) && BUILD_CONTEXT.containsMatchIn(text)) ||
                Regex("(?:stack\\s?trace|gradle|commit)").containsMatchIn(text)
            ChatMode.GENERAL -> GENERAL_REPO.containsMatchIn(text)
        }
    }

    fun isTrivial(prompt: String): Boolean {
        val t = prompt.trim()
        if (t.length < 2) return true
        return QuickReply.forText(t) != null || (t.length <= 40 && SmartRouter.isSmallTalk(t) && !hasTarget(t.lowercase()))
    }

    fun shouldUseAgent(prompt: String, hasWork: Boolean, chatMode: ChatMode): Boolean {
        val text = prompt.trim().lowercase()
        if (text.isBlank()) return false
        // المجاملة تُجاب مباشرة حتى لو كانت هناك جلسة عمل مفتوحة.
        if (isTrivial(text) && !WriteIntent.isContinuation(prompt)) return false
        if (hasWork) return true
        val projectRef = projectReference(text, chatMode)
        // سؤال/قدرة: جواب مباشر، إلا سؤالاً عن مشروع أو خطأ فعلي.
        if (UserIntent.classify(text) != UserIntent.Kind.REQUEST) return projectRef
        val target = hasTarget(text)
        // «عدّل الفقرة» / «اكتب رسالة»: نص عادي لا أدوات.
        if (TEXT_OBJECT.containsMatchIn(text) && !target && !DevTask.matches(text)) return false
        return (STRONG_ACTION.containsMatchIn(text) && (target || chatMode == ChatMode.CODE)) ||
            (TARGETED_ACTION.containsMatchIn(text) && target) ||
            WriteIntent.isDirectCommand(text) && (target || chatMode == ChatMode.CODE) ||
            DevTask.matches(text) || projectRef
    }
}
