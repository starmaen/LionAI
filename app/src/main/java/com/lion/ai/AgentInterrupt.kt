package com.lion.ai

import java.util.concurrent.ConcurrentHashMap

/**
 * مقاطعة المستخدم أثناء عمل الوكيل.
 *
 * - أمر إيقاف صريح («توقف»، «قف»، «stop»…): إيقاف فوري للمحادثة كلها، يشمل ضغط زر الإيقاف في صفحة الموقع.
 * - ملاحظة/اعتراض عادي: يتوقف الوكيل قبل تنفيذ أي أداة تالية (وتُلغى خطوة المزود الجارية)، وتُحفظ رسالته
 *   لتُرسل فور التوقف فيجيب عنها الوكيل ثم يكمل من نقطة التوقف.
 */
object AgentInterrupt {
    private val requested = ConcurrentHashMap<String, Boolean>()
    private val queued = ConcurrentHashMap<String, String>()

    private val STOP = Regex(
        "^[\\s\\p{P}]*(?:(?:من\\s*فضلك|لو\\s*سمحت|رجاء[اً]?|الآن|الان|فورا|فوراً|يا\\s*\\p{L}+)\\s*)*" +
            "(?:توقف(?:ي|وا)?|اوقف(?:ه|ها|ك)?|أوقف(?:ه|ها|ك)?|قف|كفى|كفي|يكفي|بس|خلاص|لا\\s*تكمل|لاتكمل|لا\\s*تتابع|" +
            "لا\\s*تنفذ|لاتنفذ|لا\\s*تعدل|لاتعدل|إلغاء|الغاء|الغ[ِ]?|الغي|ألغ|ألغي|stop|halt|cancel|abort|enough|wait|hold\\s*on)" +
            "(?:\\s+(?:الان|الآن|فورا|فوراً|هنا|عن\\s*العمل|العمل|التنفيذ|now|please|it))*[\\s\\p{P}]*$",
        RegexOption.IGNORE_CASE
    )

    /** هل الرسالة أمر إيقاف صريح (قصير وخالٍ من أي طلب آخر)؟ */
    fun isStopCommand(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 48) return false
        return STOP.containsMatchIn(t)
    }

    private val revoked = ConcurrentHashMap<String, Boolean>()

    private val DENY_ACTION = Regex(
        "(?:^|[\\s،,.:؛;])(?:قلت\\s+|اقول\\s+)?لا\\s*(?:ت)?(?:رفع|عدل|نفذ|كمل|تابع|كتب|غير|حذف|دفع|مس|لمس|بدل|نشئ|نشأ|ضف|ضيف|دمج|مرر|اكمل|استمر|واصل|حدث|ادفع|ارفع|عدّل)",
        RegexOption.IGNORE_CASE
    )
    private fun norm(t: String) = t.replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه')

    /** رفض لتعديل/متابعة بأي صياغة («لا تعدل»، «لاترفع تعديلات»، «قلت لا تعدل أي شيء»…) في أي موضع من الرسالة. */
    fun deniesChanges(text: String): Boolean {
        val t = norm(text.trim())
        return t.isNotEmpty() && (DENY_ACTION.containsMatchIn(t) || isStopCommand(t))
    }

    /** إيقاف فوري: أمر إيقاف صريح، أو رفض قصير للتعديل/المتابعة. */
    fun isHardStop(text: String): Boolean {
        val t = norm(text.trim())
        return isStopCommand(t) || (t.length <= 90 && DENY_ACTION.containsMatchIn(t))
    }

    /** إلغاء إذن الكتابة فوراً أثناء التشغيل (يُفحص قبل كل أداة تعديل). */
    fun revokeWrite(chatId: String) { if (chatId.isNotBlank()) revoked[chatId] = true }
    fun consumeRevoke(chatId: String): Boolean = chatId.isNotBlank() && revoked.remove(chatId) == true

    fun request(chatId: String, message: String) {
        queued[chatId] = (queued[chatId]?.let { "$it\n" }.orEmpty() + message).take(4000)
        requested[chatId] = true
    }

    /** يستهلك طلب التوقف مرة واحدة. */
    fun consume(chatId: String): Boolean = requested.remove(chatId) == true

    /** هل هناك طلب توقف معلّق؟ (فحص دون استهلاك، قبل تنفيذ أداة أو معالجة رد). */
    fun isPending(chatId: String): Boolean = chatId.isNotBlank() && requested[chatId] == true

    fun takeQueued(chatId: String): String? = queued.remove(chatId)

    fun hasQueued(chatId: String): Boolean = queued.containsKey(chatId)

    /** عند الإيقاف الصريح لا تبقى مقاطعة قديمة تُنفَّذ لاحقاً. */
    fun clear(chatId: String) {
        requested.remove(chatId)
        queued.remove(chatId)
        revoked.remove(chatId)
    }
}
