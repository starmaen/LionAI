package com.lion.ai

import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * جسر أسئلة الوكيل التفاعلية (ASK) داخل حلقة التنفيذ نفسها.
 *
 * المشكلة التي يعالجها: كان سؤال [[ASK]] يُنهي دورة الوكيل ويحفظ الجلسة ثم يبدأ ردّ المستخدم
 * دورة جديدة. النتيجة: خطوات تُعاد، سياق يُفقد، وشعور بأن المهمة توقفت فعلهاً.
 *
 * الحل: تنتظر الحلقة ردّ المستخدم **في نفس الـ coroutine** (نفس الخطوات، نفس السياق، نفس
 * إذن الكتابة)، ويُسلَّم الردّ من واجهة المحادثة مباشرة عبر [deliver]. إن لم يصل ردّ خلال
 * [WAIT_MS] (أو أُوقفت المحادثة) نعود إلى السلوك القديم الآمن: إيقاف مؤقت + حفظ الجلسة
 * وسؤال معلّق، فيكمل ردّ المستخدم التالي من النقطة نفسها بلا فقدان عمل.
 */
object AgentAsk {
    /** مفتاح التفعيل في الإعدادات (مفعّل افتراضياً). */
    const val KEY_ENABLED = AGENT_INLINE_ASK

    /** أقصى انتظار داخل الحلقة (8 دقائق) قبل التحوّل إلى الإيقاف المؤقت المحفوظ. */
    const val WAIT_MS = 8L * 60 * 1000

    private class Slot {
        val answer = CompletableDeferred<String>()
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    /** هل الحلقة الحالية للمحادثة تنتظر ردّاً؟ (تُستخدم في واجهة الإرسال). */
    fun isWaiting(chatId: String): Boolean = chatId.isNotBlank() && slots.containsKey(chatId)

    fun waitingChatIds(): List<String> = slots.keys.toList()

    /** يسلّم ردّ المستخدم إلى الحلقة المنتظرة. يعيد false إن لم يكن هناك انتظار حقيقي. */
    fun deliver(chatId: String, answer: String): Boolean {
        val slot = slots[chatId] ?: return false
        val text = answer.trim()
        if (text.isEmpty()) return false
        slots.remove(chatId)
        return slot.answer.complete(text)
    }

    /** إلغاء الانتظار (إيقاف المحادثة أو تغيير الجلسة). */
    fun cancel(chatId: String) {
        slots.remove(chatId)
    }

    fun clear() = slots.clear()

    /**
     * ينتظر الرد داخل الحلقة. يعيد النص، أو null عند انتهاء المهلة/الإلغاء
     * (فيتولّى المستدعي الحفظ والإيقاف المؤقت).
     */
    suspend fun await(chatId: String, timeoutMs: Long = WAIT_MS): String? {
        if (chatId.isBlank()) return null
        val slot = Slot()
        slots[chatId] = slot
        return try {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(5_000L)) { slot.answer.await() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } finally {
            if (slots[chatId] === slot) slots.remove(chatId)
        }
    }

    /** هل الواجهة تسمح بالانتظار داخل الحلقة؟ */
    fun enabled(prefs: SharedPreferences): Boolean = runCatching {
        prefs.getBoolean(AGENT_INLINE_ASK, true)
    }.getOrDefault(true)
}
