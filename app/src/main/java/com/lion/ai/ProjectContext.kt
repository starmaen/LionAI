package com.lion.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * سياق المشروع يُحقن فقط في وضع البرمجة. الوضع العام والتعلّم لا يريان
 * AGENTS.md ولا مسار المشروع ولا Manifest، وهذا شرط العزل على مستوى وقت التشغيل.
 */
object ProjectContext {
    fun block(
        context: Context,
        prefs: SharedPreferences,
        chatId: String,
        manifest: BuildManifest? = ManifestStore.read(prefs, chatId)
    ): String {
        if (manifest == null) return ""
        val builder = StringBuilder()
        builder.append("\n\n=== 📦 سياق المشروع (وضع البرمجة فقط) ===\n")
        builder.append("المشروع: ").append(manifest.project)
        if (manifest.root.isNotBlank()) builder.append(" | الجذر: ").append(manifest.root)
        builder.append(" | Manifest معتمد: ").append(if (manifest.approved) "نعم" else "لا").append('\n')
        if (manifest.entryPoints.isNotEmpty()) {
            builder.append("نقاط الدخول: ").append(manifest.entryPoints.joinToString("، ")).append('\n')
        }
        val agents = runCatching {
            val file = FileTools.file(context, ManifestPaths.join(manifest.root, "AGENTS.md"))
            if (file.isFile && file.length() <= 120_000) file.readText(Charsets.UTF_8) else ""
        }.getOrDefault("")
        if (agents.isNotBlank()) {
            builder.append("\n--- AGENTS.md (تعليمات المشروع) ---\n")
                .append(agents.take(8000)).append('\n')
        }
        val state = PipelineStore.read(prefs, chatId)
        if (state != null) {
            builder.append("دورة التطوير: ").append(state.phase.label)
                .append(" | المهمة: ").append(state.project).append('\n')
        }
        return builder.toString()
    }
}
