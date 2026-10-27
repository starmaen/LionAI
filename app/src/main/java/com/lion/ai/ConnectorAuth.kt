package com.lion.ai

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AuthType { GOOGLE, TOKEN, CUSTOM }

data class ConnectorDef(
    val id: String,
    val name: String,
    val icon: String,
    val auth: AuthType,
    val description: String,
    val scopes: List<String> = emptyList(),
    val tokenHint: String = ""
)

/**
 * ربط حسابات المستخدم بالموصلات العالمية عبر OAuth 2.0 (PKCE + loopback)
 * أو عبر توكن شخصي. تُحفظ الرموز مشفّرة في SecurePrefs فقط.
 */
object ConnectorAuth {
    const val GOOGLE_CLIENT_ID = "google_oauth_client_id"
    const val GOOGLE_CLIENT_SECRET = "google_oauth_client_secret"

    private const val GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val GOOGLE_TOKEN = "https://oauth2.googleapis.com/token"
    private const val GOOGLE_USERINFO = "https://www.googleapis.com/oauth2/v3/userinfo"
    private const val GOOGLE_REVOKE = "https://oauth2.googleapis.com/revoke"
    private const val REDIRECT_HOST = "127.0.0.1"
    private const val TIMEOUT_MS = 180_000

    /** منفذ ثابت حتى يستطيع المستخدم تسجيل نفس Redirect URI في Google Cloud لنوع Web application أيضاً. */
    const val DEFAULT_REDIRECT_PORT = 53682
    private val PREFERRED_PORTS = intArrayOf(DEFAULT_REDIRECT_PORT, 53683, 53684, 53685)
    val defaultRedirectUri: String get() = "http://$REDIRECT_HOST:$DEFAULT_REDIRECT_PORT"

    private const val LAST_REDIRECT = "google_oauth_last_redirect"

    fun lastRedirectUri(prefs: SharedPreferences): String =
        prefs.getString(LAST_REDIRECT, "").orEmpty().ifBlank { defaultRedirectUri }

    val all: List<ConnectorDef> = listOf(
        ConnectorDef(
            "google_drive", "Google Drive", "📁", AuthType.GOOGLE,
            "قراءة ورفع وإدارة ملفات الدرايف",
            listOf(
                "openid", "email", "profile",
                "https://www.googleapis.com/auth/drive"
            )
        ),
        ConnectorDef(
            "gmail", "Gmail", "✉️", AuthType.GOOGLE,
            "بحث وقراءة وإرسال البريد الإلكتروني",
            listOf(
                "openid", "email", "profile",
                "https://www.googleapis.com/auth/gmail.modify",
                "https://www.googleapis.com/auth/gmail.send"
            )
        ),
        ConnectorDef(
            "google_calendar", "Google Calendar", "📅", AuthType.GOOGLE,
            "عرض المواعيد وإنشاء الأحداث",
            listOf(
                "openid", "email", "profile",
                "https://www.googleapis.com/auth/calendar"
            )
        ),
        ConnectorDef(
            "notion", "Notion", "📝", AuthType.TOKEN,
            "البحث في الصفحات وقراءتها وإنشاء صفحات",
            tokenHint = "التقط توكن Integration من notion.so/my-integrations وشارك الصفحة معه"
        ),
        ConnectorDef(
            "slack", "Slack", "💬", AuthType.TOKEN,
            "إرسال رسائل وقراءة القنوات",
            tokenHint = "استخدم Bot Token يبدأ بـ xoxb- مع صلاحيات chat:write وchannels:read"
        ),
        ConnectorDef(
            "telegram", "Telegram Bot", "✈️", AuthType.TOKEN,
            "إرسال وقراءة رسائل البوت وإدارة القنوات والمجموعات",
            tokenHint = "أنشئ بوتاً من @BotFather وألصق التوكن هنا. البوت يرى المحادثات التي أُضيف إليها فقط."
        ),
        ConnectorDef(
            "whatsapp", "WhatsApp Business", "💬", AuthType.TOKEN,
            "إرسال رسائل وقوالب WhatsApp Cloud API الرسمية",
            tokenHint = "الصق بالصيغة PHONE_NUMBER_ID:ACCESS_TOKEN من Meta for Developers. هذا لرقم أعمال رسمي، وليس حساب واتساب الشخصي."
        ),
        ConnectorDef(
            "generic", "خدمة REST مخصّصة", "🔗", AuthType.CUSTOM,
            "اربط أي API بمفتاح أو رأس مصادقة خاص بك"
        )
    )

