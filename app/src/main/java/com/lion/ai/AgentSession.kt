package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** خطوة واحدة نفّذها الوكيل داخل جلسة العمل. */
data class AgentStep(
    val tool: String,
    val ok: Boolean,
    val summary: String,
    val path: String = "",
    val at: Long = System.currentTimeMillis(),
    /** كم مرة تكرر الاستدعاء بنفس المدخلات والمخرج (0 = مرة واحدة). يُستخدم لمنع تضخيم السجل. */
    val repeat: Int = 0
)

/**
 * جلسة عمل واحدة مرتبطة بمحادثة واحدة.
 *
 * الهدف: إذا توقف المزود الحالي في منتصف مهمة، يكمل المزود التالي من نفس النقطة
 * بدل البدء من الصفر. تحفظ الجلسة الخطوات الناجحة، الملفات التي تغيّرت، المزودات
 * التي جُرّبت، وأي سؤال معلّق ينتظر رد المستخدم.
 *
 * تحفظ ملخصاً وسجل أدوات للاستئناف داخل تفضيلات التطبيق المشفرة؛ قد يتضمن محتوى ملفات.
 */
class AgentSession(val chatId: String) {
    var continuity = TaskContinuity()
        private set
    private val steps = mutableListOf<AgentStep>()
    private val files = LinkedHashSet<String>()

    /** آخر أداة أثّرت في كل ملف/مسار: يمنح ملخص الاستئناف سياقاً (ماذا جرى على هذا الملف؟). */
    private val fileTools = LinkedHashMap<String, String>()
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
    fun beginTurn(userRequest: String) = beginTurnInternal(null, userRequest)

    /**
     * نسخة التطبيق: تقرأ/تكتب إذن الكتابة الدائم للمحادثة، فلا يضيع الإذن عند اكتمال الجلسة
     * أو إعادة إنشاء الوكيل، ويُلغى فقط برفض صريح أو بدء مهمة جديدة صريحة.
     */
    @Synchronized
    fun beginTurn(prefs: SharedPreferences, userRequest: String) =
        beginTurnInternal(prefs, userRequest)

    private fun beginTurnInternal(prefs: SharedPreferences?, userRequest: String) {
        if (prefs != null && WriteIntent.isNewTask(userRequest)) {
            PermissionStore.clear(prefs, chatId)
        }
        val persisted = prefs != null && PermissionStore.writeGranted(prefs, chatId)
        val followUp = !completed && hasWork() && !WriteIntent.isNewTask(userRequest)
        continuity.begin(
            userRequest,
            !completed,
            pendingQuestion != null || followUp,
            persistedWrite = persisted
        )
        if (prefs != null) {
            if (continuity.writeGranted) PermissionStore.grantWrite(prefs, chatId)
            else if (WriteIntent.isReadOnlyRequest(userRequest)) PermissionStore.revokeWrite(prefs, chatId)
        }
        if (!continuity.resume) lastTask = userRequest.take(600)
        completed = false
    }

    @Synchronized
    fun hasWork(): Boolean = pendingQuestion != null || steps.any { it.tool != "user" }

    @Synchronized
    fun reset() {
        continuity = TaskContinuity()
        steps.clear()
        files.clear()
        fileTools.clear()
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
        if (provider.isNotBlank() && providers.lastOrNull() != provider) {
            val handoff = providers.isNotEmpty()
            providers.add(provider)
            if (handoff) {
                steps.add(AgentStep("provider", true, "توقف المزود السابق وحُفظ العمل. يتابع مزود بديل من النقطة نفسها دون إعادة ما تم."))
            }
        }
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
            .put("continuity", continuity.json())
            .put("chat_id", chatId)
            .put("mode", mode)
            .put("task", lastTask)
            .put("provider", lastProvider)
            .put("pending", pendingQuestion ?: "")
            .put("steps", stepsArray)
            .put("files", JSONArray(files.toList()))
            .put("file_tools", JSONObject(fileTools.toMap()))
            .put("providers", JSONArray(providers))
            .put("failures", JSONArray(failures))
            .put("completed", completed)
            .put("updated_at", updatedAt)
    }

