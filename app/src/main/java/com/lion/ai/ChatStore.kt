package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject

object ChatStore {

    fun encode(messages: List<Message>): String {
        val array = JSONArray()
        messages.forEach { message ->
            val item = JSONObject()
                .put("text", message.text)
                .put("user", message.isUser)
            message.ytUrl?.let { item.put("yt", it) }
            message.ytmUrl?.let { item.put("ytm", it) }
            array.put(item)
        }
        return array.toString()
    }

    fun decode(raw: String): List<Message> {
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val text = item.optString("text", "")
                if (text.isEmpty()) return@mapNotNull null
                Message(
                    text = text,
                    isUser = item.optBoolean("user", false),
                    ytUrl = item.optString("yt", "").takeIf { it.isNotBlank() },
                    ytmUrl = item.optString("ytm", "").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            decodeLegacy(raw)
        }
    }

    private fun decodeLegacy(raw: String): List<Message> {
        return raw.split("\n---\n")
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split("|", limit = 2)
                val text = parts.getOrElse(1) { "" }
                if (text.isEmpty()) return@mapNotNull null
                Message(text = text, isUser = parts.getOrElse(0) { "u" } == "u")
            }
    }
}
