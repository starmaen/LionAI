package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
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
    var mode: String = ChatMode.GENERAL.id
        private set

    @Volatile
    var completed: Boolean = false
        private set

    @Volatile
    var updatedAt: Long = System.currentTimeMillis()
        private set

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
        completed = false
        updatedAt = System.currentTimeMillis()
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
        completed = false
        updatedAt = System.currentTimeMillis()
    }

    /** يثبّت وضع المحادثة داخل الجلسة حتى لا ينتقل سياق البرمجة إلى وضع عام. */
    @Synchronized
    fun setMode(mode: ChatMode) {
        this.mode = mode.id
        updatedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun markCompleted() {
        completed = true
        updatedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun stepCount(): Int = steps.size

    /** تصدير الحالة إلى JSON ليُخزَّن في checkpoint دائم. */
    @Synchronized
    fun toJson(): JSONObject {
        val stepsArray = JSONArray()
        steps.forEach { step ->
            stepsArray.put(
                JSONObject()
                    .put("tool", step.tool)
                    .put("ok", step.ok)
                    .put("summary", step.summary)
                    .put("path", step.path)
                    .put("at", step.at)
            )
        }
        return JSONObject()
            .put("chat_id", chatId)
            .put("mode", mode)
            .put("task", lastTask)
            .put("provider", lastProvider)
            .put("pending", pendingQuestion ?: "")
            .put("steps", stepsArray)
            .put("files", JSONArray(files.toList()))
            .put("providers", JSONArray(providers))
            .put("failures", JSONArray(failures))
            .put("completed", completed)
            .put("updated_at", updatedAt)
    }

    @Synchronized
    fun importJson(obj: JSONObject) {
        reset()
        mode = obj.optString("mode", ChatMode.GENERAL.id)
        lastTask = obj.optString("task", "")
        lastProvider = obj.optString("provider", "")
        pendingQuestion = obj.optString("pending", "").ifBlank { null }
        completed = obj.optBoolean("completed", false)
        updatedAt = obj.optLong("updated_at", System.currentTimeMillis())
        obj.optJSONArray("steps")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                steps.add(
                    AgentStep(
                        tool = item.optString("tool", ""),
                        ok = item.optBoolean("ok", false),
                        summary = item.optString("summary", ""),
                        path = item.optString("path", ""),
                        at = item.optLong("at", 0L)
                    )
                )
            }
        }
        obj.optJSONArray("files")?.let { array ->
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let { files.add(it) }
            }
        }
        obj.optJSONArray("providers")?.let { array ->
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let { providers.add(it) }
            }
        }
        obj.optJSONArray("failures")?.let { array ->
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let { failures.add(it) }
            }
        }
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
        updatedAt = System.currentTimeMillis()
        completed = false
    }

    @Synchronized
    fun recordNote(text: String) {
        if (text.isBlank()) return
        steps.add(AgentStep("note", true, text.replace(Regex("\\s+"), " ").trim().take(300)))
        while (steps.size > 60) steps.removeAt(0)
        updatedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun askQuestion(question: String) {
        pendingQuestion = question.trim().take(1200)
        recordNote("بانتظار رد المستخدم: ${question.trim().take(200)}")
        updatedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun providerFailed(provider: String, reason: String) {
        if (provider.isNotBlank()) {
            failures.add(provider + ": " + reason.replace(Regex("\\s+"), " ").trim().take(140))
        }
        while (failures.size > 6) failures.removeAt(0)
        updatedAt = System.currentTimeMillis()
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
        builder.append(
            if (completed) "الحالة: ✅ اكتملت آخر مهمة؛ الرسالة الحالية غالباً متابعة أو تعديل أو مهمة جديدة، فابنِ على الملفات المتغيرة أدناه.\n"
            else "الحالة: ⏸ لم تكتمل؛ أكمل من آخر نقطة.\n"
        )
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

    companion object {
        fun fromJson(obj: JSONObject): AgentSession {
            val chatId = obj.optString("chat_id", "").ifBlank { "checkpoint" }
            return AgentSession(chatId).also { it.importJson(obj) }
        }
    }
}

/** سجل الجلسات الحيّة في الذاكرة، مفتاحه معرّف المحادثة. */
object AgentSessions {
    private val sessions = ConcurrentHashMap<String, AgentSession>()

    fun of(chatId: String): AgentSession = sessions.getOrPut(chatId) { AgentSession(chatId) }

    fun of(prefs: android.content.SharedPreferences, chatId: String): AgentSession {
        val existing = sessions[chatId]
        if (existing != null) return existing
        val restored = CheckpointStore.load(prefs, chatId)
        return if (restored != null) {
            sessions[chatId] = restored
            restored
        } else {
            sessions.getOrPut(chatId) { AgentSession(chatId) }
        }
    }

    fun save(prefs: android.content.SharedPreferences, chatId: String) {
        sessions[chatId]?.let { CheckpointStore.save(prefs, it) }
    }

    fun peek(chatId: String): AgentSession? = sessions[chatId]

    fun hasProgress(chatId: String): Boolean = sessions[chatId]?.hasWork() == true

    fun clear(chatId: String) {
        sessions.remove(chatId)
    }

    fun clear(prefs: android.content.SharedPreferences, chatId: String) {
        sessions.remove(chatId)
        CheckpointStore.delete(prefs, chatId)
    }
}
