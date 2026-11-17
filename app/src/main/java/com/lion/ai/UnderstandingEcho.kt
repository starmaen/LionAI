package com.lion.ai

/**
 * سطر فهم فوري يُبنى محلياً قبل أي اتصال بمزود: يعرض للمستخدم كيف فهم التطبيق طلبه
 * (نوع العمل + مقتطف من طلبه)، فإن أخطأ الفهم صحّحه في ثوانٍ بدل انتظار المهمة كلها.
 */
internal object UnderstandingEcho {
    private fun verb(vararg words: String) =
        Regex("(?<![\\p{L}])(?:و|ف)?(?:" + words.joinToString("|") + ")(?![\\p{L}])")

    private val KINDS: List<Pair<Regex, String>> = listOf(
        verb("اختبر", "جرب") to "اختبار",
        verb("اصلح", "صحح") to "إصلاح",
        verb("افحص", "دقق", "راجع", "شوف", "شاهد", "حلل", "تحقق", "تاكد") to "فحص",
        verb("ابحث", "اعرض", "ادرس", "اكتشف", "اقرا") to "قراءة وبحث",
        verb("عدل", "اضف", "ضيف", "اعمل", "ابن", "ابني", "طور", "حسن", "رتب", "نظف", "حدث") to "تعديل وتطوير",
        verb("ابد[اءئ]", "نفذ", "انفذ", "شغل", "كمل", "اكمل", "تابع", "استمر", "طبق") to "تنفيذ"
    )

    fun line(prompt: String): String {
        val snippet = prompt.trim().lineSequence().firstOrNull().orEmpty().trim().let {
            if (it.length > 60) it.take(60).trimEnd() + "…" else it
        }
        if (snippet.isBlank()) return "فهمت الطلب وأبدأ العمل بالأدوات."
        val n = TextNorm.forMatch(prompt)
        val kind = KINDS.firstOrNull { it.first.containsMatchIn(n) }?.second
        return if (kind == null) "فهمت طلبك: «$snippet». أبدأ بالأدوات الآن."
        else "فهمت: $kind — «$snippet». أبدأ بالأدوات الآن."
    }
}
