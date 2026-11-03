package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Conservative user-intent gate. Never inspect tool output/history for authorization. */
object WriteIntent {
    private fun clean(s: String) = s.lowercase().replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace('أ','ا').replace('إ','ا').replace('آ','ا').trim()
    fun isContinuation(s: String): Boolean = clean(s).trim(' ', '.', '!', '؟') in
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
    fun permits(s: String): Boolean {
        val t = clean(s)
        if (Regex("(لا|دون|بدون)\\s+(تعدل|تعديل|تغير|تغيير|تكتب|كتابة|تحذف|حذف)|do not|don't|read.only|فقط.*(افحص|اقرا)|(?:افحص|اقرا).*فقط").containsMatchIn(t)) return false
        if (Regex("^(?:(?:اريد|please)\\s+)?(?:اكتب|write)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(t) &&
            !Regex("في\\s+(?:الملف|ملف)|داخل\\s+(?:الملف|ملف)|to\\s+(?:the\\s+)?file", RegexOption.IGNORE_CASE).containsMatchIn(t)) return false
        // Anchored imperative only. Questions, quoted examples and historical instructions do not grant rights.
        return Regex("^(?:(?:صديقي|اخي|رجاء|من فضلك|لو سمحت|اريدك ان|اريد|please)\\s+)*(اصلح|اصلاح|عدل|تعديل|غير|انشئ|انشاء|اكتب|اضف|احذف|ارفع|نفذ|ارسل|fix|repair|edit|modify|create|write|add|delete|implement|send|push|deploy)(?:\\s|$)").containsMatchIn(t)
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

    fun begin(userRequest: String, unfinished: Boolean, answering: Boolean = false) {
        resume = unfinished && (WriteIntent.isContinuation(userRequest) || answering)
        val revoked = Regex("لا\\s+|بدون|دون|do not|don't", RegexOption.IGNORE_CASE).containsMatchIn(userRequest)
        writeGranted = !revoked && (WriteIntent.permits(userRequest) || (resume && writeGranted))
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
