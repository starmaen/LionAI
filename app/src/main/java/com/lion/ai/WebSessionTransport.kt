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
    /** طابور عادل: الرسائل المتزامنة (غرفة العمليات/أدوات/مزودات) تنتظر دورها بدل الرفض الفوري. */
    private val gate = java.util.concurrent.Semaphore(1, true)
    /** تمديد مهلة طلب جارٍ بينما الصفحة ما زالت تكتب/تولّد: المضيف يستدعيه فتنتظر الدردشة بدل أن تقطع. */
    private val extended = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun extend(token: String, untilMs: Long) { extended.merge(token, untilMs) { a, b -> maxOf(a, b) } }
    fun attach(value: Host) { host = value }
    fun detach(value: Host) { if (host === value) host = null }
    fun attached(): Boolean = host != null
    fun open(provider: String) { host?.open(provider) }
    fun inspect(provider: String) { host?.inspect(provider) }
    /** زر «إيقاف» في الشات: يحرّر الطلب الجاري فلا تبقى الجلسة «مشغولة» حتى المهلة. */
    fun cancelActive() { active.get()?.let { host?.cancel(it) } }

    fun exchange(
        provider: String, prompt: String, timeoutMs: Int, fresh: Boolean = false,
        recapFull: String = "", recapDelta: String = ""
    ): Reply {
        val current = host ?: return Reply("⚠️ افتح التطبيق لتشغيل جلسة الويب.", false)
        if (current.isUiThread()) return Reply("⚠️ لا يمكن انتظار الويب على خيط الواجهة.", false)
        val token = UUID.randomUUID().toString()
        val timeout = timeoutMs.coerceIn(1000, 420_000)
        val acquired = try {
            gate.tryAcquire(timeout.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Reply("⚠️ أُلغي الانتظار في طابور جلسة الويب.", false)
        }
        if (!acquired) return Reply("⚠️ جلسة الويب مشغولة برسالة أخرى طويلة. أعد المحاولة بعد انتهائها.", false)
        active.set(token)
        val result = AtomicReference<Reply?>(null)
        val latch = CountDownLatch(1)
        return try {
            current.start(Request(token, provider, prompt, timeout, fresh, recapFull, recapDelta)) { reply ->
                if (active.get() == token && result.compareAndSet(null, reply)) latch.countDown()
            }
            // انتظار «بحسب النشاط»: المهلة تتجدد (extend) ما دامت الصفحة تكتب، وتنتهي فقط عند السكون أو السقف المطلق.
            val absoluteCap = System.currentTimeMillis() + 25 * 60_000L
            var limit = System.currentTimeMillis() + timeout.toLong() + 1000L
            var finished = false
            while (true) {
                if (latch.await(400, TimeUnit.MILLISECONDS)) { finished = true; break }
                limit = maxOf(limit, (extended[token] ?: 0L) + 1000L)
                if (System.currentTimeMillis() > minOf(limit, absoluteCap)) break
            }
            if (!finished) {
                Reply("⚠️ تأخر رد الصفحة عن المهلة؛ الجلسة لم تُنهَ وقد تكون الرسالة أُرسلت. افحص الصفحة قبل إعادة المحاولة.", false, true)
            } else result.get() ?: Reply("⚠️ لم يصل رد من الصفحة؛ الجلسة لم تُنهَ. أعد المحاولة أو افتح الجلسة للتحقق.", false)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Reply("⚠️ أُلغي الانتظار؛ قد يستمر الموقع في توليد الرد.", false, true)
        } catch (e: Exception) {
            Reply("⚠️ تعذّر تشغيل جلسة الويب: ${e.message}", false)
        } finally {
            current.cancel(token)
            extended.remove(token)
            active.compareAndSet(token, null)
            gate.release()
        }
    }

    /**
     * Web composers are already a live conversation. Dumping system+history into the
     * input prevents Send and never returns a real site answer to the app.
     */
    fun prompt(system: String, history: List<ChatTurn>, message: String): String = WebToolBridge.shape(system, message)
}