    fun anyConnected(prefs: SharedPreferences): Boolean = all.any { isConnected(prefs, it.id) }

    fun find(id: String): ConnectorDef? = all.firstOrNull { it.id == id }

    fun tokenKey(id: String) = "connector_${id}_token"
    fun accessKey(id: String) = "connector_${id}_access"
    fun refreshKey(id: String) = "connector_${id}_refresh"
    fun expiresKey(id: String) = "connector_${id}_expires"
    fun accountKey(id: String) = "connector_${id}_account"
    fun scopesKey(id: String) = "connector_${id}_scopes"
    fun baseKey(id: String) = "connector_${id}_base"
    fun headerKey(id: String) = "connector_${id}_header"
    fun valueKey(id: String) = "connector_${id}_value"

    fun isConnected(prefs: SharedPreferences, id: String): Boolean = when (id) {
        "google_drive", "gmail", "google_calendar" ->
            !prefs.getString(accessKey(id), "").isNullOrBlank() ||
                !prefs.getString(refreshKey(id), "").isNullOrBlank()
        "notion", "slack", "telegram", "whatsapp" ->
            !prefs.getString(tokenKey(id), "").isNullOrBlank()
        "generic" -> !prefs.getString(baseKey(id), "").isNullOrBlank()
        else -> false
    }

    fun account(prefs: SharedPreferences, id: String): String =
        prefs.getString(accountKey(id), "").orEmpty()

    fun expiresAt(prefs: SharedPreferences, id: String): Long = prefs.getLong(expiresKey(id), 0L)

    fun scopes(prefs: SharedPreferences, id: String): List<String> =
        prefs.getString(scopesKey(id), "").orEmpty().split(' ').filter { it.isNotBlank() }

    /** يتحقق فعلياً من صلاحية الربط ويجلب اسم الحساب؛ يفيد في تشخيص سبب الفشل. */
    suspend fun test(prefs: SharedPreferences, id: String): String = withContext(Dispatchers.IO) {
        val def = find(id) ?: throw IllegalArgumentException("موصل غير معروف: $id")
        if (!isConnected(prefs, id)) throw IllegalStateException("${def.name} غير مربوط")
        when (def.auth) {
            AuthType.GOOGLE -> {
                val label = googleProfile(prefs, def)
                if (label.isBlank()) throw IllegalStateException("التوكن موجود لكن Google رفضه. أعد الربط.")
                prefs.edit().putString(accountKey(id), label).apply()
                "✅ ${def.name}: $label"
            }
            AuthType.TOKEN -> {
                val token = prefs.getString(tokenKey(id), "").orEmpty()
                val label = validateToken(def, token)
                prefs.edit().putString(accountKey(id), label).apply()
                "✅ ${def.name}: $label"
            }
            AuthType.CUSTOM -> "✅ ${def.name}: ${prefs.getString(baseKey("generic"), "").orEmpty()}"
        }
    }

    fun googleClientId(prefs: SharedPreferences): String =
        prefs.getString(GOOGLE_CLIENT_ID, "").orEmpty().trim()

    fun googleClientSecret(prefs: SharedPreferences): String =
        prefs.getString(GOOGLE_CLIENT_SECRET, "").orEmpty().trim()

    fun saveGoogleClient(prefs: SharedPreferences, id: String, secret: String) {
        prefs.edit()
            .putString(GOOGLE_CLIENT_ID, id.trim())
            .putString(GOOGLE_CLIENT_SECRET, secret.trim())
            .apply()
    }

    fun saveToken(prefs: SharedPreferences, id: String, token: String) {
        prefs.edit().putString(tokenKey(id), token.trim()).apply()
    }

    fun saveGeneric(prefs: SharedPreferences, base: String, header: String, value: String) {
        prefs.edit()
            .putString(baseKey("generic"), base.trim())
            .putString(headerKey("generic"), header.trim().ifBlank { "Authorization" })
            .putString(valueKey("generic"), value.trim())
            .putString(accountKey("generic"), runCatching { URL(base.trim()).host }.getOrDefault(""))
            .apply()
    }

