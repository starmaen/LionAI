package com.lion.ai

internal enum class WorkVisualState { RUNNING, SUCCESS, ERROR, PERMISSION, PROVIDER }
internal data class WorkVisual(val title: String, val detail: String, val state: WorkVisualState)

internal fun toolDisplayName(tool: String): String = when (tool) {
    "provider" -> "متابعة مع مزود بديل"
    "file_read", "read_file", "github_read_file" -> "قراءة ملف"
    "file_write", "write_file", "github_write_file" -> "كتابة ملف"
    "file_tree", "file_list", "list_dir" -> "فحص مساحة العمل"
    "shell_exec", "execute_command" -> "تنفيذ أمر"
    "code_run" -> "تشغيل الكود"
    "file_present" -> "تجهيز المخرجات"
    "note" -> "ملاحظة تنفيذية"
    else -> tool.removePrefix("github_").replace('_', ' ').ifBlank { "خطوة عمل" }
}

/** يبني العرض من السجل الدائم أولاً، ثم يضيف العملية الجارية فقط؛ لا يكرر آخر النتائج. */
internal fun buildWorkVisuals(lines: List<String>, steps: List<AgentStep>): List<WorkVisual> {
    val items = steps.takeLast(5).map { step ->
        val permission = step.summary.contains("بانتظار رد") || step.summary.contains("إذن") || step.summary.contains("موافقة")
        WorkVisual(
            toolDisplayName(step.tool),
            (step.path.takeIf { it.isNotBlank() }?.let { "$it — " }.orEmpty() + step.summary).take(180),
            when {
                permission -> WorkVisualState.PERMISSION
                step.tool == "provider" -> WorkVisualState.PROVIDER
                !step.ok -> WorkVisualState.ERROR
                else -> WorkVisualState.SUCCESS
            }
        )
    }.toMutableList()
    val active = lines.lastOrNull { !it.startsWith("✅") && !it.startsWith("❌") }
    if (active != null) {
        val clean = active.removePrefix("⏳").trim().removePrefix("🛠️").trim()
        items += WorkVisual(clean.ifBlank { "تنفيذ المهمة" }, "العملية جارية؛ تُحفظ النتيجة عند اكتمالها", WorkVisualState.RUNNING)
    } else if (items.isEmpty()) {
        lines.takeLast(5).forEach { line ->
            items += WorkVisual(line.removePrefix("✅").removePrefix("❌").trim(), "", if (line.startsWith("❌")) WorkVisualState.ERROR else WorkVisualState.SUCCESS)
        }
    }
    return items.takeLast(6)
}
