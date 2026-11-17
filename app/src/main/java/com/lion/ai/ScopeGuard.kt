package com.lion.ai

/**
 * حارس النطاق: يُلزم المزود بما طُلب منه فقط.
 *
 * طلب استعلام واحد («أعطني آخر بناء ناجح»، «اعرض حالة الفرع») يُجاب بالأداة اللازمة وحدها
 * ثم تُعرض النتيجة بلا أي إجراء إضافي (لا تعديل ولا رفع ولا بناء ولا فحص موسّع).
 * أي طلب لاحق يعيد التقييم من جديد، فيمكن للمستخدم أن يطلب بعده ما يشاء.
 */
internal object ScopeGuard {
    /** يُضاف إلى تعليمات المزود في كل دورة أدوات، ويقيّد بالطلب نفسه دون أن يمنع العمل المطلوب صراحة. */
    const val DIRECTIVE = """

## حدود النطاق (إلزامية)
- نفّذ ما طلبه المستخدم حرفياً وفي حدوده؛ لا توسّع المهمة ولا تضف خطوات لم تُطلب.
- طلب استعلام أو عرض (آخر بناء، حالة، قائمة، محتوى ملف…): استخدم الأداة الأنسب مرة واحدة ثم اعرض النتيجة، بلا تعديل أو رفع أو بناء أو فحص إضافي.
- لا تبدأ عملاً جديداً بعد الإجابة؛ اعرض ما وجدته فقط، وانتظر الطلب التالي من المستخدم.
- إن احتجت قراراً أو إذناً قبل خطوة حساسة فاسأل عبر بطاقة الأذونات واذكر الخيارات، ثم تابع فور الاختيار.
- في المهام الطويلة اكتب كل بضع خطوات تقريراً مرحلياً من سطرين (ما أُنجز / التالي) داخل الحوار ثم تابع العمل مباشرة دون انتظار ردّ.
"""

    private val LOOKUP_VERB = Regex(
        "(?:^|[\\s،,.:؛;(])(?:اعطني|اعطيني|اعرض|اعرضلي|اظهر|اريني|ارني|جيب|هات|ابحث عن|دلني على|شو|ما هو|ما هي|ماهو|ماهي|" +
            "اريد|ابغى|ابي|show|get|give me|list|display|what is|what's|which|find)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE
    )
    private val LOOKUP_NOUN = Regex(
        "(?:اخر|آخر|احدث|أحدث|latest|last|recent|current|حاله|حالة|status|رابط|link|url|رقم|number|عدد|count|" +
            "بناء|build|run|commit|كوميت|فرع|branch|release|اصدار|إصدار|version|نسخه|نسخة|محتوى|content|قائمه|قائمة|list)",
        RegexOption.IGNORE_CASE
    )
    /** «آخر build» أو «آخر run» اسم لا فعل أمر؛ نزيله قبل فحص نية التعديل. */
    private val NOUN_PHRASE = Regex(
        "(?:اخر|آخر|احدث|أحدث|latest|last|recent|successful|failed|ناجح|فاشل)\\s+(?:successful\\s+|failed\\s+|ناجح\\s+|فاشل\\s+)?" +
            "(?:build|run|commit|بناء|عمليه بناء|عملية بناء|كوميت)",
        RegexOption.IGNORE_CASE
    )
    private const val MAX_CHARS = 180

    /** طلب استعلام قصير بلا نية تعديل: يُجاب بأداة واحدة ثم نتيجة. */
    fun isLookup(request: String): Boolean {
        val text = request.trim()
        if (text.isEmpty() || text.length > MAX_CHARS || text.lines().size > 2) return false
        val t = TextNorm.forMatch(text)
        val verbs = LOOKUP_VERB.containsMatchIn(t) || t.startsWith("اخر") || t.startsWith("احدث") ||
            t.startsWith("latest") || t.startsWith("last ")
        if (!verbs || !LOOKUP_NOUN.containsMatchIn(t)) return false
        val withoutNouns = NOUN_PHRASE.replace(t, " ")
        return !WriteIntent.hasMutationIntent(withoutNouns)
    }

    /** يُستبدل بعبارة «أكمل المهمة» بعد أول نتيجة أداة ناجحة في طلب استعلام. */
    const val FINISH_NOW =
        "هذا طلب استعلام واحد: اعرض النتيجة المطلوبة الآن بإيجاز بالعربية، بلا أدوات إضافية وبلا أي إجراء لم يُطلب."

    /** تقرير مرحلي قصير كل [every] خطوة في المهام الطويلة؛ فارغ خارج هذه الخطوات. */
    fun progressNote(stepsDone: Int, every: Int = 6): String =
        if (stepsDone > 0 && stepsDone % every == 0)
            "\n\nقبل متابعة الأدوات اكتب تقريراً مرحلياً من سطرين فقط (ما أُنجز / ما التالي) ثم استدعِ الأداة التالية مباشرة."
        else ""
}
