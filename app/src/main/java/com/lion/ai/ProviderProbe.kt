package com.lion.ai

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * فحص حقيقي للمزودات: يرسل طلباً صغيراً بنفس مسار المحادثة (مباشر ثم الخادم)،
 * فلا تظهر علامة ✔ إلا إذا عاد رد فعلي. النتيجة تُحفظ في ProviderHealth فتبقى بعد الإغلاق.
 */
object ProviderProbe {
    enum class State { OK, FAIL, LIMITED }

    data class Result(val state: State, val note: String, val atMs: Long)

    const val FRESH_MS = 10 * 60 * 1000L
    private const val TIMEOUT_MS = 25_000L
    private const val PARALLEL = 4

    fun stateOf(e: ProviderHealth.Entry): Result {
        val limited = !e.ok && e.note.startsWith("⏳")
        return Result(if (e.ok) State.OK else if (limited) State.LIMITED else State.FAIL, e.note, e.atMs)
    }

    fun cached(prefs: SharedPreferences, id: String): Result? =
        ProviderHealth.get(prefs, id)?.let { stateOf(it) }

    fun isFresh(r: Result?): Boolean =
        r != null && System.currentTimeMillis() - r.atMs < FRESH_MS

    /** فحص مزود واحد (حاجز، يُستدعى من خيط الإدخال/الإخراج). */
    fun check(prefs: SharedPreferences, info: ProviderInfo): Result {
        val id = info.id
        val key = (prefs.getString("api_key_$id", "") ?: "").trim()
        val account = prefs.getString("cf_account_id", "") ?: ""
        val isServer = info.custom || id == "local_proxy" || id.startsWith(UserPlatforms.PREFIX)

        if (key.isBlank() && !info.keyless && !isServer) {
            return finish(prefs, id, State.FAIL, "لا يوجد مفتاح API")
        }
        if (info.needsAccountId && account.isBlank()) {
            return finish(prefs, id, State.FAIL, "Account ID ناقص")
        }
        val probe = "أجب بكلمة واحدة فقط: تم"
        val reply = runCatching {
            var r = LlmClient.ask(probe, key, id, emptyList(), "", account, via = Channel.DIRECT)
            if (LlmClient.isErrorReply(r) && WebGateway.shouldRoute(id)) {
                val viaServer = LlmClient.ask(probe, key, id, emptyList(), "", account, via = Channel.GATEWAY)
                if (!LlmClient.isErrorReply(viaServer)) r = viaServer
            }
            r
        }.getOrElse { "⚠️ " + (it.message ?: "خطأ غير معروف") }

        return when {
            reply.isBlank() -> finish(prefs, id, State.FAIL, "رد فارغ")
            reply.startsWith("⏳") -> finish(prefs, id, State.LIMITED, reply)
            LlmClient.isErrorReply(reply) -> finish(prefs, id, State.FAIL, reply)
            else -> finish(prefs, id, State.OK, "")
        }
    }

    private fun finish(prefs: SharedPreferences, id: String, s: State, note: String): Result {
        val clean = note.removePrefix("⚠️").removePrefix("🔑").trim()
        val stored = if (s == State.LIMITED && !clean.startsWith("⏳")) "⏳ $clean" else clean
        ProviderHealth.record(prefs, id, s == State.OK, stored)
        if (s == State.OK) QuotaGuard.clear(id) else QuotaGuard.mark(id, if (s == State.LIMITED) QuotaGuard.Kind.RATE else QuotaGuard.classify(clean))
        return Result(s, stored, System.currentTimeMillis())
    }

    /**
     * يفحص القائمة بتوازٍ محدود (4)، لكل مزود سقف زمني. [onStart]/[onResult] يُستدعيان
     * من أي خيط، فعلى المستدعي نقل التحديث إلى حالة Compose بأمان (mutableStateMap آمنة).
     */
    suspend fun checkAll(
        prefs: SharedPreferences,
        list: List<ProviderInfo>,
        onStart: (String) -> Unit,
        onResult: (String, Result) -> Unit
    ) = coroutineScope {
        val gate = Semaphore(PARALLEL)
        list.map { info ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    onStart(info.id)
                    val r = withTimeoutOrNull(TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { check(prefs, info) }
                    } ?: finish(prefs, info.id, State.FAIL, "انتهت المهلة (${TIMEOUT_MS / 1000}ث)")
                    onResult(info.id, r)
                }
            }
        }.awaitAll()
    }
}
