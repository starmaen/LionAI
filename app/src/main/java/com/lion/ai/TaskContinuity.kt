package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Conservative user-intent gate. Never inspect tool output/history for authorization. */
object WriteIntent {
    private fun clean(s: String) = s.lowercase().replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace('أ','ا').replace('إ','ا').replace('آ','ا').trim()

    /** أول سطر فقط: بطاقة القرار تضيف الملاحظات في سطر منفصل، فلا تُغيّر معنى الاختيار. */
    private fun head(s: String): String = clean(s).lineSequence().firstOrNull().orEmpty()
        .replace(Regex("^[^\\p{L}\\p{N}]+"), "")
        .trim(' ', '.', '!', '؟', '?', '،', ',')

    private val DENIAL = Regex(
        "(?:^|[\\s،,.!?؛;])(?:لا|دون|بدون)\\s+(?:تعدل|تعديل|تغير|تغيير|تكتب|كتابه|كتابة|تحذف|حذف|تنفذ|تنفيذ|تلمس|تعمل|ترفع|رفع|تدفع|دفع)(?![\\p{L}\\p{N}])|" +
            "(?:^|[\\s،,.!?؛;])(?:do not|don't|never)\\s+(?:edit|modify|write|create|delete|change|run|execute|push)|" +
            "(?:افحص|فحص|اقرا|اقرأ|read|inspect).{0,40}فقط|فقط.{0,40}(?:افحص|فحص|اقرا|اقرأ|read|inspect)|" +
            "read.only|للقراءه فقط|للقراءة فقط",
        RegexOption.IGNORE_CASE
    )
    private val APPROVAL = Regex(
        "^(?:نعم|اجل|موافق|موافقه|موافقة|اوافق|الموافقه|الموافقة|allow|allowed|approve|approved|yes|yep|ok|okay|go ahead)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE
    )
    private val FILE_TARGET = Regex(
        "(ملف|ملفات|مجلد|مجلدات|مسار|مسارات|كود|سكربت|سكريبت|برنامج|داله|دالة|كلاس|class|تطبيق|مشروع|مستودع|ريبو|مكتبه|مكتبة|اختبار|اختبارات|" +
            "file|files|folder|folders|directory|path|script|code|program|function|class|app|project|repo|repository|test|" +
            "\\.(?:kt|kts|java|py|js|ts|tsx|json|xml|ya?ml|txt|md|html|css|sh|gradle|toml|ini|csv|sql)\\b|```)",
        RegexOption.IGNORE_CASE
    )

    /**
     * أمر تنفيذ صريح = إذن شامل للمهمة: «نفذ» / «أكمل التنفيذ» (وصيغهما). الرسالة كلها يجب أن تكون
     * الأمر (مع مجاملات/ظروف قصيرة)، فلا تُمنح الكتابة من جملة طويلة تذكر الكلمة عرضاً.
     * «أكمل» وحدها تبقى استئنافاً فقط ولا تمنح كتابة.
     */
    private val EXECUTE = Regex(
        "^(?:(?:تمام|حسنا|طيب|ok|okay|please|رجاء|من فضلك|لو سمحت|الان)\\s+)*" +
            "(?:(?:نفذ|انفذ|نفذها|نفذه)(?:\\s+(?:الان|فورا|كل شيء|كل شي|الكل|الخطه|الخطة|المطلوب|ما سبق))*|" +
            "(?:اكمل|كمل|تابع|استكمل|واصل)\\s+(?:ال)?تنفيذ(?:\\s+(?:الان|فورا|من حيث توقفت))*|" +
            "execute|proceed|go ahead|continue execution)$",
        RegexOption.IGNORE_CASE
    )
    fun isExecute(s: String): Boolean = !isDenial(s) && EXECUTE.matches(head(s))

