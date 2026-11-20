package com.lion.ai

import org.json.JSONObject

/**
 * يشرح بطاقة الإذن بالعربية: **ماذا** سيحدث، **لماذا** يُطلب الإذن الآن، **ما البديل**،
 * وما **مدى** الإذن (هل يخص هذه العملية أم يمتد للمهمة). سابقاً كانت البطاقة تقول
 * «هل تسمح لي بتنفيذ التعديلات؟» بلا سبب ولا نطاق ولا بديل، فيوافق المستخدم أو يرفض
 * بلا فهم. هنا يُبنى نص البطاقة من بيانات الأداة نفسها (نوعها وهدفها ومعاملاتها).
 */
internal object PermissionReason {

    /** وصف مقروء لهدف العملية من معاملات الأداة. */
    fun target(call: ToolCall): String {
        val a: JSONObject = call.args
        fun s(k: String) = a.optString(k).trim()
        return when (call.name) {
            "github_push_workspace", "github_commit_files", "github_write_file" ->
                listOf(s("repo").ifBlank { "المستودع الافتراضي" }, s("branch").ifBlank { "الفرع الافتراضي" })
                    .joinToString(" · ") + s("path").let { if (it.isNotBlank()) " · $it" else "" }
            "file_edit", "file_write", "file_apply_patch", "file_copy", "file_move", "file_delete", "file_mkdir" ->
                s("path").ifBlank { s("from").ifBlank { "ملف المشروع" } }
            "shell_exec", "execute_command", "code_run" -> s("command").replace('\n', ' ').take(160)
            else -> s("path").ifBlank { s("repo").ifBlank { s("branch").ifBlank { s("url").ifBlank { s("resource") } } } }
        }.ifBlank { "أداة ${call.name}" }
    }

    /** سبب طلب الإذن بحسب نوع الأداة والهدف. */
    fun why(call: ToolCall): String = when {
        call.name == "github_push_workspace" || call.name == "github_commit_files" ->
            "هذه العملية تُنشئ commit حقيقياً على GitHub فيتغيّر المستودع عن بُعد، ويبدأ بناء CI تلقائياً. " +
                "لا يمكن التراجع عنها بحذف محلي: التراجع يحتاج commit عكسياً."
        call.name.startsWith("github_delete") ->
            "حذف على المستودع البعيد لا يمكن التراجع عنه من التطبيق."
        call.name == "github_merge_pr" ->
            "الدمج في الفرع الأساسي يدمج الكود فوراً ويؤثر في كل من يستخدم المستودع."
        call.name in setOf("file_delete", "file_move") ->
            "تعديل مدمر للملفات المحلية: قد تحل محل نسخة أحدث أو تحذف عملاً سابقاً."
        call.name in setOf("shell_exec", "execute_command") ->
            "أمر تنفيذي على جهازك بصلاحيات التطبيق؛ قد يعدّل ملفات أو يثبّت حزماً أو يحذف بيانات."
        call.name == "code_run" ->
            "تشغيل كود سيكتب ملفات أو ينفذ أوامر على جهازك."
        call.name == "web_api" ->
            "طلب شبكة يرسل بيانات إلى خدمة خارجية، وقد يعدّل حالة هناك."
        else -> "أداة تعديل خارجية تُغيّر حالةً بعيدة أو محلية لا يمكن التراجع عنها بسهولة."
    }

    /** البديل الأقل أثراً إن أراد المستخدم الرفض دون إيقاف المهمة. */
    fun alternative(call: ToolCall): String = when {
        call.name == "github_push_workspace" -> "أو ارفض، وأكتفي بتجهيز الملفات محلياً وإعطائك patch ترفعه بنفسك."
        call.name in setOf("shell_exec", "execute_command", "code_run") -> "أو ارفض، وأعرض الأمر عليك لتنفذه يدوياً بنفسك."
        call.name.startsWith("github_delete") || call.name == "github_merge_pr" -> "أو ارفض، وأكمل بقية المهمة دون هذا الإجراء الحسّاس."
        else -> "أو ارفض، وأكتفي بالفحص والقراءة دون أي كتابة."
    }

    /** نص البطاقة الكامل — سؤال واحد يفهم منه المستخدم ماذا يوافق عليه. */
    fun card(title: String, call: ToolCall, scope: String = "هذه العملية وحدها"): String = buildString {
        append("🔐 ").append(title).append('\n')
        append("• الأدوات: ").append(call.name).append('\n')
        append("• الهدف: ").append(target(call)).append('\n')
        append("• لماذا الآن: ").append(why(call)).append('\n')
        append("• لو رفضت: ").append(alternative(call)).append('\n')
        append("• نطاق الإذن: ").append(scope).append('\n')
        append("اختر «نعم» للمتابعة أو «لا» لغير ذلك.")
    }

    /**
     * سؤال إذن الكتابة العام (عندما يطلب النموذج تعديلاً بلا إذن سابق). يبيّن أن الموافقة
     * تخص هذه المهمة، وأن الرفض يوقف الكتابة فقط دون القراءة والفحص.
     */
    fun writeCard(blocked: ToolCall): String = buildString {
        append("🔐 أحتاج إذنك لتنفيذ تعديل على ملفات/مستودع.\n")
        append("• الهدف: ").append(target(blocked)).append('\n')
        append("• لماذا الآن: ").append(why(blocked)).append('\n')
        append("• نطاق الإذن: هذه المهمة الحالية فقط؛ يلغى عند بدء مهمة جديدة أو عند كتابة «توقف».\n")
        append("• لو رفضت: أكتفي بالقراءة والفحص، ولن أكتب شيئاً.\n")
        append("• ملاحظة: الموافقة هنا لا تُغيّر إعدادات الوصول في التطبيق.\n")
        append("اختر «نعم» أو «لا».")
    }

    /** بطاقة الوصول لموصل/صلاحية غير متاحة: تُذكر وجهة التفعيل بدقة. */
    fun accessCard(resource: String, reason: String): String = buildString {
        append("🔐 أحتاج وصولاً إلى: ").append(resource).append('\n')
        if (reason.isNotBlank()) append("• السبب المعلن: ").append(reason).append('\n')
        append("• لماذا: الأداة المطلوبة تعتمد على هذا الوصول ولا يمكن تشغيلها بدونه.\n")
        append("• كيف تُفعّله: إعدادات التطبيق ← ").append(
            when {
                resource.contains("github", true) -> "التكاملات ← GitHub ← ربط التوكن"
                resource.contains("كتابة", true) || resource.contains("write", true) -> "أدوات المطوّر ← السماح للذكاء بالكتابة"
                else -> "الموصلات ← " + resource
            }
        ).append('\n')
        append("• الموافقة هنا لا تُغيّر الإعدادات؛ فعّل الوصول أولاً ثم اختر «تابع».\n")
        append("اختر «تابع» بعد التفعيل أو «لا» لتخطي هذا الطلب.")
    }
}
