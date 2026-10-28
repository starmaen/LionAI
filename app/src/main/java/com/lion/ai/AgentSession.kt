package com.lion.ai

import java.util.concurrent.ConcurrentHashMap

/** خطوة واحدة نفّذها الوكيل داخل جلسة العمل. */
data class AgentStep(
    val tool: String,
    val ok: Boolean,
    val summary: String,
    val path: String = "",
    val at: Long = System.currentTimeMillis()
)

/**
 * جلسة عمل واحدة مرتبطة بمحادثة واحدة.
 *
 * الهدف: إذا توقف المزود الحالي في منتصف مهمة، يكمل المزود التالي من نفس النقطة
 * بدل البدء من الصفر. تحفظ الجلسة الخطوات الناجحة، الملفات التي تغيّرت، المزودات
 * التي جُرّبت، وأي سؤال معلّق ينتظر رد المستخدم.
 *
 * لا تحفظ أسراراً ولا محتوى ملفات كاملاً، بل ملخصات قصيرة فقط.
 */
class AgentSession(val chatId: String) {
    private val steps = mutableListOf<AgentStep>()
    private val files = LinkedHashSet<String>()
    private val providers = mutableListOf<String>()
    private val failures = mutableListOf<String>()

    @Volatile
    var pendingQuestion: String? = null
        private set

    @Volatile
    var lastTask: String = ""
        private set

    @Volatile
    var lastProvider: String = ""
        private set

    @Synchronized
    fun hasWork(): Boolean = pendingQuestion != null || steps.any { it.tool != "user" }

    @Synchronized
    fun reset() {
        steps.clear()
        files.clear()
        providers.clear()
        failures.clear()
        pendingQuestion = null
        lastTask = ""
        lastProvider = ""
    }

    /**
     * يُستدعى عند بداية كل محاولة (مزود جديد أو دور جديد للمستخدم).
     * - إذا كان هناك سؤال معلّق فهذه الرسالة إجابة عليه، فنبقي الجلسة.
     * - إذا كانت الرسالة استمراراً للمهمة نبقي الجلسة.
     * - غير ذلك: مهمة جديدة، نبدأ بسياق نظيف.
     */
    @Synchronized
    fun start(task: String, provider: String) {
        val clean = task.replace(Regex("\\s+"), " ").trim().take(600)
        val question = pendingQuestion
        if (question != null) {
            steps.add(AgentStep("user", true, "إجابة المستخدم على سؤالي «${question.take(160)}»: ${clean.take(300)}"))
            pendingQuestion = null
        }
        // لا نصفر الجلسة عند تغيّر صياغة الطلب: الأهم ألا تُفقد مهمة أو تعديلات.
        // تعليمات الاستئناف تخبر النموذج صراحةً كيف يتعامل مع مهمة جديدة تماماً.
        if (lastTask.isBlank()) lastTask = clean
        if (provider.isNotBlank() && providers.lastOrNull() != provider) providers.add(provider)
        if (provider.isNotBlank()) lastProvider = provider
    }

    /** يسجّل نتيجة أداة (نجاحاً أو فشلاً) مع ملخص قصير ومسار إن وُجد. */
    @Synchronized
    fun recordTool(tool: String, ok: Boolean, output: String, path: String = "") {
        val summary = output.replace(Regex("\\s+"), " ").trim().take(240)
        steps.add(AgentStep(tool, ok, summary, path.trim().take(160)))
        if (ok && path.isNotBlank() && tool != "file_read" && tool != "github_read_file") {
            files.add(path.trim().take(160))
        }
        while (steps.size > 60) steps.removeAt(0)
    }

    @Synchronized
    fun recordNote(text: String) {
        if (text.isBlank()) return
        steps.add(AgentStep("note", true, text.replace(Regex("\\s+"), " ").trim().take(300)))
        while (steps.size > 60) steps.removeAt(0)
    }

    @Synchronized
    fun askQuestion(question: String) {
        pendingQuestion = question.trim().take(1200)
        recordNote("بانتظار رد المستخدم: ${question.trim().take(200)}")
    }

    @Synchronized
    fun providerFailed(provider: String, reason: String) {
        if (provider.isNotBlank()) {
            failures.add(provider + ": " + reason.replace(Regex("\\s+"), " ").trim().take(140))
        }
        while (failures.size > 6) failures.removeAt(0)
    }

    @Synchronized
    fun touchedFiles(): List<String> = files.toList()

    /**
     * كتلة سياق جاهزة تُحقن في تعليمات النظام ليعرف المزود البديل ما جرى فعلاً.
     * قصيرة دائماً (أقل من maxChars) حتى لا تستهلك نافذة السياق.
     */
    @Synchronized
    fun contextBlock(maxChars: Int = 4000): String {
        if (!hasWork()) return ""
        val builder = StringBuilder()
        builder.append("\n\n=== 🧵 جلسة عمل مستمرة (نفس المحادثة) ===\n")
        if (lastTask.isNotBlank()) {
            builder.append("المهمة الأصلية: ").append(lastTask.take(500)).append('\n')
        }
        if (providers.isNotEmpty()) {
            builder.append("المزودات التي جُرّبت بالترتيب: ").append(providers.joinToString(" ← ")).append('\n')
        }
        if (failures.isNotEmpty()) {
            builder.append("محاولات فاشلة: ").append(failures.joinToString(" | ")).append('\n')
        }
        if (files.isNotEmpty()) {
            builder.append("ملفات/مسارات تغيّرت بنجاح: ")
                .append(files.take(20).joinToString("، ")).append('\n')
        }
        pendingQuestion?.let {
            builder.append("سؤال معلّق للمستخدم: ").append(it.take(400)).append('\n')
        }
        val recent = steps.takeLast(14)
        if (recent.isNotEmpty()) {
            builder.append("آخر الخطوات:\n")
            recent.forEach { step ->
                builder.append(if (step.ok) "• ✅ " else "• ❌ ").append(step.tool)
                if (step.path.isNotBlank()) builder.append(' ').append(step.path)
                builder.append(" — ").append(step.summary).append('\n')
            }
        }
        builder.append(
            "قواعد الاستئناف: هذه مهمة لم تكتمل بعد؛ أكملها من آخر نقطة. لا تُعد تنفيذ خطوة نجحت، " +
                "واقرأ/تحقق من الملفات المتغيرة قبل أي تعديل جديد. لا تدّعِ إنجاز شيء إلا إذا أعادت أداة نتيجة تؤكده. " +
                "إن كانت رسالة المستخدم الحالية تطلب مهمة مختلفة تماماً فابدأ بها واعتبر ما سبق سجلاً تاريخياً.\n"
        )
        return builder.toString().take(maxChars.coerceIn(800, 8000))
    }
}

/** سجل الجلسات الحيّة في الذاكرة، مفتاحه معرّف المحادثة. */
object AgentSessions {
    private val sessions = ConcurrentHashMap<String, AgentSession>()

    fun of(chatId: String): AgentSession = sessions.getOrPut(chatId) { AgentSession(chatId) }

    fun peek(chatId: String): AgentSession? = sessions[chatId]

    fun hasProgress(chatId: String): Boolean = sessions[chatId]?.hasWork() == true

    fun clear(chatId: String) {
        sessions.remove(chatId)
    }
}
