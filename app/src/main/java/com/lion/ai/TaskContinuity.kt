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
    private val IMPERATIVE = Regex("^(?:(?:صديقي|اخي|رجاء|من فضلك|لو سمحت|اريدك ان|اريد|please)\\s+)*(اصلح|اصلاح|عدل|تعديل|غير|انشئ|انشاء|اصنع|صنع|اكتب|اضف|احذف|ارفع|نفذ|انفذ|ارسل|ارسل|برمج|طور|حول|ترجم|شغل|ثبت|صمم|اعمل|حسن|fix|repair|edit|modify|create|write|add|delete|implement|send|push|deploy|build|make|generate|refactor)(?:\\s|$)")

    /** الرسالة تبدأ بفعل أمر صريح (أنشئ/احذف/اكتب/شغّل…). */
    fun startsImperative(s: String): Boolean = IMPERATIVE.containsMatchIn(clean(s))

    /**
     * طلب قراءة/رفض فعلي. عبارات النهي الجزئية داخل أمر صريح («احذف الفرع X، ولا تحذف أي فرع آخر»)
     * قيود على نطاق الإذن وليست رفضاً للمهمة، فلا تُلغي الإذن الممنوح بالأمر نفسه.
     */
    fun isReadOnlyRequest(s: String): Boolean = isDenial(s) && !startsImperative(s)

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
    var responded = false
    var resume = false
    var trimmed = false
        private set
    private var epoch = 0
    private val receipts = JSONObject()
    private val reads = JSONObject()

    fun begin(
        userRequest: String,
        unfinished: Boolean,
        answering: Boolean = false,
        persistedWrite: Boolean = false
    ) {
        resume = unfinished && (WriteIntent.isContinuation(userRequest) || answering)
        val approved = WriteIntent.isApproval(userRequest) &&
            (resume || answering || writeGranted || persistedWrite)
        writeGranted = when {
            WriteIntent.isReadOnlyRequest(userRequest) -> false
            WriteIntent.isExecute(userRequest) -> true
            WriteIntent.permits(userRequest) -> true
            approved -> true
            resume -> writeGranted || persistedWrite
            persistedWrite -> true
            else -> false
        }
        responded = false
        if (!resume) {
            receipts.keys().asSequence().toList().forEach { receipts.remove(it) }
            reads.keys().asSequence().toList().forEach { reads.remove(it) }
            current = ""
        }
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
    fun recordMutation(key: String, output: String, ok: Boolean) {
        // A failed network response can still have committed remotely. Never auto-retry it.
        receipts.put(key, JSONObject().put("state", if(ok) "done" else "uncertain").put("output", output.take(16000)))
        epoch++
        reads.keys().asSequence().toList().forEach { reads.remove(it) }
    }
    fun cachedRead(key: String): String? = reads.optJSONObject(key)?.takeIf { it.optInt("epoch") == epoch }?.optString("output")
    fun recordRead(key: String, output: String) {
        if (reads.length() >= 24) reads.keys().asSequence().firstOrNull()?.let { reads.remove(it) }
        reads.put(key, JSONObject().put("epoch", epoch).put("output", output.take(16000)))
    }
    fun json(): JSONObject = JSONObject().put("history", JSONArray().also { a -> history.forEach { a.put(JSONObject().put("role",it.role).put("content",it.content)) } })
        .put("current",current).put("write",writeGranted).put("receipts",receipts).put("reads",reads).put("epoch",epoch).put("trimmed",trimmed)
    fun restore(o: JSONObject) {
        val a=o.optJSONArray("history") ?: JSONArray()
        history=(0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { x -> ChatTurn(x.optString("role"),x.optString("content")) } }
        current=o.optString("current");writeGranted=o.optBoolean("write");epoch=o.optInt("epoch");trimmed=o.optBoolean("trimmed")
        listOf("receipts" to receipts,"reads" to reads).forEach { (name,target) ->
            target.keys().asSequence().toList().forEach { target.remove(it) }
            o.optJSONObject(name)?.let { source -> source.keys().forEach { target.put(it,source.get(it)) } }
        }
    }
}