    fun isContinuation(s: String): Boolean = isExecute(s) || clean(s).trim(' ', '.', '!', '؟') in
        setOf("اكمل", "كمل", "تابع", "استكمل", "واصل", "continue", "resume", "اكمل من حيث توقفت")
    fun isNewTask(s: String): Boolean {
        val t = clean(s).replace('ة', 'ه').trim(' ', '.', '!', '؟', '?')
        return t == "مهمه جديده" || t.startsWith("مهمه جديده ") ||
            t == "موضوع جديد" || t.startsWith("موضوع جديد ") ||
            t == "ابدا من جديد" || t.startsWith("ابدا من جديد ") ||
            t == "start over" || t.startsWith("start over ") ||
            t == "new task" || t.startsWith("new task ") ||
            t == "new chat" || t.startsWith("new chat ")
    }
    /** موافقة صريحة على طلب إذن سابق، وليست مجرد استئناف ("أكمل" لا يمنح كتابة). */
    fun isApproval(s: String): Boolean = APPROVAL.containsMatchIn(head(s))
    /** رفض صريح: لا يُفعّل بمجرد ورود "لا" داخل جملة مثل "لا مشكلة". */
    fun isDenial(s: String): Boolean {
        val t = clean(s)
        if (DENIAL.containsMatchIn(t)) return true
        return head(s) in setOf("لا", "كلا", "الغاء", "cancel", "stop", "no", "reject", "denied", "deny")
    }
    private val IMPERATIVE = Regex("^(?:(?:صديقي|يا صديقي|اخي|رجاء|ارجوك|ارجو|من فضلك|لو سمحت|لو تكرمت|المطلوب|اريدك ان|اريدك|اريد|ابغى|ابي|بدي|عايز|please)\\s+)*(قم|ابدا|حدث|تحديث|ابن|انسخ|انقل|امسح|ادمج|ضع|فعل|عطل|اصلح|اصلاح|عدل|تعديل|غير|انشئ|انشاء|اصنع|صنع|اكتب|اضف|احذف|ارفع|نفذ|انفذ|ارسل|ارسل|برمج|طور|حول|ترجم|شغل|ثبت|صمم|اعمل|حسن|fix|repair|edit|modify|create|write|add|delete|implement|send|push|deploy|build|make|generate|refactor|update|install|rename|remove|commit|merge|move|copy|run)(?:\\s|$)")

    /** الرسالة تبدأ بفعل أمر صريح (أنشئ/احذف/اكتب/شغّل…). */
    fun startsImperative(s: String): Boolean = IMPERATIVE.containsMatchIn(clean(s))

    /**
     * طلب قراءة/رفض فعلي. عبارات النهي الجزئية داخل أمر صريح («احذف الفرع X، ولا تحذف أي فرع آخر»)
     * قيود على نطاق الإذن وليست رفضاً للمهمة، فلا تُلغي الإذن الممنوح بالأمر نفسه.
     */
    fun isReadOnlyRequest(s: String): Boolean =
        (isDenial(s) && !startsImperative(s) && !hasMutationIntent(s)) || ScopeGuard.isLookup(s)

    /** فعل تعديل/حذف/كتابة/تشغيل بصيغة الأمر في أي موضع (لا «تعدل» المنفية): يعني أن الطلب ليس قراءة فقط. */
    private val MUTATION_WORD = Regex(
        "(?:^|[\\s،,.:؛;(\\[\"'])(?:احذف|انشئ|اكتب|عدل|غير|اصلح|شغل|نفذ|ارفع|اضف|ادمج|ابن|انسخ|انقل|امسح|" +
            "delete|create|write|edit|modify|run|dispatch|push|merge|remove|build)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE
    )
    fun hasMutationIntent(s: String): Boolean = MUTATION_WORD.containsMatchIn(clean(s))

