package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject

object ChatStore {

    fun encode(messages: List<Message>): String {
        val array = JSONArray()
        messages.forEach { message ->
            val storedText = if (message.pending != null) {
                message.text.substringBefore("\n\nاختر") + "\n\n⌛ لم يتم التأكيد."
            } else {
                message.text
            }
            val item = JSONObject()
                .put("text", storedText)
                .put("user", message.isUser)
            message.ytUrl?.let { item.put("yt", it) }
            message.ytmUrl?.let { item.put("ytm", it) }
            message.anghamiUrl?.let { item.put("angh", it) }
            message.browserUrl?.let { item.put("browser", it) }
            message.via?.let { item.put("via", it) }
            if (message.sources.isNotEmpty()) {
                val sources = JSONArray()
                message.sources.forEach { source ->
                    sources.put(
                        JSONObject()
                            .put("title", source.title)
                            .put("url", source.url)
                            .put("snippet", source.snippet)
                    )
                }
                item.put("sources", sources)
            }
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
                val sourcesArray = item.optJSONArray("sources")
                val sources = if (sourcesArray == null) {
                    emptyList()
                } else {
                    (0 until sourcesArray.length()).mapNotNull { sourceIndex ->
                        val source = sourcesArray.optJSONObject(sourceIndex) ?: return@mapNotNull null
                        val url = source.optString("url", "").trim()
                        if (url.isBlank()) return@mapNotNull null
                        WebSource(
                            title = source.optString("title", "").trim().ifBlank { "مصدر" },
                            url = url,
                            snippet = source.optString("snippet", "").trim()
                        )
                    }
                }
                Message(
                    text = text,
                    isUser = item.optBoolean("user", false),
                    ytUrl = item.optString("yt", "").takeIf { it.isNotBlank() },
                    ytmUrl = item.optString("ytm", "").takeIf { it.isNotBlank() },
                    anghamiUrl = item.optString("angh", "").takeIf { it.isNotBlank() },
                    browserUrl = item.optString("browser", "").takeIf { it.isNotBlank() },
                    via = item.optString("via", "").takeIf { it.isNotBlank() },
                    sources = sources
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
