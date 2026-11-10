package com.lion.ai.core
import android.content.Context
data class WebProvider(val id: String, val name: String, val url: String, val icon: String = "🌐")
object WebProviderAdapter {
    val providers: List<WebProvider> = listOf(
        WebProvider("arena", "Arena AI", "https://arena.ai/text/direct", "🏟️"),
        WebProvider("claude_web", "Claude Web", "https://claude.ai", "🧠"),
        WebProvider("deepseek", "DeepSeek", "https://chat.deepseek.com", "🔮"),
        WebProvider("chatgpt_web", "ChatGPT Web", "https://chat.openai.com", "💬"),
        WebProvider("gemini_web", "Gemini Web", "https://gemini.google.com/app", "✨"),
        WebProvider("perplexity", "Perplexity", "https://www.perplexity.ai", "🔍"),
        WebProvider("poe", "Poe", "https://poe.com", "🤖"),
        WebProvider("you", "You.com", "https://you.com", "🟢"),
        WebProvider("duckduckgo", "DuckDuckGo AI", "https://duckduckgo.com/?q=&ia=chat", "🦆"),
        WebProvider("copilot_web", "Copilot", "https://copilot.microsoft.com", "🪁"),
        WebProvider("mistral", "Mistral", "https://chat.mistral.ai", "🌬️"),
        WebProvider("qwen", "Qwen", "https://chat.qwen.ai", "🐼"),
        WebProvider("grok", "Grok", "https://grok.com", "❌"),
        WebProvider("huggingface", "HuggingChat", "https://huggingface.co/chat", "🤗"),
        WebProvider("kimi", "Kimi", "https://www.kimi.com", "🌙"),
        WebProvider("blackbox", "BlackBox AI", "https://www.blackbox.ai", "📦"),
        WebProvider("phind", "Phind", "https://www.phind.com", "🎯"),
        WebProvider("pi", "Pi AI", "https://pi.ai", "🥧"),
        WebProvider("character", "Character.AI", "https://character.ai", "🎭"),
        WebProvider("meta_ai", "Meta AI", "https://www.meta.ai", "Ⓜ️"),
        WebProvider("bing", "Bing Chat", "https://www.bing.com/chat", "🅱️"),
        WebProvider("lmsys", "LMSYS Arena", "https://chat.lmsys.org", "🧪")
    )
    val allWebProviders: List<WebProvider> = providers
    fun findById(id: String): WebProvider? = providers.find { it.id == id }
    fun getEnabledProviders(context: Context): List<WebProvider> = providers.filter { WebProvidersPrefs.isProviderEnabled(context, it.id) }
}