    /**
     * أمر تنفيذي مباشر من المستخدم نفسه (لا من نص صفحة ولا من نتيجة أداة) = إذن رسمي لهذه المهمة.
     * الأسئلة وأسئلة القدرة («هل يمكنك…؟») لا تُمنح بها كتابة. «اكتب» بلا هدف ملف/كود تبقى صياغة نص.
     */
    fun isDirectCommand(s: String): Boolean {
        val t = clean(s)
        if (isReadOnlyRequest(t) || isApproval(t)) return false
        if (UserIntent.classify(s) != UserIntent.Kind.REQUEST) return false
        if (Regex("^(?:(?:صديقي|اخي|رجاء|ارجوك|من فضلك|لو سمحت|اريدك ان|اريد|please)\\s+)*(?:اكتب|write)\\s+", RegexOption.IGNORE_CASE)
                .containsMatchIn(t) && !FILE_TARGET.containsMatchIn(t)) return false
        return IMPERATIVE.containsMatchIn(t) || hasMutationIntent(t)
    }

    fun permits(s: String): Boolean {
        val t = clean(s)
        if (isReadOnlyRequest(t)) return false
        if (isApproval(t)) return false
        // "اكتب" وحدها قد تعني صياغة نص. لا نمنح كتابة إلا عندما يكون الهدف ملفاً/كوداً.
        if (Regex("^(?:(?:صديقي|اخي|رجاء|من فضلك|لو سمحت|اريدك ان|اريد|please)\\s+)*(?:اكتب|write)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(t) &&
            !FILE_TARGET.containsMatchIn(t)) return false
        // Anchored imperative only. Questions, quoted examples and historical instructions do not grant rights.
        return IMPERATIVE.containsMatchIn(t)
    }
}

/**
 * إذن الكتابة على مستوى المحادثة (وليس الدور فقط).
 *
 * المشكلة التي يعالجها: كانت الموافقة تعيش داخل جلسة الوكيل، فإذا اكتملت الجلسة أو أُعيد
 * إنشاؤها ثم كتب المستخدم «أكمل» عاد الوكيل يطلب الإذن من جديد رغم منحه سابقاً.
 * هذا المخزن يبقى بعد انتهاء الجلسة وبعد إعادة تشغيل التطبيق، ولا يُلغى إلا برفض صريح
 * أو بدء مهمة جديدة صريحة أو حذف المحادثة.
 */
object PermissionStore {
    private const val PREFIX = "perm_write_"

    fun writeGranted(prefs: SharedPreferences, chatId: String): Boolean =
        chatId.isNotBlank() && prefs.getBoolean(PREFIX + chatId, false)

    fun grantWrite(prefs: SharedPreferences, chatId: String) {
        if (chatId.isNotBlank()) prefs.edit().putBoolean(PREFIX + chatId, true).apply()
    }

    fun revokeWrite(prefs: SharedPreferences, chatId: String) {
        if (chatId.isNotBlank()) prefs.edit().putBoolean(PREFIX + chatId, false).apply()
    }

    fun clear(prefs: SharedPreferences, chatId: String) {
        if (chatId.isNotBlank()) prefs.edit().remove(PREFIX + chatId).apply()
    }
}

/** Per-task receipts and exact tool-turn state, persisted in encrypted application preferences.
 * Results are data, not authorization. Bounded history is explicitly marked when trimmed.
 */
class TaskContinuity {
    var history: List<ChatTurn> = emptyList()
        private set
    var current: String = ""
        private set
    var writeGranted = false
    /** مفاتيح عمليات خطرة وافق عليها المستخدم بالاسم، وأخرى معروضة عليه بانتظار قراره. */
    val riskApproved = mutableSetOf<String>()
    val pendingRisk = mutableSetOf<String>()
    var responded = false
    var resume = false
    /** ملخص الاستئناف يُلحق برسالة المتابعة؛ منفصل عن current حتى لا يغيّر الحالة المحفوظة. */
    var resumeNote: String = ""
        private set
    var trimmed = false
        private set
    private var epoch = 0
    private val receipts = JSONObject()
    private val reads = JSONObject()

