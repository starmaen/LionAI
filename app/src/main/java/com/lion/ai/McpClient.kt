package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
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

private class McpSessionExpiredException : Exception()

object McpClient {
    private const val PROTOCOL_VERSION = "2025-06-18"
    private const val MAX_TOOL_PAGES = 20

    private val ids = AtomicLong(1)
    private val sessions = ConcurrentHashMap<String, String>()
    private val initialized = ConcurrentHashMap.newKeySet<String>()
    private val negotiated = ConcurrentHashMap<String, String>()

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
        resetState(server.id)
        return current
    }

    fun removeServer(prefs: SharedPreferences, id: String): Boolean {
        val current = servers(prefs)
        val filtered = current.filterNot { it.id == id }
        if (filtered.size == current.size) return false
        saveServers(prefs, filtered)
        resetState(id)
        return true
    }

    private fun resetState(id: String) {
        sessions.remove(id)
        initialized.remove(id)
        negotiated.remove(id)
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
        prefs.edit().putString(McpConfig.KEY, array.toString()).commit()
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
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject())
                .put(
                    "clientInfo",
                    JSONObject().put("name", "LionAI").put("version", "1.6")
                )
        )
        response.optJSONObject("result")
            ?.optString("protocolVersion", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { negotiated[server.id] = it }
        notify(server, "notifications/initialized", JSONObject())
        initialized.add(server.id)
        return response
    }

    fun listTools(prefs: SharedPreferences, serverRef: String): JSONArray {
        val server = find(prefs, serverRef) ?: throw McpException("خادم MCP غير معروف: $serverRef")
        ensureEnabled(server)
        initialize(server)
        val all = JSONArray()
        var cursor: String? = null
        var pages = 0
        do {
            val params = JSONObject()
            if (!cursor.isNullOrBlank()) params.put("cursor", cursor)
            val response = rpc(server, "tools/list", params)
            val result = response.optJSONObject("result") ?: response
            val page = result.optJSONArray("tools")
            if (page != null) {
                for (i in 0 until page.length()) all.put(page.get(i))
            }
            cursor = if (result.isNull("nextCursor")) null
            else result.optString("nextCursor", "").takeIf { it.isNotBlank() }
            pages++
        } while (cursor != null && pages < MAX_TOOL_PAGES)
        return all
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
        val url = server.url.trim()
        val isHttp = url.startsWith("http://")
        val isHttps = url.startsWith("https://")
        if (!isHttp && !isHttps) {
            throw McpException("رابط MCP يجب أن يبدأ بـ http:// أو https://")
        }
        if (isHttp && server.token.isNotBlank()) {
            val host = runCatching { URL(url).host }.getOrDefault("")
            if (!isPrivateHost(host)) {
                throw McpException(
                    "لا يُرسل التوكن عبر http:// إلى مضيف عام (${server.name}). استخدم https:// أو مضيفاً محلياً."
                )
            }
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h == "::1" || h == "[::1]" || h.endsWith(".local")) return true
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 ||
            parts[0] == 127 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 100 && parts[1] in 64..127)
    }

    private fun rpc(
        server: McpServer,
        method: String,
        params: JSONObject,
        retryOnExpiry: Boolean = true
    ): JSONObject {
        val id = ids.getAndIncrement()
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        val response = try {
            post(server, payload, id)
        } catch (e: McpSessionExpiredException) {
            if (!retryOnExpiry || method == "initialize") {
                throw McpException("انتهت جلسة MCP مع ${server.name} ولم تنجح إعادة الاتصال")
            }
            resetState(server.id)
            initialize(server)
            return rpc(server, method, params, retryOnExpiry = false)
        }
        throwIfError(server, method, response)
        return response
    }

    private fun throwIfError(server: McpServer, method: String, response: JSONObject) {
        val error = response.optJSONObject("error") ?: return
        val code = error.optInt("code", 0)
        val message = error.optString("message", "خطأ غير معروف")
        throw McpException("خادم ${server.name} رفض $method (JSON-RPC $code): $message")
    }

    private fun notify(server: McpServer, method: String, params: JSONObject) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
        runCatching { post(server, payload, null) }
    }

    private fun post(server: McpServer, payload: JSONObject, expectedId: Long?): JSONObject {
        val method = payload.optString("method")
        val sentSession = sessions[server.id]
        val connection = (URL(server.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (method != "initialize") {
                setRequestProperty("MCP-Protocol-Version", negotiated[server.id] ?: PROTOCOL_VERSION)
            }
            if (server.token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${server.token}")
            }
            if (sentSession != null) setRequestProperty("Mcp-Session-Id", sentSession)
        }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            connection.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let {
                sessions[server.id] = it
            }
            if (code !in 200..299) {
                val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code == 404 && sentSession != null && method != "initialize") {
                    throw McpSessionExpiredException()
                }
                val hint = if (code == 401 || code == 403) " — تحقق من التوكن/الصلاحيات" else ""
                throw McpException("HTTP $code من ${server.name}$hint: ${body.take(500)}")
            }
            if (expectedId == null) return JSONObject().put("ok", true)
            val contentType = connection.contentType.orEmpty().lowercase()
            if (contentType.contains("text/event-stream")) {
                readSseResponse(connection.inputStream, expectedId, server.name, method)
            } else {
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseBody(body, expectedId, server.name, method)
            }
        } catch (e: SocketTimeoutException) {
            throw McpException("انتهت مهلة الاتصال بخادم ${server.name} أثناء $method")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseBody(body: String, expectedId: Long, serverName: String, method: String): JSONObject {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) throw McpException("رد فارغ من $serverName على $method")
        if (trimmed.startsWith("{")) {
            runCatching { JSONObject(trimmed) }.getOrNull()?.let { return it }
        }
        return readSseResponse(
            ByteArrayInputStream(trimmed.toByteArray(Charsets.UTF_8)),
            expectedId,
            serverName,
            method
        )
    }

    private fun readSseResponse(
        stream: InputStream,
        expectedId: Long,
        serverName: String,
        method: String
    ): JSONObject {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        val data = StringBuilder()
        var line = reader.readLine()
        while (line != null) {
            if (line.startsWith("data:")) {
                data.append(line.removePrefix("data:").trimStart()).append('\n')
            } else if (line.isBlank()) {
                matchEvent(data.toString(), expectedId)?.let { return it }
                data.setLength(0)
            }
            line = reader.readLine()
        }
        matchEvent(data.toString(), expectedId)?.let { return it }
        throw McpException("لم أجد رد JSON-RPC المطابق في stream خادم $serverName ($method)")
    }

    private fun matchEvent(raw: String, expectedId: Long): JSONObject? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.has("method")) return null
        if (!json.has("result") && !json.has("error")) return null
        if (!json.isNull("id") && json.optLong("id", -1L) != expectedId) return null
        return json
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
