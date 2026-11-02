package com.lion.ai.core
import com.lion.ai.Route
import com.lion.ai.Channel

data class WebProvider(val id: String, val displayName: String, val url: String, val icon: String = "🦁")

class WebProviderAdapter {
    val webProviders = listOf(
        WebProvider("arena", "Arena", "https://arena.ai/text/direct", "🏟"),
        WebProvider("claude-web", "Claude Web", "https://claude.ai", "🧠"),
        WebProvider("deepseek-chat", "DeepSeek Chat", "https://chat.deepseek.com", "🐋"),
        WebProvider("chatgpt-web", "ChatGPT Web", "https://chat.openai.com", "🤖"),
        WebProvider("gemini-web", "Gemini Web", "https://gemini.google.com", "✨"),
        WebProvider("qwen-web", "Qwen Web", "https://chat.qwen.ai", "🌙"),
        WebProvider("kimi-web", "Kimi Web", "https://www.kimi.com", "🌊"),
        WebProvider("perplexity-web", "Perplexity", "https://www.perplexity.ai", "🔍"),
        WebProvider("poe-web", "Poe", "https://poe.com", "🎭"),
        WebProvider("huggingface-web", "HuggingFace", "https://huggingface.co/chat", "🤗")
    )

    fun toRoutes(prefs: WebProvidersPrefs): List<Route> {
        return prefs.getEnabled(webProviders).map { web: WebProvider ->
            Route(provider = "lion-chat-${web.id}", model = "lion-chat-${web.id}", via = Channel.AUTO)
        }
    }

    fun getLionChatName(): String = "🦁 Lion Chat"
}