    private companion object {
        const val RESUME_MARK = "[[RESUME_BRIEF]]"
        val READ_HEAD = Regex("^(.+?)\\s*\\(الأسطر\\s*(\\d+)[–-](\\d+)\\s*من\\s*(\\d+)\\)")
        val SHA_TAIL = Regex("\\s*\\([0-9a-f]{7,40}\\)\\s*$")
    }

    fun begin(
        userRequest: String,
        unfinished: Boolean,
        answering: Boolean = false,
        persistedWrite: Boolean = false
    ) {
        resume = unfinished && (WriteIntent.isContinuation(userRequest) || answering)
        val approved = WriteIntent.isApproval(userRequest) &&
            (resume || answering || writeGranted || persistedWrite)
        // موافقة المستخدم على بطاقة عملية خطرة تخص تلك العملية فقط؛ المهمة الجديدة أو الرفض يمسحان الاثنتين.
        if (!resume || WriteIntent.isReadOnlyRequest(userRequest)) { riskApproved.clear(); pendingRisk.clear() }
        else if (approved || WriteIntent.isExecute(userRequest)) {
            riskApproved.addAll(pendingRisk)
            // موافقة واحدة تغطي نفس الصنف من العمليات الروتينية في هذه المهمة (لا بطاقة كل دقيقتين)؛ الفروع والمستودعات والطلبات الخارجية تبقى فردية.
            riskApproved.addAll(pendingRisk.map { it.substringBefore('|') }
                .filter { it in setOf("shell_exec", "execute_command", "file_delete", "file_move", "github_delete_file") }
                .map { "cat:$it" })
            pendingRisk.clear()
        }
        writeGranted = when {
            WriteIntent.isReadOnlyRequest(userRequest) -> false
            WriteIntent.isExecute(userRequest) -> true
            WriteIntent.permits(userRequest) -> true
            WriteIntent.isDirectCommand(userRequest) -> true
            approved -> true
            resume -> writeGranted || persistedWrite
            persistedWrite && UserIntent.classify(userRequest) != UserIntent.Kind.QUESTION -> true
            else -> false
        }
        responded = false
        if (!resume) {
            receipts.keys().asSequence().toList().forEach { receipts.remove(it) }
            reads.keys().asSequence().toList().forEach { reads.remove(it) }
            current = ""
            resumeNote = ""
        } else {
            prepareResume()
        }
    }

    /**
     * الاستئناف الحقيقي بدل إعادة الدورة نفسها: رفضات الإذن/الحوكمة (⛔) لا تبقى «فاشلة» للأبد،
     * والعملية غير المؤكدة لا تمنع الاكتمال (وتبقى محجوبة من إعادة التنفيذ الآلي)،
     * ويُلحق بالسياق ما قُرئ وأين يُتابع وقواعد تمنع تكرار الاستدعاء نفسه.
     */
    private fun prepareResume() {
        resumeNote = ""
        val unverified = mutableListOf<String>()
        for (key in receipts.keys().asSequence().toList()) {
            val receipt = receipts.optJSONObject(key) ?: continue
            when (receipt.optString("state")) {
                "failed" -> {
                    if (receipt.optString("output").contains("⛔")) receipts.remove(key)
                }
                "uncertain" -> {
                    receipt.put("state", "unverified")
                    unverified.add(receipt.optString("output").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(100))
                }
            }
        }
        if (current.isBlank()) return
        resumeNote = resumeBrief(unverified)
    }

