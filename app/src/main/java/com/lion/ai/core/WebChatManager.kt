package com.lion.ai.core
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object WebChatManager {
    suspend fun askWebProvider(context: Context, providerId: String, prompt: String): String {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                try {
                    val provider = WebProviderAdapter.findById(providerId) ?: WebProviderAdapter.providers.first()
                    val webView = WebView(context)
                    val bridge = LionAIBridge(
                        context = context,
                        providerId = provider.id,
                        onResponse = { txt ->
                            if (cont.isActive) {
                                cont.resume(txt)
                                Handler(Looper.getMainLooper()).postDelayed({ try { webView.destroy() } catch(_:Exception){} }, 1000)
                            }
                        },
                        onError = { err ->
                            if (cont.isActive) cont.resume("خطأ ${provider.name}: $err")
                        }
                    )
                    LionAIBridge.setupWebViewWithBrain(webView, provider, bridge)
                    Handler(Looper.getMainLooper()).postDelayed({
                        LionAIBridge.sendPrompt(webView, prompt)
                    }, 5000)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (cont.isActive) cont.resume("⏰ انتهى الوقت بانتظار ${provider.name}")
                    }, 45000)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume("خطأ: ${e.message}")
                }
            }
        }
    }
}
