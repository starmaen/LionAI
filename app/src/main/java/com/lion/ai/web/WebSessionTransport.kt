package com.lion.ai

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Pure request broker. The Android host owns WebViews; workers only wait on request-scoped results. */
object WebSessionTransport {
    /** ملف يُرفق آلياً بصفحة الموقع (صورة غالباً). المحتوى base64 كما في ImageAttachment. */
    data class WebFile(val name: String, val mime: String, val base64: String)

    data class Request(
        val token: String, val provider: String, val prompt: String, val timeoutMs: Int, val fresh: Boolean = false,
        /** ملخص سياق كامل يُلحق إن أعاد المضيف تحميل الصفحة، وملخص لما فات الصفحة فقط إن لم يُعد. */
        val recapFull: String = "", val recapDelta: String = "",
        /** محادثة التطبيق صاحبة الطلب: تُمرَّر صراحةً بدل متغير عام يتغير عند تبديل علامة التبويب. */
        val chatId: String = "",
        val files: List<WebFile> = emptyList()
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
    private val activeChat = AtomicReference<String?>(null)
    /** وقت آخر «إيقاف» لكل محادثة: يلغي الطلبات المنتظرة في الطابور لتلك المحادثة فقط. */
    private val cancelledAt = ConcurrentHashMap<String, Long>()
    /** طابور عادل: الرسائل المتزامنة (غرفة العمليات/أدوات/مزودات) تنتظر دورها بدل الرفض الفوري. */
    private val gate = java.util.concurrent.Semaphore(1, true)
    /** تمديد مهلة طلب جارٍ بينما الصفحة ما زالت تكتب/تولّد: المضيف يستدعيه فتنتظر الدردشة بدل أن تقطع. */
    private val extended = ConcurrentHashMap<String, Long>()
    fun extend(token: String, untilMs: Long) { extended.merge(token, untilMs) { a, b -> maxOf(a, b) } }
    fun attach(value: Host) { host = value }
    fun detach(value: Host) { if (host === value) host = null }
    fun attached(): Boolean = host != null
    fun open(provider: String) { host?.open(provider) }
    fun inspect(provider: String) { host?.inspect(provider) }

    /**
     * زر «إيقاف» في الشات. مع chatId: يلغي طلب تلك المحادثة فقط (كان إيقاف علامة تبويب يقتل طلب علامة أخرى)،
     * ويمنع أي رسالة منتظرة في الطابور من محادثتها أن تُرسل للموقع بعد الإيقاف. بدونه: يلغي الطلب الجاري أياً كان.
     */
    fun cancelActive(chatId: String = "") {
        if (chatId.isNotBlank()) {
            cancelledAt[chatId] = System.currentTimeMillis()
            if (activeChat.get() != chatId) return
        }
        active.get()?.let { host?.cancel(it) }
    }

    private fun cancelledSince(chatId: String, startedAt: Long): Boolean =
        chatId.isNotBlank() && (cancelledAt[chatId] ?: 0L) >= startedAt

    fun exchange(
        provider: String, prompt: String, timeoutMs: Int, fresh: Boolean = false,
        recapFull: String = "", recapDelta: String = "",
        chatId: String = "", files: List<WebFile> = emptyList()
    ): Reply {
        val current = host ?: return Reply("⚠️ افتح التطبيق لتشغيل جلسة الويب.", false)
        if (current.isUiThread()) return Reply("⚠️ لا يمكن انتظار الويب على خيط الواجهة.", false)
        val token = UUID.randomUUID().toString()
        val timeout = timeoutMs.coerceIn(1000, 420_000)
        val startedAt = System.currentTimeMillis()
        var acquired = false
        try {
            // انتظار على شرائح قصيرة كي يلتقط «إيقاف» المستخدم الطلبات المنتظرة بدل تنفيذها لاحقاً.
            while (true) {
                val left = startedAt + timeout - System.currentTimeMillis()
                if (left <= 0L) break
                if (gate.tryAcquire(minOf(left, 400L), TimeUnit.MILLISECONDS)) { acquired = true; break }
                if (cancelledSince(chatId, startedAt)) return Reply("⚠️ أُلغي الطلب قبل إرساله للموقع.", false)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Reply("⚠️ أُلغي الانتظار في طابور جلسة الويب.", false)
        }
        if (!acquired) return Reply("⚠️ جلسة الويب مشغولة برسالة أخرى طويلة. أعد المحاولة بعد انتهائها.", false)
        if (cancelledSince(chatId, startedAt)) {
            gate.release()
            return Reply("⚠️ أُلغي الطلب قبل إرساله للموقع.", false)
        }
        active.set(token)
        activeChat.set(chatId.ifBlank { null })
        val result = AtomicReference<Reply?>(null)
        val latch = CountDownLatch(1)
        return try {
            current.start(Request(token, provider, prompt, timeout, fresh, recapFull, recapDelta, chatId, files)) { reply ->
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
            if (active.compareAndSet(token, null)) activeChat.set(null)
            gate.release()
        }
    }

    /**
     * Web composers are already a live conversation. Dumping system+history into the
     * input prevents Send and never returns a real site answer to the app.
     */
    fun prompt(system: String, history: List<ChatTurn>, message: String): String = WebToolBridge.shape(system, message)
}