    private fun resumeBrief(unverified: List<String>): String {
        val cover = LinkedHashMap<String, IntArray>()
        for (key in reads.keys().asSequence().toList()) {
            val output = reads.optJSONObject(key)?.optString("output").orEmpty()
            val head = output.lineSequence().take(3).firstOrNull { it.contains("(الأسطر") } ?: continue
            val match = READ_HEAD.find(head) ?: continue
            val path = match.groupValues[1].replace(SHA_TAIL, "").trim().takeLast(70)
            val shown = match.groupValues[3].toIntOrNull() ?: continue
            val total = match.groupValues[4].toIntOrNull() ?: continue
            val entry = cover.getOrPut(path) { intArrayOf(0, total) }
            if (shown > entry[0]) entry[0] = shown
            entry[1] = total
        }
        val sb = StringBuilder("\n\n").append(RESUME_MARK).append("\nحالة الاستئناف (من سجل التطبيق لا من ذاكرتك):\n")
        cover.entries.toList().takeLast(8).forEach { (path, range) ->
            sb.append("- قُرئ: ").append(path).append(" حتى السطر ").append(range[0]).append(" من ").append(range[1])
            if (range[0] < range[1]) sb.append(" — المتبقي يبدأ من السطر ").append(range[0] + 1)
            sb.append('\n')
        }
        unverified.take(3).forEach {
            sb.append("- عملية لم يُعرف أثرها: ").append(it).append(" — تحقق منها بقراءة واحدة ثم تابع، ولا تعدها آلياً\n")
        }
        sb.append("قواعد المتابعة:\n")
        sb.append("- لا تُعد أي استدعاء نفّذته بنفس مدخلاته؛ نتيجته معروفة.\n")
        sb.append("- لا تقرأ ملفاً كاملاً بحثاً عن كلمة: استعمل file_search أو github_search_code، وللمتابعة استعمل start_line التالي.\n")
        sb.append("- أنهِ هذه الجولة بنتيجة للمستخدم: ما وجدته، ما لم تجده، ما تبقى، والخطوة التالية. لا تتوقف بصمت.")
        return sb.toString()
    }

