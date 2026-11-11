package com.lion.ai

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * مركز أحداث الانعكاس الحيّ بين صفحات مزودات الويب وشات التطبيق.
 *
 * الحالة القديمة: أثناء توليد الرد داخل الصفحة كان النص يُقتطع عند 3500 حرف ويُكتب
 * في بطاقة «مسار التنفيذ» فقط، فلا يرى المستخدم التفاعل الحقيقي إلا بعد اكتماله.
 * الآن كل حدث هنا يصل لشات التطبيق فوراً بالنص الكامل:
 *  - outbound: النص الكامل الذي كُتب في حقل الصفحة (سياق + رسالة المستخدم).
 *  - partial:  نص الرد وهو يتكوّن داخل الصفحة، كاملاً بلا أي قصّ.
 *  - stage:    ملاحظة مرحلة قصيرة (تجهيز/إرسال/انتظار) لبطاقة المسار.
 *  - capture:  أدوار محادثة الصفحة (مستخدم/مساعد) المنعكسة إلى الشات، بما فيها التفاعل اليدوي.
 *
 * كائن نقي بلا اعتماديات أندرويد ليكون قابلاً للاختبار على JVM؛ والمستمع يُستدعى على
 * الخيط نفسه الذي بث الحدث (مضيف الجلسة يعمل على خيط الواجهة أصلاً).
 */
object WebSessionMirror {
    interface Listener {
        fun onOutbound(chatId: String, provider: String, fullText: String)
        fun onPartial(chatId: String, provider: String, fullText: String)
        fun onStage(chatId: String, provider: String, note: String)
        /** أدوار جديدة من صفحة الموقع: دور واحد لكل نداء (user أو assistant). */
        fun onCapturedTurn(chatId: String, provider: String, role: String, text: String)
    }

    @Volatile private var listener: Listener? = null

    fun attach(value: Listener) { listener = value }
    fun detach(value: Listener) { if (listener === value) listener = null }
    fun attached(): Boolean = listener != null

    fun outbound(chatId: String, provider: String, fullText: String) {
        listener?.onOutbound(chatId, provider, fullText)
    }

    fun partial(chatId: String, provider: String, fullText: String) {
        if (fullText.isNotBlank()) listener?.onPartial(chatId, provider, fullText)
    }

    fun stage(chatId: String, provider: String, note: String) {
        listener?.onStage(chatId, provider, note)
    }

    fun capturedTurn(chatId: String, provider: String, role: String, text: String) {
        if (text.isNotBlank()) listener?.onCapturedTurn(chatId, provider, role, text)
    }
}

/**
 * ترشيح أدوار صفحة الموقع قبل عكسها في الشات.
 *
 * الهدف: «كل تفاعلات الدردشة داخل صفحة الويب تظهر في شات التطبيق» دون تكرار ما
 * أرسله التطبيق أو استقبله سابقاً. نحفظ بصمة كل نص انعكس أو أُرسل من التطبيق،
 * ونحفظ بصمة آخر رد مكتمل، فلا يمرّ للشات إلا دورٌ جديد فعلاً (رسالة كتبها
 * المستخدم داخل الصفحة بنفسه، أو ردٌ من موقع آخر لم يمره الشات).
 */
class WebMirrorFilter(private val maxSignatures: Int = 400) {
    private val seen = ConcurrentLinkedQueue<String>()
    private val seenSet = LinkedHashSet<String>()

    /** بصمة مطاطة: تتسامح مع فروق المسافات والأسطر الجديدة بين عرض الموقع والنص الأصلي. */
    private fun signature(text: String): String =
        text.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim().take(600).lowercase()

    private fun remember(sig: String) {
        if (sig.isEmpty()) return
        synchronized(this) {
            if (!seenSet.add(sig)) return
            seen.add(sig)
            if (seenSet.size > maxSignatures) {
                val oldest = seen.poll()
                if (oldest != null) seenSet.remove(oldest)
            }
        }
    }

    /** علم التطبيق بما كتبه هو نفسه في الصفحة (رسالة أو رد) كي لا ينعكس مكرراً. */
    fun markOwn(text: String) { remember(signature(text)) }

    /** صحيح إذا كان النص جديداً فعلاً (لم يُعلم منه التطبيق ولا انعكس سابقاً). */
    fun isNew(text: String): Boolean {
        val sig = signature(text)
        if (sig.isEmpty()) return false
        synchronized(this) { return !seenSet.contains(sig) }
    }

    /** علّم النص كمنعكس الآن، وأعد صحيحاً إن كان جديداً (استخدمه قبل البث). */
    fun admit(text: String): Boolean {
        val sig = signature(text)
        if (sig.isEmpty()) return false
        synchronized(this) {
            if (seenSet.contains(sig)) return false
            remember(sig)
            return true
        }
    }

    fun clear() {
        synchronized(this) {
            seenSet.clear()
            seen.clear()
        }
    }
}
