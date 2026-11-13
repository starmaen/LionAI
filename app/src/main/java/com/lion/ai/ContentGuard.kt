package com.lion.ai

/**
 * حارس الكتابة: يمنع أخطر عطل رأيناه عملياً، وهو أن يكتب النموذج نصاً مؤقتاً («[الكود المحدث]»، «... بقية الكود»)
 * أو جزءاً صغيراً فوق ملف كامل فيُدمَّر الملف ويُرفع إلى GitHub ويفشل البناء.
 * - نصوص الحشو مرفوضة دائماً.
 * - تقليص ملف كبير إلى أقل من 40% مرفوض ما لم يُمرَّر allow_shrink=true صراحةً.
 */
object ContentGuard {
    private val PLACEHOLDERS = listOf(
        Regex("\\[الكود المحدث\\]"),
        Regex("\\.\\.\\.\\s*\\[[^\\]]*(الكود|code|rest)[^\\]]*\\]", RegexOption.IGNORE_CASE),
        Regex("rest of (the )?(code|file)", RegexOption.IGNORE_CASE),
        Regex("(existing|previous|remaining|unchanged) code (here|goes here|unchanged|omitted)", RegexOption.IGNORE_CASE),
        Regex("//\\s*\\.\\.\\.\\s*(existing|rest|same|unchanged|بقية)", RegexOption.IGNORE_CASE),
        Regex("/\\*\\s*تم التعديل"),
        Regex("\\(تحديث\\s+[A-Za-z_]+\\)")
    )
    private val TEXT_EXT = setOf(
        "kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "xml", "json", "md", "yml", "yaml", "sh", "gradle",
        "html", "css", "toml", "properties", "cjs", "mjs"
    )

    /** يعيد سبب الرفض أو null. */
    fun problem(path: String, old: String?, new: String, allowShrink: Boolean = false): String? {
        if (new.isBlank()) return "المحتوى الجديد فارغ؛ لن أكتب ملفاً فارغاً. استخدم file_delete إن أردت حذفه."
        PLACEHOLDERS.firstOrNull { it.containsMatchIn(new) }?.let {
            return "المحتوى يحوي نص حشو بدل الكود الفعلي («${it.find(new)?.value?.take(40)}»). لم أكتب شيئاً؛ أرسل الملف كاملاً أو استخدم file_edit للتعديل الجزئي."
        }
        val ext = path.substringAfterLast('.', "").lowercase()
        if (!allowShrink && old != null && ext in TEXT_EXT && old.length > 1500 && new.length < old.length * 0.4) {
            return "الكتابة ستقلّص $path من ${old.length} إلى ${new.length} حرفاً (أقل من 40%): يبدو أنك أرسلت جزءاً فقط أو نصاً مؤقتاً. " +
                "استخدم file_edit لتعديل موضع محدد، أو أرسل الملف كاملاً، أو مرّر allow_shrink=true إن كان التقليص مقصوداً."
        }
        return null
    }

    fun require(path: String, old: String?, new: String, allowShrink: Boolean = false) {
        problem(path, old, new, allowShrink)?.let { throw IllegalArgumentException(it) }
    }
}
