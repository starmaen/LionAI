package com.lion.ai

/**
 * سياسة المانيفست والبوابة: تفصل بين «مشروع جديد من الصفر» و«مشروع قائم».
 *
 * المشكلة التي تحلّها: كانت بوابة الجودة تفشل دائماً على المشاريع القائمة (EMPTY_DIR،
 * ORPHAN_FILE، ملفات فارغة خارج الـ Manifest) فتبدو كأنها «تمنع أي بناء» رغم أن الكود
 * سليم ويُبنى فعلاً في CI. النتيجة: الوكيل يعلق بين «أصلح ثم أعد gate_run» و«لا Manifest».
 *
 * القاعدة الآن:
 * - مشروع قائم بلا Manifest: لا شيء مانع إطلاقاً. كل الفحوص ملاحظات، والنجاح الحقيقي
 *   هو نجاح compile/CI.
 * - مشروع ب Manifest معتمد: الملفات المعلنة إلزامية (خطأ إن غابت أو فرغت)، وما عداها ملاحظات.
 * - EMPTY_DIR / ORPHAN_FILE / PLACEHOLDER / تواقيع HMAC: ملاحظات دائماً، لا تمنع البناء.
 * - خطأ توازن الأقواس SYNTAX_BALANCE يمنع فقط في ملفات المشروع نفسها (لا في مكتبات مرفقة).
 */
internal object ManifestPolicy {

    /** رموز ملاحظات لا تمنع الاكتمال في أي سياق. */
    private val NEVER_BLOCKING = setOf(
        "NO_MANIFEST",
        "EMPTY_DIR",
        "ORPHAN_FILE",
        "PLACEHOLDER",
        "EMPTY_FILE",             // ملف فارغ غير معلن في Manifest: ملاحظة، لا يمنع
        "EMPTY_REQUIRED_FILE",
        "SIGNATURE_MISSING",
        "SIGNATURE_MISMATCH",
        "TEST_MISSING",
        "TEST_TAMPERED",
        "BINARY", "IGNORED"
    )

    /** رموز تمنع فقط عندما يوجد Manifest معتمد (ملفات معلنة إلزامية). */
    private val BLOCKING_WITH_MANIFEST = setOf(
        "MISSING_FILE",
        "EMPTY_FILE"
    )

    /**
     * هل هذه الملاحظة تمنع الاكتمال في هذا السياق؟
     * [code] رمز الملاحظة، [manifest] مانيفست المحادثة (null = مشروع قائم)،
     * [snapshot] لقطة المشروع لتفريق أخطاء الملفات الحقيقية عن ضجيج المكتبات.
     */
    fun blocking(code: String, manifest: BuildManifest?, snapshot: ProjectSnapshot? = null): Boolean {
        // مشروع قائم بلا Manifest: لا شيء يمنع — البوابة استشارية.
        if (manifest == null) return false
        if (code in NEVER_BLOCKING) return false
        if (code in BLOCKING_WITH_MANIFEST) return true
        // خطأ لغوي/توازن في ملف موجود فعلاً داخل المشروع: يمنع. غير ذلك ملاحظة.
        if (code == "SYNTAX_BALANCE" || code == "LINT" || code == "TYPECHECK") return true
        val path = code.substringAfter(':', "").trim()
        if (path.isNotBlank() && snapshot != null && !snapshot.has(path)) return false
        return true
    }

    /** شرح بشري بالعربية لكل رمز، يُعرض بجانب الملاحظة في تقرير البوابة. */
    fun explain(code: String): String = when (code) {
        "NO_MANIFEST" -> "لا Manifest لهذه المحادثة؛ مشروع قائم فيُفحص الكود والبنية فقط (ليس خطأً)."
        "EMPTY_DIR" -> "مجلد فارغ — لا يضرّ بالبناء: git لا يتتبع المجلدات الفارغة أصلاً، فهي ملاحظة لا عيب."
        "MISSING_FILE" -> "ملف معلن في Manifest المعتمد غير موجود على القرص."
        "EMPTY_FILE" -> "ملف فارغ — إن كان معلناً في Manifest فاكتب محتواه، وإلا فهو ملاحظة."
        "ORPHAN_FILE" -> "لم يُربط آلياً بنقطة دخول؛ قد يكون مرجعاً ديناميكياً — ملاحظة لا عيب."
        "PLACEHOLDER" -> "يحتوي نصاً مؤقتاً (TODO/قيد الإنشاء) — لا يمنع البناء."
        "SYNTAX_BALANCE" -> "أقواس/علامات تنصيص غير متوازنة — خطأ حقيقي يجب إصلاحه قبل البناء."
        "SIGNATURE_MISSING" -> "ملف اختبار غير موقّع بـ HMAC — التوقيع لحماية الاختبارات من العبث فقط."
        "SIGNATURE_MISMATCH" -> "تغيّر ملف اختبار بعد توقيعه — إن كان تعديلك مقصوداً أعد gate_sign_tests."
        "TEST_TAMPERED" -> "تعديل غير موقّع لملف اختبار — توقيع HMAC لا يطابق."
        "TEST_MISSING" -> "ملف اختبار موقّع سابقاً لم يعد موجوداً."
        else -> ""
    }
}
