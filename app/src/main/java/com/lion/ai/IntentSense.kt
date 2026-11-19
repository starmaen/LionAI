package com.lion.ai

/**
 * فهم نية الرسالة في «المطوّر» دون الحاجة لأمر صريح مثل «افحص» أو «استخدم الأدوات».
 * لا يحل محل النموذج: يقرر فقط هل تُفتح دورة الأدوات (والنموذج يقرر أيّها يستعمل وهل يلزم أي منها).
 * يعمل على النص المطبَّع (TextNorm.forMatch: أ/إ→ا، ة→ه، بلا تشكيل).
 */
internal object IntentSense {
    private const val B = "(?<![\\p{L}])(?:و|ف)?"
    private const val E = "(?![\\p{L}])"

    /** شكوى أو وصف حالة: الجواب الصحيح يحتاج قراءة الواقع (لا يعمل، بطيء، ظهر لي خطأ...). */
    private val STATE = Regex(
        "(?:لا|ما|مو|مش|لم|لن)\\s*(?:يعمل|تعمل|يشتغل|تشتغل|اشتغل|اشتغلت|عمل|يفتح|تفتح|يرد|ترد|يستجيب|تستجيب|يظهر|تظهر|ينفذ|تنفذ|نجح|نجحت)|" +
            "مو\\s*شغال|مش\\s*شغال|غير\\s*شغال|" + B +
            "(?:توقف|توقفت|تعطل|تعطلت|تعلق|تعلقت|يعلق|بطيء|بطيئه|بطئ|تاخر|تاخرت|يتاخر|ظهر\\s*لي|طلع\\s*لي|يطلع|المشكله|مشكله|مشكلتي|عطل|ما\\s*زال|لسا|لسه|رجع|رجعت|تكرر|تكرر)" + E
    )

    /** سؤال عن التقدم أو النتيجة: يُقرأ من الجلسة/البناء/السجل لا من الذاكرة. */
    private val PROGRESS = Regex(
        "وين\\s*وصل|اين\\s*وصل|ماذا\\s*تم|ايش\\s*صار|شو\\s*صار|ماذا\\s*حدث|كيف\\s*الوضع|ما\\s*الوضع|ما\\s*النتيجه|" +
            "ما\\s*الذي\\s*تغير|ما\\s*تم\\s*انجازه|هل\\s*(?:نجح|انتهى|اكتمل|تم)\\s*(?:البناء|التنفيذ|الدمج|الرفع|الاصلاح)|" +
            B + "(?:النتيجه|التقرير|الحاله)\\s*[؟?]?\$"
    )

    /** رغبة/طلب غير أمري («أريد إضافة…»، «لازم يكون…»، «المفروض…»): طلب عمل وليس سؤالاً. */
    private val WISH = Regex(
        B + "(?:اريد|ابغى|ابي|بدي|احتاج|محتاج|اتمنى|المفروض|لازم|يجب|ينبغي|ضيف|اضافه|اضافة|خلي|خلّي|يفضل|الافضل)" + E
    )

    /** أسماء أشياء المشروع المعرّفة («التطبيق»، «الأداة»…): الإشارة إليها غالباً إشارة إلى الواقع لا إلى المعرفة العامة. */
    private val PROJECT_NOUN = Regex(
        B + "(?:التطبيق|الكود|الاداه|الادوات|البوابه|الخطا|الاخطاء|الميزه|الواجهه|الشاشه|الزر|البناء|المزود|المزودات|المزودين|الوكيل|" +
            "السياق|الاستجابه|السيرفر|الخادم|الجلسه|الاعدادات|الصفحه|المتصفح|الذاكره|الفرع|الكوميت|المستودع|الريبو|العقل\\s*المركزي|" +
            "النموذج|الموديل|السكربت|الداله|الكلاس|المكتبه|الاعتماديات|المانيفست|الاختبارات|الاختبار|الصلاحيات|التوكن)" + E
    )

    /** كلام عام/إبداعي/ترجمة: لا علاقة له بالمشروع حتى لو كان أمراً. */
    private val CHAT_ONLY = Regex(
        B + "(?:احكي|احكى|نكته|نكتة|ترجم|ترجمه|قصيده|شعر|اغنيه|حكايه|لغز|تسليه|معلومه\\s*عامه|اقترح\\s*(?:علي)?\\s*(?:اسم|فيلم|اكله|مسلسل|كتاب))" + E
    )

    fun hasStateSignal(n: String): Boolean = STATE.containsMatchIn(n) || PROGRESS.containsMatchIn(n)

    /** إشارة قوية: تُجبر أول رد على أن يحمل استدعاء أداة (قراءة الواقع أولاً). */
    fun strongSignal(text: String, chatMode: ChatMode): Boolean {
        if (chatMode != ChatMode.CODE) return false
        val n = TextNorm.forMatch(text)
        if (n.length < 4 || CHAT_ONLY.containsMatchIn(n)) return false
        if (UserIntent.classify(text) == UserIntent.Kind.CAPABILITY) return false
        return hasStateSignal(n) || (WISH.containsMatchIn(n) && PROJECT_NOUN.containsMatchIn(n))
    }

    /**
     * هل الرسالة (في «المطوّر») تتعلق ضمناً بالمشروع؟
     * - شكوى/تقدم/رغبة: نعم.
     * - ذكر شيء معرّف من المشروع في سؤال أو طلب: نعم.
     * - أي طلب غير أمري ≥ 3 كلمات ليس نصاً عادياً ولا كلاماً إبداعياً: نعم (الأصل في المطوّر أن الأدوات جاهزة).
     * سؤال معرفة عام («ما عاصمة فرنسا؟») وسؤال القدرة وكلمات المجاملة تبقى مباشرة.
     */
    fun impliesProjectWork(text: String, chatMode: ChatMode, textObject: Boolean): Boolean {
        if (chatMode != ChatMode.CODE) return false
        val n = TextNorm.forMatch(text)
        if (n.length < 4 || CHAT_ONLY.containsMatchIn(n)) return false
        val intent = UserIntent.classify(text)
        if (intent == UserIntent.Kind.CAPABILITY) return false
        if (hasStateSignal(n)) return true
        if (textObject) return false
        if (PROJECT_NOUN.containsMatchIn(n)) return true
        if (intent == UserIntent.Kind.REQUEST) {
            if (WISH.containsMatchIn(n)) return true
            return n.split(' ').count { it.isNotBlank() } >= 3
        }
        return false
    }
}
