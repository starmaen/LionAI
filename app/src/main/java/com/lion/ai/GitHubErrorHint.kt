package com.lion.ai

/**
 * يحوّل رمز خطأ GitHub إلى تشخيص وخطوة تالية صريحة، فيعرف أي مزود (API أو ويب) ماذا يفعل
 * بدل تكرار الطلب نفسه أو تخمين السبب. يُلحق بنص الخطأ في نتيجة الأداة.
 */
object GitHubErrorHint {
    fun explain(status: Int, message: String): String {
        val m = message.lowercase()
        return when {
            status == 401 -> "التوكن غير صالح أو منتهٍ. لا تكرر الطلب؛ أبلغ المستخدم بتجديد التوكن من الإعدادات."
            status == 429 || (status == 403 && ("rate limit" in m || "abuse" in m || "secondary" in m)) ->
                "حد المعدل. توقف عن الطلبات الجديدة، احفظ الحالة (checkpoint)، وأبلغ المستخدم بالانتظار قبل المتابعة."
            status == 403 -> "الصلاحية ناقصة (نطاق التوكن أو حماية الفرع). افحص github_whoami ونطاقات التوكن؛ لا تكرر الطلب نفسه."
            status == 404 -> "المستودع أو الفرع أو المسار غير موجود، أو لا صلاحية عليه (المستودع الخاص يظهر 404). تحقق بـ github_repo_overview و github_list_branches قبل أي إنشاء."
            isShaConflict(status, message) -> "تعارض إصدار: الملف تغيّر. اقرأه بـ github_read_file وأعد تطبيق التعديل على الأحدث، لا تتجاهل الخطأ."
            status == 409 -> "تعارض (الفرع تقدّم أو المستودع فارغ). أعد قراءة آخر commit ثم أعد المحاولة مرة واحدة."
            status == 422 -> "بيانات مرفوضة (فرع موجود مسبقاً، أو مسار/محتوى غير صالح). اقرأ نص الخطأ حرفياً وعدّل المعطيات؛ لا تكرر كما هي."
            status in 500..599 -> "عطل مؤقت لدى GitHub. أعد المحاولة مرة أو مرتين بانتظار قصير، ثم احفظ الحالة وأبلغ."
            else -> ""
        }
    }

    fun isShaConflict(status: Int, message: String): Boolean =
        (status == 409 || status == 422) && message.lowercase().let {
            "sha" in it || "does not match" in it
        }
}
