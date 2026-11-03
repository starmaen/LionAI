package com.lion.ai.bridge

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class WebViewClientImpl : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        view.evaluateJavascript("""
            console.log('WebView loaded successfully');
            if(typeof AIBridge !== 'undefined') {
                console.log('AIBridge is ready');
            }
        """.trimIndent(), null)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return false
    }
}
