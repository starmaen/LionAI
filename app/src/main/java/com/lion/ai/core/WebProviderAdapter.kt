package com.lion.ai.core
import android.content.Context
data class WebProvider(val id: String, val name: String, val url: String, val icon: String = "🌐")
object WebProviderAdapter {
    val providers: List<WebProvider> = listOf(
        WebProvider("arena", "Arena AI", "https://arena.ai/text/direct", "🏟️"),
        WebProvider("claude_web", "Claude Web", "https://claude.ai", "🧠"),
        WebProvider("deepseek", "DeepSeek", "https://chat.deepseek.com", "🔮"),
        WebProvider("chatgpt_web", "ChatGPT Web", "https://chat.openai.com", "💬"),
        WebProvider("gemini_web", "Gemini Web", "https://gemini.google.com", "✨"),
        WebProvider("perplexity", "Perplexity", "https://www.perplexity.ai", "🔍"),
        WebProvider("poe", "Poe", "https://poe.com", "🤖"),
        WebProvider("you", "You.com", "https://you.com", "🟢")
    )
    val allWebProviders: List<WebProvider> = providers
}
