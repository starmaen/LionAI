package com.lion.ai.bridge

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

class AIBridge(val context: Context) {
    
    @JavascriptInterface
    fun callAPI(method: String, params: String): String {
        return try {
            when(method) {
                "getSettings" -> getSettings()
                "sendMessage" -> handleMessage(params)
                "checkConnection" -> """{"status":"connected","message":"Bridge active"}"""
                else -> """{"error":"Method not found"}"""
            }
        } catch (e: Exception) {
            Log.e("AIBridge", "Error: ${e.message}")
            """{"error":"${e.message}"}"""
        }
    }

    @JavascriptInterface
    fun getSettings(): String {
        val prefs = context.getSharedPreferences("lion_settings", Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("apiKey", prefs.getString("api_key", ""))
            put("model", prefs.getString("model", ""))
            put("provider", prefs.getString("provider", ""))
            put("status", "ok")
        }.toString()
    }

    @JavascriptInterface
    fun handleMessage(message: String) {
        Log.d("AIBridge", "Message from web: $message")
    }

    @JavascriptInterface
    fun log(message: String) {
        Log.i("WebView", message)
    }
}
