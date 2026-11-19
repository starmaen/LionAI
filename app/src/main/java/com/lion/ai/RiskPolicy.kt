package com.lion.ai

/**
 * سياسة المفاصل الخطرة: عمل ديناميكي مرن داخل حدود طلب المستخدم وإذنه.
 *
 * - الأوامر الواضحة تُنفَّذ مباشرة (لا بطاقة على كل خطوة).
 * - العملية الخطرة (حذف/استبدال/دفع مدمّر/أمر صدفة مدمّر/مسار توقيع) تُنفَّذ دون سؤال فقط إذا كان المستخدم
 *   سمّى هدفها صراحةً في طلبه («احذف الفرع X»)؛ وإلا تُعرض بطاقة قرار واحدة تُوضّح الفكرة وتنتظر موافقته.
 * - موافقة المستخدم تخصّ هذه العملية بعينها ولا تعمّم على بقية المهمة.
 */
internal object RiskPolicy {
    data class Risk(val key: String, val label: String, val targets: List<String>)

    private val SENSITIVE_PATH = Regex("(?i)(keystore|\\.jks|\\.keystore|\\.env\\b|secrets?|signing|id_rsa|\\.pem)")
    private val DESTRUCTIVE_SHELL = Regex(
        "(?i)(\\brm\\s+-[a-z]*[rf]|\\bgit\\s+push\\b.*(--force|-f\\b)|\\bgit\\s+reset\\s+--hard|\\bgit\\s+clean\\b|" +
            "\\bchmod\\s+-R|\\bmkfs|\\bdd\\s+if=|\\bsudo\\b|\\|\\s*(sh|bash)\\b|>\\s*/dev/)"
    )

    fun assess(call: ToolCall): Risk? {
        val a = call.args
        fun s(k: String) = a.optString(k).trim()
        val path = s("path").ifBlank { s("from") }
        val (label, targets) = when (call.name) {
            "file_delete" -> ("حذف ${s("path")}" + if (a.optBoolean("recursive")) " بكل محتوياته" else "") to listOf(s("path"))
            "file_move" ->
                if (a.optBoolean("overwrite")) ("نقل ${s("from")} إلى ${s("to")} مع استبدال الموجود" to listOf(s("from"), s("to"))) else (null to emptyList())
            "github_delete_file" -> ("حذف الملف ${s("path")} من المستودع" to listOf(s("path")))
            "github_delete_branch" -> ("حذف الفرع ${s("branch")}" to listOf(s("branch")))
            "github_delete_branches" -> ("حذف عدة فروع من المستودع" to listOf(a.optString("branches")))
            "github_push_workspace" ->
                if (a.optBoolean("delete_missing") || a.optBoolean("confirm_large"))
                    ("رفع مجلد كامل إلى المستودع" +
                        (if (a.optBoolean("delete_missing")) " مع حذف ما ليس موجوداً محلياً" else "") +
                        (if (a.optBoolean("confirm_large")) " (دفعة كبيرة)" else "")) to listOf(s("path"), s("repo"))
                else (null to emptyList())
            "shell_exec", "execute_command" ->
                if (DESTRUCTIVE_SHELL.containsMatchIn(s("command"))) ("أمر صدفة قد يكون مدمّراً: ${s("command").take(160)}" to listOf(s("command")))
                else (null to emptyList())
            "web_api" ->
                if (AiTools.isMutation(call)) ("طلب ${s("method").uppercase()} خارجي إلى ${s("url").take(80)}" to listOf(s("url"))) else (null to emptyList())
            else -> (null to emptyList())
        }
        val sensitive = AiTools.isMutation(call) && path.isNotBlank() && SENSITIVE_PATH.containsMatchIn(path)
        if (label == null && !sensitive) return null
        val finalLabel = label ?: "تعديل مسار حساس (توقيع/أسرار): $path"
        val tg = if (label == null) listOf(path) else targets
        return Risk(call.name + "|" + a.toString().hashCode().toString(16), finalLabel, tg.filter { it.isNotBlank() })
    }

    /** المستخدم سمّى هدف العملية صراحةً (اسم الملف/الفرع/المسار) في طلبه نفسه. */
    private val DELETE_VERB = Regex("(?:حذف|احذف|ازل|أزل|إزالة|ازاله|امسح|مسح|remove|delete)")

    fun requestNames(risk: Risk, userRequest: String): Boolean {
        val req = userRequest.lowercase()
        if (req.isBlank()) return false
        // طلب حذف صريح يُجيز حذف ملف/مقطع من المشروع (غير الفروع/المستودعات/الأسرار): لا بطاقة على كل خطوة.
        if (DELETE_VERB.containsMatchIn(req) && (risk.key.startsWith("file_delete|") || risk.key.startsWith("github_delete_file|")) &&
            !risk.label.contains("بكل محتوياته") && !SENSITIVE_PATH.containsMatchIn(risk.label)) return true
        return risk.targets.any { t ->
            val tl = t.lowercase().trim()
            tl.length >= 3 && (req.contains(tl) || req.contains(tl.substringAfterLast('/')))
        }
    }
}
