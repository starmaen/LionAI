package com.lion.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * حالة صفحات الويب كما تراها واجهة الدردشة: أخضر = الصفحة حيّة وجاهزة/تعمل،
 * أصفر = تحميل أو تحتاج تسجيل دخول، أحمر = أُغلقت أو فشلت، رمادي = لم تُفتح بعد.
 */
object WebSessionStatus {
    enum class Level { GREEN, AMBER, RED, IDLE }
    data class Info(val level: Level, val text: String)

    val state = mutableStateMapOf<String, Info>()
    var hostAlive by mutableStateOf(false)

    fun set(provider: String, level: Level, text: String) { state[provider] = Info(level, text) }

    fun markAllClosed(text: String) {
        hostAlive = false
        state.keys.toList().forEach { state[it] = Info(Level.RED, text) }
    }

    fun infoFor(provider: String): Info =
        if (!hostAlive) Info(Level.RED, "جلسة الويب مغلقة")
        else state[provider] ?: Info(Level.IDLE, "لم تُفتح الصفحة بعد")
}
