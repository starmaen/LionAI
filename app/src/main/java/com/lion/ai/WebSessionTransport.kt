package com.lion.ai

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Pure request broker. The Android host owns WebViews; workers only wait on request-scoped results. */
object WebSessionTransport {
    data class Request(
        val token: String, val provider: String, val prompt: String, val timeoutMs: Int, val fresh: Boolean = false,
        /** ملخص سياق كامل يُلحق إن أعاد المضيف تحميل الصفحة، وملخص لما فات الصفحة فقط إن لم يُعد. */
        val recapFull: String = "", val recapDelta: String = ""
    )
    data class Reply(val text: String, val success: Boolean, val submitted: Boolean = false)
    interface Host {
        fun start(request: Request, done: (Reply) -> Unit)
        fun cancel(token: String)
        fun inspect(provider: String) = Unit
        fun open(provider: String)
        fun isUiThread(): Boolean
    }
    @Volatile private var host: Host? = null
    private val active = AtomicReference<String?>(null)
    fun attach(value: Host) { host = value }
    fun detach(value: Host) { if (host === value) host = null }
    fun attached(): Boolean = host != null
    fun open(provider: String) { host?.open(provider) }
    fun inspect(provider: String) { host?.inspect(provider) }

    fun exchange(
        provider: String, prompt: String, timeoutMs: Int, fresh: Boolean = false,
        recapFull: String = "", recapDelta: String = ""
    ): Reply {
        val current = host ?: return Reply("⚠️ افتح التطبيق لتشغيل جلسة الويب.", false)
        if (current.isUiThread()) return Reply("⚠️ لا يمكن انتظار الويب على خيط الواجهة.", false)
        val token = UUID.randomUUID().toString()
        if (!active.compareAndSet(null, token)) return Reply("⚠️ توجد رسالة ويب قيد التنفيذ. انتظر أو ألغها.", false)
        val result = AtomicReference<Reply?>(null)
        val latch = CountDownLatch(1)
        val timeout = timeoutMs.coerceIn(1000, 180_000)
        return try {
            current.start(Request(token, provider, prompt, timeout, fresh, recapFull, recapDelta)) { reply ->
                if (active.get() == token && result.compareAndSet(null, reply)) latch.countDown()
            }
            if (!latch.await(timeout.toLong() + 1000L, TimeUnit.MILLISECONDS)) {
                Reply("⚠️ انتهت مهلة جلسة الويب؛ قد تكون الرسالة أُرسلت. افحص الصفحة قبل إعادة المحاولة.", false, true)
            } else result.get() ?: Reply("⚠️ انتهت الجلسة دون رد.", false)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Reply("⚠️ أُلغي الانتظار؛ قد يستمر الموقع في توليد الرد.", false, true)
        } catch (e: Exception) {
            Reply("⚠️ تعذّر تشغيل جلسة الويب: ${e.message}", false)
        } finally {
            current.cancel(token)
            active.compareAndSet(token, null)
        }
    }

    /**
     * Web composers are already a live conversation. Dumping system+history into the
     * input prevents Send and never returns a real site answer to the app.
     */
    fun prompt(system: String, history: List<ChatTurn>, message: String): String = WebToolBridge.shape(system, message)
}