    fun disconnect(prefs: SharedPreferences, id: String) {
        prefs.edit()
            .remove(accessKey(id))
            .remove(refreshKey(id))
            .remove(expiresKey(id))
            .remove(accountKey(id))
            .remove(tokenKey(id))
            .remove(scopesKey(id))
            .apply()
    }

    /** إلغاء التوكن لدى Google أيضاً حتى لا يبقى التطبيق مصرّحاً بعد الفصل. */
    suspend fun revoke(prefs: SharedPreferences, id: String) {
        val def = find(id) ?: return
        val token = when (def.auth) {
            AuthType.GOOGLE -> prefs.getString(refreshKey(id), "").orEmpty()
                .ifBlank { prefs.getString(accessKey(id), "").orEmpty() }
            else -> prefs.getString(tokenKey(id), "").orEmpty()
        }
        if (token.isNotBlank()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    postForm(GOOGLE_REVOKE, form("token" to token))
                }
            }
        }
        disconnect(prefs, id)
    }

    /** يربط الحساب ويعيد اسم الحساب/المعرّف للعرض. */
    suspend fun connect(context: Context, prefs: SharedPreferences, id: String): String {
        val def = find(id) ?: throw IllegalArgumentException("موصل غير معروف: $id")
        return when (def.auth) {
            AuthType.GOOGLE -> {
                val clientId = googleClientId(prefs)
                if (clientId.isBlank()) {
                    throw IllegalStateException("أدخل Google OAuth Client ID أولاً (نوع Desktop app)")
                }
                if (!clientId.endsWith(".apps.googleusercontent.com")) {
                    throw IllegalStateException(
                        "Client ID لا يبدو صحيحاً. انسخه كاملاً من Google Cloud Console، وينتهي عادةً بـ .apps.googleusercontent.com"
                    )
                }
                val tokens = authorizeGoogle(context, prefs, def, clientId, googleClientSecret(prefs))
                saveTokens(prefs, def.id, tokens)
                prefs.edit()
                    .putString(scopesKey(def.id), def.scopes.joinToString(" "))
                    .apply()
                val label = withContext(Dispatchers.IO) {
                    runCatching { googleProfile(prefs, def) }.getOrDefault("")
                }
                prefs.edit().putString(accountKey(def.id), label).apply()
                label
            }
            AuthType.TOKEN -> {
                val token = prefs.getString(tokenKey(def.id), "").orEmpty().trim()
                if (token.isBlank()) throw IllegalStateException("ألصق التوكن ثم اضغط ربط")
                val label = withContext(Dispatchers.IO) { validateToken(def, token) }
                prefs.edit().putString(accountKey(def.id), label).apply()
                label
            }
            AuthType.CUSTOM -> {
                val base = prefs.getString(baseKey("generic"), "").orEmpty().trim()
                if (base.isBlank()) throw IllegalStateException("أدخل عنوان الخدمة أولاً")
                val label = runCatching { URL(base).host }.getOrDefault(base)
                prefs.edit().putString(accountKey("generic"), label).apply()
                label
            }
        }
    }

    private fun validateToken(def: ConnectorDef, token: String): String {
        val (url, extra) = when (def.id) {
            "notion" -> "https://api.notion.com/v1/users/me" to mapOf("Notion-Version" to "2022-06-28")
            "slack" -> "https://slack.com/api/auth.test" to mapOf("Content-Type" to "application/json")
            "telegram" -> "https://api.telegram.org/bot$token/getMe" to emptyMap()
            "whatsapp" -> {
                val credentials = WhatsAppClient.parse(token)
                "https://graph.facebook.com/v21.0/${credentials.phoneId}?fields=verified_name,display_phone_number" to
                    mapOf("Authorization" to "Bearer ${credentials.accessToken}")
                }
            else -> return "متصل"
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 25_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/json")
        extra.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (def.id == "slack") {
            // Slack يفضّل POST مع التوكن في الرأس.
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.outputStream.use { }
        }
        val body = conn.inputStreamOrError()
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("رفضت الخدمة التوكن (${conn.responseCode})")
        }
        val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        if (def.id == "telegram") {
            if (!json.optBoolean("ok")) {
                throw IllegalStateException(json.optString("description", "توكن Telegram غير صالح"))
            }
            val user = json.optJSONObject("result") ?: JSONObject()
            return TelegramClient.formatUser(user).ifBlank { "Telegram Bot" }
        }
        if (def.id == "whatsapp") {
            val name = json.optString("verified_name").ifBlank { "WhatsApp Business" }
            val number = json.optString("display_phone_number")
            return if (number.isBlank()) name else "$name — $number"
        }
        if (def.id == "slack") {
            if (!json.optBoolean("ok")) throw IllegalStateException(json.optString("error", "توكن Slack غير صالح"))
            val user = json.optString("user")
            val team = json.optString("team")
            return listOf(team, user).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { "Slack" }
        }
        return json.optString("name").ifBlank { json.optString("type") }.ifBlank { "Notion" }
    }

    private fun saveTokens(prefs: SharedPreferences, id: String, json: JSONObject) {
        val access = json.optString("access_token")
        if (access.isBlank()) throw IllegalStateException("لم تُرجع الخدمة access_token")
        val refresh = json.optString("refresh_token")
        val expiresIn = json.optLong("expires_in", 0L).let { if (it in 1..86_400L) it else 3600L }
        val expires = System.currentTimeMillis() + expiresIn * 1000L
        val editor = prefs.edit().putString(accessKey(id), access).putLong(expiresKey(id), expires)
        // Google لا يعيد refresh_token عند كل تجديد؛ لا تمسح القديم إن لم يصل جديد.
        if (refresh.isNotBlank()) editor.putString(refreshKey(id), refresh)
        editor.apply()
    }

    suspend fun token(prefs: SharedPreferences, id: String): String {
        val def = find(id) ?: throw IllegalArgumentException("موصل غير معروف: $id")
        return if (def.auth == AuthType.GOOGLE) googleToken(prefs, def) else {
            prefs.getString(tokenKey(id), "").orEmpty().trim().ifBlank {
                throw IllegalStateException("الموصل ${def.name} غير مربوط")
            }
        }
    }

    private val refreshLock = Any()

    private fun googleToken(prefs: SharedPreferences, def: ConnectorDef): String {
        val access = prefs.getString(accessKey(def.id), "").orEmpty()
        val expires = prefs.getLong(expiresKey(def.id), 0L)
        if (access.isNotBlank() && System.currentTimeMillis() < expires - 60_000L) return access
        synchronized(refreshLock) {
            // قد يكون طلب آخر جدّد التوكن أثناء انتظار القفل.
            val currentAccess = prefs.getString(accessKey(def.id), "").orEmpty()
            val currentExpires = prefs.getLong(expiresKey(def.id), 0L)
            if (currentAccess.isNotBlank() && System.currentTimeMillis() < currentExpires - 60_000L) {
                return currentAccess
            }
            val refresh = prefs.getString(refreshKey(def.id), "").orEmpty()
            if (refresh.isBlank()) {
                if (currentAccess.isNotBlank()) return currentAccess
                throw IllegalStateException("أعد ربط ${def.name}")
            }
            return refreshGoogle(prefs, def, refresh)
        }
    }

    private fun refreshGoogle(prefs: SharedPreferences, def: ConnectorDef, refresh: String): String {
        val clientId = googleClientId(prefs)
        val clientSecret = googleClientSecret(prefs)
        if (clientId.isBlank()) throw IllegalStateException("إعداد Google OAuth غير مكتمل")
        val fields = mutableListOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to clientId
        )
        if (clientSecret.isNotBlank()) fields.add("client_secret" to clientSecret)
        val json = try {
            postForm(GOOGLE_TOKEN, form(*fields.toTypedArray()))
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (message.contains("invalid_grant", true)) {
                // التوكن أُبطل أو انتهت صلاحيته (شائع في تطبيقات وضع Testing بعد 7 أيام).
                disconnect(prefs, def.id)
                throw IllegalStateException(
                    "انتهت صلاحية ربط ${def.name} أو أُبطل. اضغط «ربط الحساب» من جديد. " +
                        "إن تكرر ذلك أسبوعياً فانشر تطبيق OAuth من Google Cloud (Publishing status: In production)."
                )
            }
            throw e
        }
        saveTokens(prefs, def.id, json)
        return json.optString("access_token")
    }

    private fun googleProfile(prefs: SharedPreferences, def: ConnectorDef): String {
        val token = googleToken(prefs, def)
        val conn = URL(GOOGLE_USERINFO).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        val body = conn.inputStreamOrError()
        if (conn.responseCode !in 200..299) return ""
        val json = JSONObject(body)
        return json.optString("email").ifBlank { json.optString("name") }
    }

    private suspend fun authorizeGoogle(
        context: Context,
        prefs: SharedPreferences,
        def: ConnectorDef,
        clientId: String,
        clientSecret: String
    ): JSONObject {
        val verifier = random(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = random(32)
        val server = withContext(Dispatchers.IO) { bindLoopback() }
        val port = server.localPort
        val redirect = "http://$REDIRECT_HOST:$port"
        prefs.edit().putString(LAST_REDIRECT, redirect).apply()
        val url = buildString {
            append(GOOGLE_AUTH)
            append("?client_id=").append(enc(clientId))
            append("&redirect_uri=").append(enc(redirect))
            append("&response_type=code")
            append("&scope=").append(enc(def.scopes.joinToString(" ")))
            append("&state=").append(enc(state))
            append("&code_challenge=").append(enc(challenge))
            append("&code_challenge_method=S256")
            append("&access_type=offline")
            append("&prompt=").append(enc("consent select_account"))
            append("&include_granted_scopes=true")
        }
        val opened = withContext(Dispatchers.Main) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrElse { false }
        }
        if (!opened) {
            runCatching { server.close() }
            throw IllegalStateException("لا يوجد متصفح لفتح صفحة موافقة Google. ثبّت Chrome أو أي متصفح ثم أعد المحاولة.")
        }
        try {
            val code = withContext(Dispatchers.IO) { awaitCode(server, state) }
            return withContext(Dispatchers.IO) {
                val fields = mutableListOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "client_id" to clientId,
                    "code_verifier" to verifier,
                    "redirect_uri" to redirect
                )
                if (clientSecret.isNotBlank()) fields.add("client_secret" to clientSecret)
                postForm(GOOGLE_TOKEN, form(*fields.toTypedArray()))
            }
        } finally {
            runCatching { server.close() }
        }
    }

    private fun bindLoopback(): ServerSocket {
        var lastError: Exception? = null
        for (port in PREFERRED_PORTS) {
            try {
                return ServerSocket(port, 1, InetAddress.getByName(REDIRECT_HOST)).apply { soTimeout = TIMEOUT_MS }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(
            "تعذّر حجز منفذ محلي للربط (${PREFERRED_PORTS.joinToString("، ")}). " +
                "أغلق أي تطبيق آخر يستخدم المنفذ ثم أعد المحاولة. آخر خطأ: ${lastError?.message.orEmpty()}",
            lastError
        )
    }

    private fun awaitCode(server: ServerSocket, expectedState: String): String {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                break
            }
            socket.use { client ->
                client.soTimeout = 8_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine().orEmpty()
                val target = requestLine.split(' ').getOrNull(1).orEmpty()
                if (target.contains("favicon")) {
                    respond(client, 404, "text/plain; charset=utf-8", "not found")
                    return@use
                }
                val query = target.substringAfter('?', "")
                val params = parseQuery(query)
                val stateBack = params["state"].orEmpty()
                val error = params["error"]
                val code = params["code"]
                if (stateBack.isBlank() && code.isNullOrBlank() && error.isNullOrBlank()) {
                    respond(client, 404, "text/plain; charset=utf-8", "waiting for oauth callback")
                    return@use
                }
                if (stateBack != expectedState) {
                    respond(client, 400, "text/html; charset=utf-8", page("فشل التحقق", "رمز الحماية (state) غير مطابق. أغلق الصفحة وأعد المحاولة."))
                    throw IllegalStateException("state غير مطابق؛ أُلغيت محاولة الربط")
                }
                if (!error.isNullOrBlank()) {
                    val description = params["error_description"].orEmpty()
                    val friendly = oauthError(error, description)
                    respond(client, 400, "text/html; charset=utf-8", page("تعذّر الربط", friendly))
                    throw IllegalStateException(friendly)
                }
                if (!code.isNullOrBlank()) {
                    respond(client, 200, "text/html; charset=utf-8", page("تم ربط Lion AI", "تم الربط بنجاح. يمكنك العودة إلى التطبيق."))
                    return code
                }
                respond(client, 400, "text/html; charset=utf-8", page("طلب غير صالح", "لم يصل رمز التفويض."))
            }
        }
        throw IllegalStateException("انتهت مهلة الربط (3 دقائق). أعد المحاولة.")
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "").trim()
            if (key.isBlank()) null else key to Uri.decode(part.substringAfter('=', ""))
        }.toMap()
    }

    private fun respond(client: java.net.Socket, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Bad Request"}\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().use { out ->
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }
    }

    private fun page(title: String, message: String): String = """
        <!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${escape(title)}</title></head>
        <body style="font-family:system-ui;background:#0d0d0d;color:#eee;display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
        <div style="text-align:center;padding:24px"><div style="font-size:48px">🦁</div>
        <h2 style="color:#ffb800">${escape(title)}</h2><p>${escape(message)}</p></div></body></html>
    """.trimIndent()

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun random(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return base64Url(data)
    }

    private fun base64Url(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    private fun oauthError(error: String, description: String): String {
        val hint = when (error) {
            "access_denied" ->
                "رُفض منح الصلاحية. تأكد أن بريدك مضاف ضمن Test users في شاشة OAuth consent screen، أو اضغط Advanced ثم المتابعة."
            "redirect_uri_mismatch" ->
                "نوع OAuth Client غير مطابق. أنشئ Client من نوع Desktop app (وليس Web application). " +
                    "إن أردت استخدام Web application فأضف Redirect URI التالي حرفياً: $defaultRedirectUri"
            "invalid_client" ->
                "Client ID أو Client Secret غير صحيح. أعد نسخهما من Google Cloud Console ← Credentials."
            "invalid_scope" ->
                "صلاحية غير مفعّلة. فعّل Drive API وGmail API وCalendar API من API Library، " +
                    "وأضف الصلاحيات نفسها في OAuth consent screen ← Data access."
            "invalid_request" ->
                "طلب Google غير صالح. احذف المسافات الزائدة من Client ID/Secret وأعد المحاولة."
            "invalid_grant" ->
                "انتهت صلاحية رمز التفويض. أعد الربط من جديد."
            "temporarily_unavailable", "server_error" ->
                "خدمة Google غير متاحة مؤقتاً. انتظر دقيقة وأعد المحاولة."
            else -> "رفضت Google الربط ($error)."
        }
        val detail = description.trim()
        return if (detail.isBlank()) hint else "$hint\nتفاصيل Google: $detail"
    }

    private fun postForm(url: String, body: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "application/json")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val text = conn.inputStreamOrError()
        if (conn.responseCode !in 200..299) {
            val detail = runCatching {
                val json = JSONObject(text)
                val code = json.optString("error").ifBlank { json.optString("error_code") }
                val desc = json.optString("error_description").ifBlank {
                    json.optJSONObject("error")?.optString("message").orEmpty()
                }
                if (code.isNotBlank()) "$code${if (desc.isNotBlank()) ": $desc" else ""}" else desc
            }.getOrDefault("")
            val friendly = runCatching {
                val json = JSONObject(text)
                oauthError(json.optString("error"), json.optString("error_description"))
            }.getOrDefault("")
            throw IllegalStateException(
                friendly.ifBlank {
                    "فشل تبادل الرمز (${conn.responseCode}): ${detail.ifBlank { text.take(300) }}"
                }
            )
        }
        val trimmed = text.trim()
        if (trimmed.isBlank()) return JSONObject()
        return runCatching { JSONObject(trimmed) }.getOrElse {
            throw IllegalStateException("رد غير صالح من Google: ${trimmed.take(200)}")
        }
    }

    private fun HttpURLConnection.inputStreamOrError(): String = try {
        (if (responseCode in 200..299) inputStream else errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    } catch (e: Exception) {
        ""
    }
}
