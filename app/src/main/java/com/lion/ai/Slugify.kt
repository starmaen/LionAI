package com.lion.ai

object Slugify {
    fun toSlug(text: String): String {
        // تحويل الأحرف الإنجليزية إلى صغيرة والاحتفاظ بالأحرف العربية والأرقام
        val sb = StringBuilder()
        var prevHyphen = false
        for (c in text.lowercase()) {
            when {
                c.isLetterOrDigit() -> {
                    sb.append(c)
                    prevHyphen = false
                }
                c.isWhitespace() || !c.isLetterOrDigit() -> {
                    if (!prevHyphen) {
                        sb.append('-')
                        prevHyphen = true
                    }
                }
            }
        }
        val result = sb.toString()
        // إزالة الشرطات من الطرفين
        return if (result.startsWith('-')) result.substring(1) else if (result.endsWith('-')) result.substring(0, result.length - 1) else result
    }
}