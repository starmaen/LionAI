package com.lion.ai.bridge

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

/** Minimal, non-privileged bridge surface for WebViews.
 *
 * This bridge deliberately does not expose app credentials or execute page messages as
 * model requests. A site-specific, supported integration must be implemented separately.
 */
class AIBridge(@Suppress("unused") context: Context) {

    @JavascriptInterface
    fun callAPI(method: String, params: String): String = try {
        when (method) {
            "getSettings" -> getSettings()
            "sendMessage" -> handleMessage(params)
            "checkConnection" -> JSONObject()
                .put("status", "connected")
                .put("message", "Bridge active; chat forwarding is not configured")
                .toString()
            else -> JSONObject().put("error", "Method not found").toString()
        }
    } catch (e: Exception) {
        Log.e("AIBridge", "Bridge call failed", e)
        JSONObject().put("error", e.message ?: "Bridge call failed").toString()
    }

    @JavascriptInterface
    fun getSettings(): String = JSONObject()
        .put("status", "ok")
        .put("provider", "")
        .put("model", "")
        .toString()

    @JavascriptInterface
    fun handleMessage(message: String): String {
        if (message.isBlank()) return JSONObject().put("error", "Message is empty").toString()
        // Avoid logging full user prompts or page content.
        Log.d("AIBridge", "Web message received (${message.length.coerceAtMost(1_000)} chars)")
        return JSONObject()
            .put("error", "This WebView is not connected to the app chat")
            .toString()
    }

    @JavascriptInterface
    fun log(message: String) {
        Log.i("WebView", message.take(500))
    }
}
