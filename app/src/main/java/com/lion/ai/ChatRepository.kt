package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * titleSource: none = لا عنوان بعد، auto = من أول سطر في الرسالة،
 * ai = عنوان ذكي من النموذج، user = اسم كتبه المستخدم (لا يُستبدل أبداً).
 */
data class ChatMeta(
    val id: String,
    val title: String,
    val titleSource: String,
    val archived: Boolean,
    val updatedAt: Long,
    val count: Int
)

object ChatRepository {
    private const val KEY_META = "chats_meta_v2"
    const val DEFAULT_TITLE = "محادثة جديدة"

    /** كاش في الذاكرة: يمنع فك تشفير/تحليل JSON كامل عند كل رسالة أو تبديل محادثة. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Message>>()

    fun newId(): String = "chat_" + System.currentTimeMillis()

    fun load(prefs: SharedPreferences, id: String): List<Message> =
        cache[id] ?: ChatStore.decode(prefs.getString("chat_$id", "") ?: "")
            .also { if (it.isNotEmpty()) cache[id] = it }

    fun all(prefs: SharedPreferences): List<ChatMeta> =
        read(prefs).sortedByDescending { it.updatedAt }

    fun get(prefs: SharedPreferences, id: String): ChatMeta? =
        read(prefs).firstOrNull { it.id == id }

    fun save(prefs: SharedPreferences, id: String, messages: List<Message>) {
        if (messages.isEmpty()) {
            cache.remove(id)
            return
        }
        cache[id] = messages
        prefs.edit().putString("chat_$id", ChatStore.encode(messages)).apply()
        val list = read(prefs)
        val index = list.indexOfFirst { it.id == id }
        val old = list.getOrNull(index)
        var title = old?.title ?: DEFAULT_TITLE
        var source = old?.titleSource ?: "none"
        if (source == "none" || source == "auto") {
            val fallback = fallbackTitle(messages)
            if (fallback != null) {
                title = fallback
                source = "auto"
            }
        }
        val meta = ChatMeta(
            id = id,
            title = title,
            titleSource = source,
            archived = old?.archived ?: false,
            updatedAt = System.currentTimeMillis(),
            count = messages.size
        )
        if (index >= 0) list[index] = meta else list.add(meta)
        write(prefs, list)
    }

    fun rename(prefs: SharedPreferences, id: String, newTitle: String): Boolean {
        val clean = newTitle.trim().replace(Regex("\\s+"), " ").take(60)
        if (clean.isEmpty()) return false
        val list = read(prefs)
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false
        list[index] = list[index].copy(title = clean, titleSource = "user")
        write(prefs, list)
        return true
    }

    fun setAiTitle(prefs: SharedPreferences, id: String, title: String) {
        val list = read(prefs)
        val index = list.indexOfFirst { it.id == id }
        if (index < 0 || list[index].titleSource == "user") return
        list[index] = list[index].copy(title = title.take(60), titleSource = "ai")
        write(prefs, list)
    }

    fun setArchived(prefs: SharedPreferences, id: String, archived: Boolean) {
        val list = read(prefs)
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list[index] = list[index].copy(archived = archived)
        write(prefs, list)
    }

    fun delete(prefs: SharedPreferences, id: String) {
        cache.remove(id)
        val list = read(prefs).filter { it.id != id }
        val legacyIds = (prefs.getString("chat_ids", "") ?: "")
            .split(",")
            .filter { it.isNotBlank() && it != id }
            .joinToString(",")
        prefs.edit()
            .remove("chat_$id")
            .remove("chat_title_$id")
            .putString("chat_ids", legacyIds)
            .apply()
        write(prefs, list)
    }

    fun fallbackTitle(messages: List<Message>): String? {
        val first = messages.firstOrNull { it.isUser }?.text ?: return null
        val line = first.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.removePrefix("📎")
            ?.removePrefix("🖼️")
            ?.trim()
            .orEmpty()
        if (line.isEmpty()) return null
        return if (line.length > 40) line.take(40).trimEnd() + "…" else line
    }

    private fun read(prefs: SharedPreferences): MutableList<ChatMeta> {
        if (!prefs.contains(KEY_META)) migrate(prefs)
        return decode(prefs.getString(KEY_META, "") ?: "")
    }

    private fun write(prefs: SharedPreferences, list: List<ChatMeta>) {
        prefs.edit().putString(KEY_META, encode(list)).apply()
    }

    private fun migrate(prefs: SharedPreferences) {
        val ids = (prefs.getString("chat_ids", "") ?: "")
            .split(",")
            .filter { it.isNotBlank() }
            .toMutableList()
        if ("default" !in ids && !(prefs.getString("chat_default", "") ?: "").isBlank()) {
            ids.add(0, "default")
        }
        val now = System.currentTimeMillis()
        val distinct = ids.distinct()
        val list = distinct.mapIndexedNotNull { index, id ->
            val messages = load(prefs, id)
            if (messages.isEmpty()) return@mapIndexedNotNull null
            val stored = (prefs.getString("chat_title_$id", "") ?: "").trim()
            val good = stored.isNotEmpty() && stored != "محادثة" && stored != DEFAULT_TITLE
            ChatMeta(
                id = id,
                title = if (good) stored else (fallbackTitle(messages) ?: DEFAULT_TITLE),
                titleSource = "auto",
                archived = false,
                updatedAt = now - (distinct.size - index) * 1000L,
                count = messages.size
            )
        }
        prefs.edit().putString(KEY_META, encode(list)).apply()
    }

    private fun encode(list: List<ChatMeta>): String {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("src", it.titleSource)
                    .put("arch", it.archived)
                    .put("ts", it.updatedAt)
                    .put("n", it.count)
            )
        }
        return array.toString()
    }

    private fun decode(raw: String): MutableList<ChatMeta> {
        val result = mutableListOf<ChatMeta>()
        if (raw.isBlank()) return result
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                if (id.isEmpty()) continue
                result.add(
                    ChatMeta(
                        id = id,
                        title = item.optString("title", DEFAULT_TITLE).ifBlank { DEFAULT_TITLE },
                        titleSource = item.optString("src", "auto"),
                        archived = item.optBoolean("arch", false),
                        updatedAt = item.optLong("ts", 0L),
                        count = item.optInt("n", 0)
                    )
                )
            }
        } catch (e: Exception) {
        }
        return result
    }
}
