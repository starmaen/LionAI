package com.lion.ai

import java.io.File

/** حكم صادق على «اكتملت المهمة»: لا يُعلَن إلا بدليل أداة، ولا يُخفى فشل آخر خطوة. */
internal object CompletionGuard {
    data class Verdict(val complete: Boolean, val why: String = "")

    fun verdict(
        mutationIntent: Boolean,
        readOnly: Boolean,
        answerOnly: Boolean,
        toolsUsed: Int,
        okMutations: Int,
        trailingFailures: Int,
        replyBlank: Boolean
    ): Verdict {
        if (toolsUsed <= 0) return Verdict(true)
        val needsWrite = mutationIntent && !readOnly && !answerOnly
        if (trailingFailures > 0 && (needsWrite || replyBlank)) {
            return Verdict(false, "آخر خطوة أداة فشلت ولم تُعالَج بعدها")
        }
        if (needsWrite && okMutations == 0) {
            return Verdict(false, "لا توجد نتيجة أداة ناجحة تؤكد تنفيذ التعديل")
        }
        return Verdict(true)
    }

    /** بدلاً من «اكتملت العملية.» الفارغة حين لا يكتب المزود ملخصاً. */
    const val BLANK_SUMMARY = "انتهت الحلقة دون ملخص من المزود؛ راجع تقرير التنفيذ أدناه للتأكد مما حدث فعلاً."

    /** أدوات يتكرر ناتجها المتطابق بشكل مشروع (استطلاع حالة)، فلا تُحسب حلقة. */
    fun isPolling(tool: String): Boolean =
        listOf("wait_run", "get_run", "status", "poll", "tail").any { tool.contains(it) }

    /** بصمة نتيجة: نفس الأداة + نفس المدخلات + نفس الناتج. */
    fun resultKey(tool: String, args: String, output: String): String =
        tool + "|" + args.hashCode() + "|" + output.take(300).hashCode()

    /** رسالة «ملف غير موجود» مفيدة: بدائل بنفس الاسم + محتوى المجلد الأب. */
    fun missingFileMessage(rootDir: File, path: String): String {
        val sb = StringBuilder("الملف غير موجود: ").append(path)
        val clean = path.trim().removePrefix("/")
        val name = clean.substringAfterLast('/')
        if (name.isBlank()) return sb.toString()
        val similar = runCatching {
            rootDir.walkTopDown()
                .onEnter { !it.name.startsWith(".lion-") && it.name != ".git" && it.name != "node_modules" && it.name != "build" }
                .filter { it.isFile && it.name.equals(name, ignoreCase = true) }
                .take(5)
                .map { it.relativeTo(rootDir).path }
                .toList()
        }.getOrDefault(emptyList())
        if (similar.isNotEmpty()) {
            sb.append("\nملفات بنفس الاسم موجودة في: ").append(similar.joinToString("، "))
            sb.append("\nأعد file_read بأحد هذه المسارات.")
        } else {
            val parent = File(rootDir, clean).parentFile
            val near = if (parent != null && parent.isDirectory) {
                parent.listFiles()?.map { it.name + if (it.isDirectory) "/" else "" }?.sorted()?.take(15).orEmpty()
            } else emptyList()
            if (near.isNotEmpty()) {
                sb.append("\nالمجلد الأب موجود ومحتواه: ").append(near.joinToString("، "))
            } else {
                sb.append("\nلا يوجد ملف بهذا الاسم في مساحة العمل المحلية. استخدم file_tree أو file_search لمعرفة المسار الصحيح،")
                sb.append(" أو github_read_file إن كان الملف في المستودع؛")
                sb.append(" وإن أنشأتَه على فرع عمل آخر (مثل bench/…) فهو غير موجود محلياً على الفرع الحالي —")
                sb.append(" استخدم github_read_file مع تحديد الفرع أو github_pull لإحضار الفرع أولًا.")
            }
        }
        return sb.toString()
    }
}
