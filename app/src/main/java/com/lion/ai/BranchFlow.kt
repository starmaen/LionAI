package com.lion.ai

import android.content.SharedPreferences

/**
 * مسار GitHub الآمن: كل كتابة تذهب إلى فرع عمل لا إلى الفرع الافتراضي،
 * ويُفتح PR تلقائياً (فيبدأ البناء)، ولا يُدمج إلا بعد نجاح بناء الدفعة الأخيرة.
 * الفرض ميكانيكي في الأدوات نفسها فلا يعتمد على التزام المزود بالتعليمات.
 */
internal object BranchFlow {
    /** مفتاح إعداد يسمح بالدفع المباشر إلى الفرع الافتراضي (افتراضه مغلق). */
    const val ALLOW_MAIN = "allow_main_push"

    data class Prep(val branch: String, val note: String)

    private fun key(ctx: ToolContext, owner: String, repo: String): String =
        "${ctx.chatId.ifBlank { "x" }}_${owner}/${repo}".lowercase()

    private fun workName(ctx: ToolContext): String =
        "lion/" + (ctx.chatId.filter { it.isLetterOrDigit() }.takeLast(8).ifBlank { "work" }) +
            "-" + java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US).format(java.util.Date())

    /** فرع العمل الحالي لهذه المحادثة والمستودع، إن وُجد. */
    fun current(prefs: SharedPreferences, ctx: ToolContext, owner: String, repo: String): String =
        prefs.getString("wb_" + key(ctx, owner, repo), "").orEmpty()

    /** الفرع الذي تُكتب عليه الدفعة: المطلوب إن كان غير افتراضي، وإلا فرع العمل (يُنشأ عند الحاجة). */
    fun prepare(ctx: ToolContext, token: String, owner: String, repo: String, requested: String): Prep {
        val prefs = ctx.prefs
        if (prefs.getBoolean(ALLOW_MAIN, false)) return Prep(requested, "")
        val default = runCatching { GitHubClient.getRepo(token, owner, repo).defaultBranch }.getOrDefault("")
        val wanted = requested.trim()
        // فشل مغلق: إن لم يُعرف الفرع الافتراضي فلا نخاطر بالكتابة عليه.
        if (default.isBlank()) {
            if (wanted.isNotBlank() && wanted !in setOf("main", "master")) return Prep(wanted, "")
            throw IllegalStateException("⛔ تعذّر تحديد الفرع الافتراضي للمستودع، فرفضت الكتابة احتياطاً كي لا تُعدَّل main. أعد المحاولة أو حدّد فرعاً غير رئيسي.")
        }
        if (wanted.isNotBlank() && wanted != default) return Prep(wanted, "")
        val k = key(ctx, owner, repo)
        var branch = prefs.getString("wb_$k", "").orEmpty()
        if (branch.isNotBlank()) return Prep(branch, "")
        branch = workName(ctx)
        return try {
            GitHubClient.createBranch(token, owner, repo, branch, default)
            prefs.edit().putString("wb_$k", branch).remove("wbpr_$k").apply()
            Prep(branch, "ℹ️ لم يُدفع إلى $default: أُنشئ فرع العمل «$branch» وسيُفتح PR تلقائياً.")
        } catch (e: Exception) {
            // الاستثناء الوحيد: مستودع فارغ لا فرع فيه بعد (الدفعة الأولى لا بديل لها). غير ذلك = رفض.
            val empty = runCatching { GitHubClient.listBranches(token, owner, repo).isEmpty() }.getOrDefault(false)
            if (!empty) throw IllegalStateException("⛔ تعذّر إنشاء فرع العمل (${e.message?.take(100).orEmpty()}) فلم أكتب على $default. أعد المحاولة أو أنشئ فرعاً بـ github_create_branch وادفع إليه.")
            Prep(wanted, "ℹ️ المستودع فارغ فدُفعت الدفعة الأولى إلى $default.")
        }
    }

    /** بعد كتابة ناجحة على فرع العمل: يضمن وجود PR مفتوح ويسجّل وقت الدفع. يعيد سطر معلومة. */
    fun afterWrite(ctx: ToolContext, token: String, owner: String, repo: String, branch: String): String {
        val prefs = ctx.prefs
        val k = key(ctx, owner, repo)
        if (branch.isBlank() || branch != prefs.getString("wb_$k", "")) return ""
        prefs.edit().putLong("wbpush_$k", System.currentTimeMillis()).apply()
        val known = prefs.getInt("wbpr_$k", 0)
        if (known > 0) return "ℹ️ PR #$known مفتوح؛ ابدأ github_wait_run (الفرع $branch) ثم ادمج بعد النجاح."
        return runCatching {
            val existing = GitHubClient.listPulls(token, owner, repo).firstOrNull { it.head == branch }
            val pull = existing ?: GitHubClient.createPull(
                token, owner, repo, "Lion AI: $branch", branch, "",
                "فتحه Lion AI تلقائياً. لا يُدمج قبل نجاح البناء."
            )
            prefs.edit().putInt("wbpr_$k", pull.number).apply()
            "ℹ️ PR #${pull.number}: ${pull.htmlUrl}\nالخطوة التالية: github_wait_run (الفرع $branch) ← عند النجاح github_merge_pr number=${pull.number}؛ عند الفشل build_diagnose ثم أصلح وادفع إلى الفرع نفسه."
        }.getOrElse { "⚠️ لم يُفتح PR تلقائياً (${it.message?.take(100).orEmpty()}). افتحه بـ github_create_pr head=$branch." }
    }

    /** سبب رفض الدمج إن لم ينجح بناء آخر دفعة؛ null = مسموح. */
    fun mergeGate(ctx: ToolContext, token: String, owner: String, repo: String, number: Int): String? {
        val pull = runCatching { GitHubClient.listPulls(token, owner, repo).firstOrNull { it.number == number } }
            .getOrNull() ?: return null
        val runs = runCatching {
            GitHubClient.listWorkflowRuns(token, owner, repo, perPage = 10, branch = pull.head)
        }.getOrDefault(emptyList())
        val latest = runs.firstOrNull() ?: return null // لا CI على هذا الفرع: لا شيء ننتظره
        val pushedAt = ctx.prefs.getLong("wbpush_" + key(ctx, owner, repo), 0L)
        val created = runCatching { java.time.Instant.parse(latest.createdAt).toEpochMilli() }.getOrDefault(0L)
        return when {
            pushedAt > 0 && created + 120_000L < pushedAt ->
                "⛔ لا دمج: لم يظهر بعد بناء للدفعة الأخيرة على ${pull.head}. استخدم github_wait_run بهذا الفرع أولاً."
            latest.status != "completed" ->
                "⛔ لا دمج: البناء #${latest.number} ما زال ${latest.status}. استخدم github_wait_run بالفرع ${pull.head}."
            latest.conclusion != "success" ->
                "⛔ لا دمج: آخر بناء #${latest.number} انتهى بـ ${latest.conclusion}. شغّل build_diagnose على سجله، أصلح، ادفع إلى ${pull.head}، ثم انتظر النجاح."
            else -> null
        }
    }

    /** بعد دمج ناجح: تُنسى مراجع فرع العمل ليبدأ العمل التالي بفرع جديد من الافتراضي المحدّث. */
    fun afterMerge(ctx: ToolContext, owner: String, repo: String) {
        val k = key(ctx, owner, repo)
        ctx.prefs.edit().remove("wb_$k").remove("wbpr_$k").remove("wbpush_$k").apply()
    }
}
