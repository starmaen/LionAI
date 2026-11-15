package com.lion.ai

internal enum class WorkVisualState { RUNNING, SUCCESS, ERROR, PERMISSION, PROVIDER }

/** [glyph] أيقونة الحالة التقنية (تفكير/كتابة/ملف...) تُرسم بدل الرمز التعبيري التقليدي. */
internal data class WorkVisual(
    val title: String,
    val detail: String,
    val state: WorkVisualState,
    val glyph: StateGlyph = StateGlyph.GENERIC
)

internal fun toolDisplayName(tool: String): String {
    val t = tool.lowercase()
    return when {
        tool == "provider" -> "متابعة مع مزود بديل"
        tool == "note" -> "ملاحظة تنفيذية"
        tool == "file_present" -> "تجهيز المخرجات"
        tool == "code_run" -> "تشغيل الكود"
        "tree" in t || "list" in t || "glob" in t || "grep" in t || "search" in t -> "فحص مساحة العمل"
        "read" in t || "view" in t || "cat" == t || "open" in t -> "قراءة ملف"
        "patch" in t || "edit" in t || "replace" in t -> "تعديل ملف"
        "write" in t || "create" in t || "save" in t -> "كتابة ملف"
        "push" in t || "commit" in t || "upload" in t || "release" in t || "merge" in t || "pull_request" in t -> "رفع التعديلات"
        "build" in t || "workflow" in t || "gradle" in t || "run_status" in t || "actions" in t -> "متابعة البناء"
        "fix" in t || "heal" in t || "doctor" in t || "diagnose" in t || "repair" in t -> "إصلاح أخطاء"
        "shell" in t || "exec" in t || "command" in t || "bash" in t -> "تنفيذ أمر"
        "zip" in t || "unzip" in t || "archive" in t -> "ضغط/فك ملفات"
        else -> tool.removePrefix("github_").replace('_', ' ').ifBlank { "خطوة عمل" }
    }
}

/** يبني العرض من السجل الدائم أولاً، ثم يضيف العملية الجارية فقط؛ لا يكرر آخر النتائج. */
internal fun buildWorkVisuals(lines: List<String>, steps: List<AgentStep>): List<WorkVisual> {
    val items = steps.filterNot { it.tool == "provider" }.filter { it.tool != "note" || it.summary.contains("بانتظار رد") }.takeLast(5).map { step ->
        val permission = step.summary.contains("بانتظار رد") || step.summary.contains("إذن") || step.summary.contains("موافقة")
        WorkVisual(
            toolDisplayName(step.tool),
            StateGlyphs.removePictographs(
                (step.path.takeIf { it.isNotBlank() }?.let { "$it — " }.orEmpty() + step.summary).take(180)
            ),
            when {
                permission -> WorkVisualState.PERMISSION
                step.tool == "provider" -> WorkVisualState.PROVIDER
                !step.ok -> WorkVisualState.ERROR
                else -> WorkVisualState.SUCCESS
            },
            when {
                permission -> StateGlyph.ASK
                !step.ok -> StateGlyph.ERROR
                else -> StateGlyphs.forTool(step.tool)
            }
        )
    }.toMutableList()
    val active = lines.lastOrNull { line ->
        val head = StateGlyphs.leadingSymbols(line).firstOrNull()
        // ✅ و❌ نتائج منتهية؛ ما سواها عملية جارية (⏳ 🧠 🛠️ 💬 ...).
        head != 0x2705 && head != 0x274C
    }
    if (active != null) {
        val clean = StateGlyphs.removePictographs(StateGlyphs.clean(active))
        items += WorkVisual(
            clean.ifBlank { "تنفيذ المهمة" },
            "العملية جارية؛ تُحفظ النتيجة عند اكتمالها",
            WorkVisualState.RUNNING,
            StateGlyphs.forLine(active)
        )
    } else if (items.isEmpty()) {
        lines.takeLast(5).forEach { line ->
            val failed = StateGlyphs.leadingSymbols(line).firstOrNull() == 0x274C
            items += WorkVisual(
                StateGlyphs.removePictographs(StateGlyphs.clean(line)),
                "",
                if (failed) WorkVisualState.ERROR else WorkVisualState.SUCCESS,
                if (failed) StateGlyph.ERROR else StateGlyphs.forLine(line).takeIf { it != StateGlyph.GENERIC }
                    ?: StateGlyph.SUCCESS
            )
        }
    }
    return items.takeLast(6)
}
