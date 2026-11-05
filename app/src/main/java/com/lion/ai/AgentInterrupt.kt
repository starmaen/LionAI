package com.lion.ai

import java.util.concurrent.ConcurrentHashMap

/**
 * مقاطعة المستخدم أثناء عمل الوكيل: يطلب التوقف عند أقرب نقطة آمنة (بين خطوتين)،
 * وتُحفظ رسالته لتُرسل فور التوقف فيجيب عنها الوكيل ثم يكمل من نقطة التوقف.
 */
object AgentInterrupt {
    private val requested = ConcurrentHashMap<String, Boolean>()
    private val queued = ConcurrentHashMap<String, String>()

    fun request(chatId: String, message: String) {
        queued[chatId] = (queued[chatId]?.let { "$it\n" }.orEmpty() + message).take(4000)
        requested[chatId] = true
    }

    /** يستهلك طلب التوقف مرة واحدة. */
    fun consume(chatId: String): Boolean = requested.remove(chatId) == true

    fun takeQueued(chatId: String): String? = queued.remove(chatId)

    fun hasQueued(chatId: String): Boolean = queued.containsKey(chatId)
}
