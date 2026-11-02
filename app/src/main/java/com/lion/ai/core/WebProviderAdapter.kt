package com.lion.ai.core
import android.webkit.WebView

data class WebProvider(val id: String, val displayName: String, val url: String)

class WebProviderAdapter(private val webView: WebView) {
    val webProviders = listOf(
        WebProvider("arena", "Arena", "https://arena.ai/text/direct"),
        WebProvider("claude-web", "Claude Web", "https://claude.ai"),
        WebProvider("deepseek-chat", "DeepSeek Chat", "https://chat.deepseek.com"),
        WebProvider("chatgpt-web", "ChatGPT", "https://chat.openai.com"),
        WebProvider("gemini-web", "Gemini Web", "https://gemini.google.com"),
        WebProvider("qwen-web", "Qwen", "https://chat.qwen.ai"),
        WebProvider("kimi-web", "Kimi", "https://www.kimi.com"),
        WebProvider("perplexity-web", "Perplexity", "https://www.perplexity.ai"),
        WebProvider("poe-web", "Poe", "https://poe.com"),
        WebProvider("huggingface-web", "HuggingFace", "https://huggingface.co/chat")
    )
    
    fun toLionAIProvider(web: WebProvider): Map<String,String> {
        return mapOf("displayName" to "🦁 LionAI", "original" to web.displayName)
    }
}