    fun checkpoint(turns: List<ChatTurn>, next: String) {
        val kept = turns.toMutableList()
        var size = kept.sumOf { it.content.length }
        while (size > 180_000 && kept.isNotEmpty()) { size -= kept.removeAt(0).content.length; trimmed = true }
        history = kept
        current = next
    }
    fun key(name: String, args: JSONObject): String {
        fun canonical(v: Any?): String = when(v) {
            is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it)+":"+canonical(v.opt(it)) }
            is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
            is String -> JSONObject.quote(v)
            else -> v?.toString() ?: "null"
        }
        return MessageDigest.getInstance("SHA-256").digest((name+canonical(args)).toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun receipt(key: String): JSONObject? = receipts.optJSONObject(key)
    fun hasConfirmedMutation(): Boolean = receipts.keys().asSequence().any { receipts.optJSONObject(it)?.optString("state") == "done" }
    fun hasUncertainMutation(): Boolean = receipts.keys().asSequence().any { receipts.optJSONObject(it)?.optString("state") == "uncertain" }
    fun beginMutation(key: String): Boolean {
        if (receipts.length() >= 128) return false
        receipts.put(key, JSONObject().put("state", "uncertain"))
        return true
    }
    /**
     * definite = فشل محلي حاسم (patch/تعديل ملف يُتحقق منه كاملاً قبل الكتابة): لم يتغير شيء، فلا يُسمّى «غير مؤكد»
     * ولا يوقف المهمة. أما فشل الشبكة/الصدفة فيبقى غير مؤكد لأنه قد يكون نفذ فعلاً.
     * [path] هدف العملية (ملف/فرع/مسار) ليُربط بأول قراءة ناجحة له فيُحسم حالته تلقائياً.
     */
    fun recordMutation(key: String, output: String, ok: Boolean, definite: Boolean = false, path: String = "") {
        // A failed network response can still have committed remotely. Never auto-retry it.
        val state = if (ok) "done" else if (definite) "failed" else "uncertain"
        receipts.put(
            key,
            JSONObject().put("state", state).put("output", output.take(16000))
                .put("path", path.trim().replace('\\', '/').take(160))
        )
        epoch++
        reads.keys().asSequence().toList().forEach { reads.remove(it) }
    }
    fun cachedRead(key: String): String? = reads.optJSONObject(key)?.takeIf { it.optInt("epoch") == epoch }?.optString("output")

    /**
     * يُحسم كل المحاولات «غير المؤكدة» (أو تلك الخاصة بمسار محدد) بعد أن يتحقق الوكيل فعلياً
     * من الحالة. يُستخدم كبديل صريح عن إعادة التنفيذ الأعمى، فلا تبقى المهمة معلّقة للأبد.
     */
    fun resolveUncertain(path: String = ""): Int {
        val normalized = path.trim().replace('\\', '/')
        var count = 0
        receipts.keys().asSequence().toList().forEach { key ->
            val receipt = receipts.optJSONObject(key) ?: return@forEach
            if (receipt.optString("state") != "uncertain") return@forEach
            val rpath = receipt.optString("path").trim().replace('\\', '/')
            if (normalized.isBlank() || rpath.isBlank() || rpath == normalized || rpath.endsWith("/" + normalized.substringAfterLast('/'))) {
                receipt.put("state", "verified")
                count++
            }
        }
        return count
    }

    /**
     * قراءة ناجحة = دليل فعلي على حالة الهدف. أي محاولة تعديل سابقة بقيت «غير مؤكدة» على المسار
     * نفسه (أو بلا مسار محدد) تُحسم الآن إلى "verified" فلا تمنع الاكتمال ولا تُعيد التنفيذ الأعمى.
     * هذا يكسر حلقة «نتيجة محاولة سابقة غير مؤكدة» التي كانت توقف المهمة رغم أن الحالة صارت معلومة.
     */
    fun recordRead(key: String, output: String, path: String = "") {
        if (reads.length() >= 24) reads.keys().asSequence().firstOrNull()?.let { reads.remove(it) }
        reads.put(key, JSONObject().put("epoch", epoch).put("output", output.take(16000)))
        val normalized = path.trim().replace('\\', '/')
        receipts.keys().asSequence().toList().forEach { rk ->
            val receipt = receipts.optJSONObject(rk) ?: return@forEach
            if (receipt.optString("state") != "uncertain") return@forEach
            val rpath = receipt.optString("path").trim().replace('\\', '/')
            val related = rpath.isBlank() || normalized.isBlank() ||
                rpath == normalized || rpath.endsWith("/" + normalized.substringAfterLast('/'))
            if (related) receipt.put("state", "verified")
        }
    }
    fun json(): JSONObject = JSONObject().put("history", JSONArray().also { a -> history.forEach { a.put(JSONObject().put("role",it.role).put("content",it.content)) } })
        .put("current",current).put("write",writeGranted).put("receipts",receipts).put("reads",reads).put("epoch",epoch).put("trimmed",trimmed)
        .put("riskOk",JSONArray(riskApproved.toList())).put("riskPending",JSONArray(pendingRisk.toList()))
    fun restore(o: JSONObject) {
        val a=o.optJSONArray("history") ?: JSONArray()
        history=(0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { x -> ChatTurn(x.optString("role"),x.optString("content")) } }
        current=o.optString("current");writeGranted=o.optBoolean("write");epoch=o.optInt("epoch");trimmed=o.optBoolean("trimmed")
        riskApproved.clear();pendingRisk.clear()
        o.optJSONArray("riskOk")?.let { r -> for (i in 0 until r.length()) riskApproved.add(r.optString(i)) }
        o.optJSONArray("riskPending")?.let { r -> for (i in 0 until r.length()) pendingRisk.add(r.optString(i)) }
        listOf("receipts" to receipts,"reads" to reads).forEach { (name,target) ->
            target.keys().asSequence().toList().forEach { target.remove(it) }
            o.optJSONObject(name)?.let { source -> source.keys().forEach { target.put(it,source.get(it)) } }
        }
    }
}
