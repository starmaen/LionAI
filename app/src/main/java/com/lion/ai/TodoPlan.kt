package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** خطة مهام قصيرة يكتبها المزود لتتبّع التقدّم في المهام الطويلة (مثل TodoWrite في Claude Code). */
internal object TodoPlan {
    data class Item(val text: String, val status: String)

    private const val MAX_ITEMS = 20
    private const val MAX_TEXT = 160

    private fun status(raw: String): String = when (raw.trim().lowercase()) {
        "done", "completed", "complete", "finished", "✓", "✅", "منجز", "تم" -> "done"
        "in_progress", "in-progress", "active", "doing", "current", "جارٍ", "جاري" -> "in_progress"
        else -> "pending"
    }

    fun parse(items: JSONArray?): List<Item> {
        if (items == null || items.length() == 0) {
            throw IllegalArgumentException("items مطلوب: مصفوفة خطوات، كل عنصر {\"text\":\"...\",\"status\":\"pending|in_progress|done\"}")
        }
        val out = mutableListOf<Item>()
        for (i in 0 until minOf(items.length(), MAX_ITEMS)) {
            when (val e = items.opt(i)) {
                is JSONObject -> {
                    val t = e.optString("text").ifBlank { e.optString("content") }.trim()
                    if (t.isNotBlank()) out.add(Item(t.take(MAX_TEXT), status(e.optString("status"))))
                }
                is String -> if (e.isNotBlank()) out.add(Item(e.trim().take(MAX_TEXT), "pending"))
            }
        }
        if (out.isEmpty()) throw IllegalArgumentException("لا توجد خطوات صالحة في items")
        return out
    }

    fun render(list: List<Item>): String {
        val done = list.count { it.status == "done" }
        return buildString {
            append("📋 الخطة (").append(done).append('/').append(list.size).append(" منجزة)\n")
            list.forEach {
                append(when (it.status) { "done" -> "✅ "; "in_progress" -> "🔄 "; else -> "⬜ " }).append(it.text).append('\n')
            }
        }.trimEnd()
    }

    fun save(prefs: SharedPreferences, chatId: String, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("text", it.text).put("status", it.status)) }
        prefs.edit().putString("todo_" + chatId, arr.toString()).apply()
    }

    fun load(prefs: SharedPreferences, chatId: String): List<Item> = runCatching {
        parse(JSONArray(prefs.getString("todo_" + chatId, "[]")))
    }.getOrDefault(emptyList())
}
