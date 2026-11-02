package com.lion.ai.web
import android.webkit.JavascriptInterface
import android.webkit.WebView

class LionAIBridge(private val onResponse: (String) -> Unit) {
    @JavascriptInterface
    fun onWebResponse(text: String) { onResponse(text) }
}

fun setupWebViewWithBrain(webView: WebView, bridge: LionAIBridge) {
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true
    webView.addJavascriptInterface(bridge, "Android")
}
