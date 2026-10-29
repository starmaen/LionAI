package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val token: String = "",
    val enabled: Boolean = true
)

class McpException(message: String) : Exception(message)

/**
 * عميل MCP مبسّط فوق Streamable HTTP (JSON-RPC 2.0 + SSE).
 *
 * يسمح بربط خوادم مثل filesystem/shell/git/android-build/session-mesh خلف واجهة
 * واحدة، فيستفيد منها أي مزود يدعم بروتوكول الأدوات النصي في Lion AI.
 */
object McpClient {
    private val ids = AtomicLong(1)
    private val sessions = ConcurrentHashMap<String, String>()
    private val initialized = ConcurrentHashMap.newKeySet<String>()

    fun servers(prefs: SharedPreferences): List<McpServer> {
        val raw = prefs.getString(McpConfig.KEY, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val url = obj.optString("url", "").trim()
                if (url.isBlank()) return@mapNotNull null
                McpServer(
                    id = obj.optString("id", "").ifBlank { "mcp_${index + 1}" },
                    name = obj.optString("name", "").ifBlank { "MCP ${index + 1}" },
                    url = url,
                    token = obj.optString("token", ""),
                    enabled = obj.optBoolean("enabled", true)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addServer(prefs: SharedPreferences, server: McpServer): List<McpServer> {
        val current = servers(prefs).filterNot { it.id == server.id }.toMutableList()
        current.add(server)
        saveServers(prefs, current)
        return current
    }

    fun removeServer(prefs: SharedPreferences, id: String): Boolean {
        val current = servers(prefs)
        val filtered = current.filterNot { it.id == id }
        if (filtered.size == current.size) return false
        saveServers(prefs, filtered)
        sessions.remove(id)
        initialized.remove(id)
        return true
    }

    private fun saveServers(prefs: SharedPreferences, list: List<McpServer>) {
        val array = JSONArray()
        list.forEach { server ->
            array.put(
                JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("url", server.url)
                    .put("token", server.token)
                    .put("enabled", server.enabled)
            )
        }
        prefs.edit().putString(McpConfig.KEY, array.toString()).apply()
    }

    fun find(prefs: SharedPreferences, idOrName: String): McpServer? {
        val needle = idOrName.trim()
        return servers(prefs).firstOrNull { it.id == needle || it.name == needle }
    }

    fun initialize(server: McpServer): JSONObject {
        if (server.id in initialized) {
            return JSONObject().put("ok", true).put("cached", true)
        }
        val response = rpc(
            server,
            "initialize",
            JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject())
                .put(
                    "clientInfo",
                    JSONObject().put("name", "LionAI").put("version", "1.6")
                )
        )
        notify(server, "notifications/initialized", JSONObject())
        initialized.add(server.id)
        return response
    }

    fun listTools(prefs: SharedPreferences, serverRef: String): JSONArray {
        val server = find(prefs, serverRef) ?: throw McpException("خادم MCP غير معروف: $serverRef")
        ensureEnabled(server)
        initialize(server)
        val response = rpc(server, "tools/list", JSONObject())
        return response.optJSONObject("result")?.optJSONArray("tools")
            ?: response.optJSONArray("tools")
            ?: JSONArray()
    }

    fun callTool(
        prefs: SharedPreferences,
        serverRef: String,
        tool: String,
        arguments: JSONObject = JSONObject()
    ): String {
        val server = find(prefs, serverRef) ?: throw McpException("خادم MCP غير معروف: $serverRef")
        ensureEnabled(server)
        initialize(server)
        val response = rpc(
            server,
            "tools/call",
            JSONObject().put("name", tool).put("arguments", arguments)
        )
        val result = response.optJSONObject("result") ?: response
        if (result.optBoolean("isError", false)) {
            throw McpException(extractText(result).ifBlank { "أعاد خادم MCP خطأً" })
        }
        return extractText(result).ifBlank { result.toString() }
    }

    fun ping(prefs: SharedPreferences, serverRef: String): String {
        val server = find(prefs, serverRef) ?: throw McpException("خادم MCP غير معروف: $serverRef")
        ensureEnabled(server)
        initialize(server)
        val started = System.currentTimeMillis()
        val tools = listTools(prefs, serverRef)
        return "✅ ${server.name} متصل — ${tools.length()} أداة — ${System.currentTimeMillis() - started}ms"
    }

    private fun ensureEnabled(server: McpServer) {
        if (!server.enabled) throw McpException("خادم ${server.name} معطّل")
        if (!server.url.startsWith("http://") && !server.url.startsWith("https://")) {
            throw McpException("رابط MCP يجب أن يبدأ بـ http:// أو https://")
        }
    }

    private fun rpc(server: McpServer, method: String, params: JSONObject): JSONObject {
        val id = ids.getAndIncrement()
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        return post(server, payload, id)
    }

    private fun notify(server: McpServer, method: String, params: JSONObject) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
        runCatching { post(server, payload, null) }
    }

    private fun post(server: McpServer, payload: JSONObject, expectedId: Long?): JSONObject {
        val connection = (URL(server.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("MCP-Protocol-Version", "2024-11-05")
            if (server.token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${server.token}")
            }
            sessions[server.id]?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            connection.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let {
                sessions[server.id] = it
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw McpException("HTTP $code من ${server.name}: ${body.take(500)}")
            }
            if (expectedId == null) return JSONObject().put("ok", true)
            if (body.isBlank()) {
                throw McpException("رد فارغ من ${server.name} على ${payload.optString("method")}")
            }
            val contentType = connection.contentType.orEmpty().lowercase()
            val json = if (contentType.contains("text/event-stream") || body.trimStart().startsWith("event:") ||
                body.lineSequence().any { it.startsWith("data:")
            }) {
                parseSse(body, expectedId)
            } else {
                runCatching { JSONObject(body) }.getOrElse {
                    parseSse(body, expectedId)
                }
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSse(body: String, expectedId: Long): JSONObject {
        val events = mutableListOf<String>()
        val current = StringBuilder()
        body.lineSequence().forEach { line ->
            if (line.startsWith("data:")) {
                current.append(line.removePrefix("data:").trim()).append('\n')
            } else if (line.isBlank() && current.isNotEmpty()) {
                events.add(current.toString().trim())
                current.clear()
            }
        }
        if (current.isNotEmpty()) events.add(current.toString().trim())
        events.asReversed().forEach { event ->
            runCatching {
                val json = JSONObject(event)
                if (json.optLong("id", -1L) == expectedId || json.has("result") || json.has("error")) json
                else null
            }.getOrNull()?.let { return it }
        }
        throw McpException("لم أجد رد JSON-RPC المطابق في stream خادم MCP")
    }

    private fun extractText(result: JSONObject): String {
        val content = result.optJSONArray("content")
        if (content != null) {
            val parts = mutableListOf<String>()
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "text" -> parts.add(item.optString("text"))
                    "image" -> parts.add("[صورة ${item.optString("mimeType", "image")} من خادم MCP]")
                    "resource" -> parts.add(item.optJSONObject("resource")?.optString("text").orEmpty())
                    else -> parts.add(item.toString())
                }
            }
            if (parts.any { it.isNotBlank() }) return parts.joinToString("\n")
        }
        return result.optString("text", "")
    }
}

object McpConfig {
    const val KEY = "mcp_servers_v1"

    fun mask(server: McpServer): String = buildString {
        append(if (server.enabled) "🟢" else "⚪")
        append(' ').append(server.name).append(" (").append(server.id).append(") — ").append(server.url)
        if (server.token.isNotBlank()) append(" — 🔑 مخفي")
    }
}