    @Synchronized
    fun importJson(obj: JSONObject) {
        reset()
        obj.optJSONObject("continuity")?.let { continuity.restore(it) }
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
        obj.optJSONObject("file_tools")?.let { map ->
            map.keys().forEach { key -> map.optString(key).takeIf { it.isNotBlank() }?.let { fileTools[key] = it } }
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
        val cleanPath = path.trim().take(160)
        // تكرار متطابق لنفس الأداة بنفس المدخلات والمخرج لا يضيف معلومة، ويستهلك نافذة السياق
        // فيُسجَّل كعدّاد على الخطوة نفسها بدل خطوة جديدة.
        val last = steps.lastOrNull()
        if (last != null && last.tool == tool && last.ok == ok && last.path == cleanPath && last.summary == summary) {
            steps[steps.lastIndex] = last.copy(repeat = last.repeat + 1, at = System.currentTimeMillis())
            updatedAt = System.currentTimeMillis()
            completed = false
            return
        }
        steps.add(AgentStep(tool, ok, summary, cleanPath))
        if (ok && cleanPath.isNotBlank() && tool !in READ_ONLY_TOOLS) {
            files.add(cleanPath)
            fileTools[cleanPath] = tool
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

    /**
     * يسجّل إجابة وصلت على السؤال المعلّق داخل الحلقة نفسها ويُفرّغ السؤال.
     * يعيد true إن كانت هناك فعلاً إجابة استُهلكت.
     */
    @Synchronized
    fun answerQuestion(answer: String, provider: String = ""): Boolean {
        val question = pendingQuestion ?: return false
        val clean = answer.replace(Regex("\\s+"), " ").trim().take(600)
        pendingQuestion = null
        steps.add(
            AgentStep(
                "user",
                true,
                "إجابة المستخدم على سؤالي «${question.take(120)}»: ${clean.take(300)}" +
                    (if (provider.isNotBlank()) " (داخل نفس الحلقة · $provider)" else " (داخل نفس الحلقة)")
            )
        )
        while (steps.size > 60) steps.removeAt(0)
        updatedAt = System.currentTimeMillis()
        return true
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

    /** مصدر العرض الحي نفسه المستخدم في الحفظ والاستئناف وتبديل المزود. */
    @Synchronized
    fun timeline(): List<AgentStep> = steps.toList()

    @Synchronized
    fun touchedFiles(): List<String> = files.toList()

    /**
     * كتلة سياق جاهزة تُحقن في تعليمات النظام ليعرف المزود البديل ما جرى فعلاً.
     * قصيرة دائماً (أقل من maxChars) حتى لا تستهلك نافذة السياق، لكنها الآن تحمل
     * تفاصيل أوسع للخطوات المنفذة والملفات المعدّلة وسياقها (ملخص الاستئناف).
     */
    @Synchronized
    fun contextBlock(maxChars: Int = 6000): String {
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
        // ملخص تنفيذي رقمي: كم خطوة نجحت/فشلت، توزيع الأدوات، وما فشل تحديداً.
        executionDigest().takeIf { it.isNotBlank() }?.let { digest ->
            builder.append("ملخص التنفيذ: ").append(digest.trim())
            if (!digest.endsWith("\n")) builder.append('\n')
        }
        builder.append("إذن الكتابة: ")
            .append(
                if (continuity.writeGranted) "ممنوح صراحةً لهذه المهمة. لا تطلب الإذن مجدداً ولا تستخدم [[ASK]] لطلبه؛ نفّذ الأداة مباشرة.\n"
                else "غير ممنوح. اطلب موافقة صريحة قبل أي تعديل، وبعد الموافقة نفّذ مباشرة بلا تكرار الطلب.\n"
            )
        if (providers.isNotEmpty()) {
            builder.append("المزودات التي جُرّبت بالترتيب: ").append(providers.joinToString(" ← ")).append('\n')
        }
        if (failures.isNotEmpty()) {
            builder.append("محاولات فاشلة: ").append(failures.joinToString(" | ")).append('\n')
        }
        if (files.isNotEmpty()) {
            builder.append("ملفات/مسارات تغيّرت بنجاح (مع الأداة التي لمستها): ")
                .append(
                    files.take(20).joinToString("، ") { path ->
                        val tool = fileTools[path].orEmpty()
                        if (tool.isBlank()) path else "$path [$tool]"
                    }
                ).append('\n')
            if (files.size > 20) builder.append("(و").append(files.size - 20).append(" مساراً آخر في السجل الكامل)\n")
        }
        pendingQuestion?.let {
            builder.append("سؤال معلّق للمستخدم: ").append(it.take(400)).append('\n')
        }
        // قواعد الاستئناف أولاً، ثم سجل الخطوات في الذيل: الاقتطاع يحافظ على آخر الخطوات
        // (وهي الأهم للمتابعة) بدل أن يقطعها.
        builder.append(
            "قواعد الاستئناف: هذه مهمة لم تكتمل بعد؛ أكملها من آخر نقطة. لا تُعد تنفيذ خطوة نجحت، " +
                "واقرأ/تحقق من الملفات المتغيرة قبل أي تعديل جديد. لا تدّعِ إنجاز شيء إلا إذا أعادت أداة نتيجة تؤكده. " +
                "إن كانت رسالة المستخدم الحالية تطلب مهمة مختلفة تماماً فابدأ بها واعتبر ما سبق سجلاً تاريخياً. " +
                "إن كان آخر سجل «قاطع المستخدم العمل»: أجب أولاً عن سؤاله أو ملاحظته بوضوح وإيجاز، ثم تابع من نقطة التوقف ما لم يغيّر الخطة.\n"
        )
        val recent = steps.takeLast(24)
        if (recent.isNotEmpty()) {
            if (steps.size > recent.size) builder.append("آخر ").append(recent.size).append(" خطوة من ").append(steps.size).append(":\n")
            else builder.append("الخطوات المنفذة:\n")
            recent.forEachIndexed { index, step ->
                builder.append(if (step.ok) "• ✅ " else "• ❌ ").append(step.tool)
                if (step.path.isNotBlank()) builder.append(' ').append(step.path)
                if (step.repeat > 0) builder.append(" (تكررت ×").append(step.repeat + 1).append(")")
                builder.append(" — ").append(step.summary)
                if (index == recent.lastIndex) builder.append('\n')
                else builder.append('\n')
            }
        }
        // الاقتطاع من المنتصف لا من الذيل: نحافظ على رأس الملخص (المهمة/الإذن/الملفات) وآخر الخطوات معاً.
        val limit = maxChars.coerceIn(800, 12_000)
        val text = builder.toString()
        if (text.length <= limit) return text
        val head = limit * 55 / 100
        val tail = limit - head - 60
        return text.take(head) + "\n… [اقتُطع جزء من منتصف السجل لضيق السياق] …\n" +
            text.takeLast(tail.coerceAtLeast(200))
    }

    /** أدوات لا تغيّر الملف (قراءة/استكشاف) فلا تُعدّ من «الملفات المعدّلة». */
    private val READ_ONLY_TOOLS = setOf(
        "file_read", "read_file", "file_list", "list_dir", "file_tree", "github_read_file",
        "file_diff", "file_search", "grep", "github_list_tree", "github_list_dir", "tool_schema", "tools_schema"
    )

    /** ما جرى على ملف بعينه (آخر أداة أثّرت فيه)، أو فراغ. */
    @Synchronized
    fun toolForFile(path: String): String = fileTools[path].orEmpty()

    /**
     * تفاصيل تنفيذ مجمّعة: عدد الخطوات لكل أداة، الخطوات الفاشلة، والملفات مع الأداة التي لمستها.
     * يستخدمها ملخص الاستئناف وسجل الحالة في الواجهة.
     */
    @Synchronized
    fun executionDigest(limit: Int = 12): String {
        val executed = steps.filter { it.tool !in NON_WORK_TOOLS }
        if (executed.isEmpty()) return ""
        val okCount = executed.count { it.ok }
        val builder = StringBuilder()
        builder.append("نجح ").append(okCount).append(" من ").append(executed.size).append(" خطوة منفذة")
        val repeated = executed.count { it.repeat > 0 }
        if (repeated > 0) builder.append(" ($repeated خطوة تكررت بنفس المخرج)")
        builder.append('\n')
        val byTool = executed.groupingBy { it.tool }.eachCount().entries
            .sortedByDescending { it.value }.take(limit)
        if (byTool.isNotEmpty()) {
            builder.append("توزيع الأدوات: ")
                .append(byTool.joinToString("، ") { it.key + "×" + it.value }).append('\n')
        }
        val failed = executed.filter { !it.ok }.map { it.tool }.distinct()
        if (failed.isNotEmpty()) {
            builder.append("خطوات فشلت (لا تكررها بنفس المدخلات قبل تغيير السبب): ")
                .append(failed.take(8).joinToString("، ")).append('\n')
        }
        val missing = files.filter { fileTools[it].orEmpty().isBlank() }
        if (missing.isNotEmpty()) builder.append("ملفات تغيّرت بلا أداة معروفة: ").append(missing.take(8).joinToString("، ")).append('\n')
        return builder.toString()
    }

    /** الملفات المتغيّرة مصحوبة بالأداة التي غيّرتها (سياق أدق عند الاستئناف). */
    @Synchronized
    fun touchedFilesWithTools(): List<Pair<String, String>> = files.map { it to fileTools[it].orEmpty() }

    companion object {
        /** خطوات إدارية/سردية ليست عملاً منفذاً على المشروع. */
        private val NON_WORK_TOOLS = setOf("user", "note", "provider")

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
