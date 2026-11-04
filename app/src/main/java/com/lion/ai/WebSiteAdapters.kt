package com.lion.ai

import android.content.SharedPreferences
import java.net.URI
import org.json.JSONObject

/** DOM adapters are NOT API mappings. Selectors are version-sensitive and never imply a verified site. */
data class WebSiteAdapter(
    val id: String, val url: String, val hosts: Set<String>,
    val input: String, val send: String, val answer: String,
    val stop: String, val complete: String
) {
    fun accepts(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host?.lowercase() in hosts && uri.userInfo == null
    }.getOrDefault(false)

    fun json(): JSONObject = JSONObject().put("id", id).put("hosts", org.json.JSONArray(hosts.toList()))
        .put("input", input).put("send", send).put("answer", answer)
        .put("stop", stop).put("complete", complete)
}

object WebSiteAdapters {
    private const val INPUT = "textarea, [contenteditable='true'][role='textbox'], [contenteditable='true'].ProseMirror"
    private const val SEND = "button[data-testid='send-button'], button[aria-label='Send'], button[aria-label='Send message'], button[aria-label='Submit'], button[type='submit']"
    private const val STOP = "button[data-testid='stop-button'], button[aria-label='Stop'], button[aria-label='Stop generating'], button[aria-label='Stop response'], button[aria-label='Stop generation']"
    private const val COMPLETE = "button[aria-label*='Copy'], button[data-testid*='copy'], button[title*='Copy'], [data-state='complete']"

    // Kept explicit: no extracting document.body or treating arbitrary page text as an answer.
    private val answerSelectors = mapOf(
        "arena" to "[data-message-role='assistant'], [data-role='assistant'], [data-testid='assistant-message']",
        "duckduckgo_web" to "[data-role='assistant'], [data-testid='assistant-message'], [class*='assistantMessage']",
        "gemini_web" to "model-response message-content, model-response .markdown",
        "copilot_web" to "[data-content='ai-message'], [data-testid='assistant-message']",
        "github_copilot_web" to "[data-role='assistant'], [data-testid='copilot-chat-message-assistant']",
        "claude_web" to "[data-is-streaming] .font-claude-response, .font-claude-response",
        "deepseek_web" to "div.ds-markdown",
        "chatgpt_web" to "[data-message-author-role='assistant']",
        "mistral_web" to "[data-message-role='assistant'], [data-role='assistant']",
        "qwen_web" to "[data-role='assistant'], .chat-assistant-message",
        "perplexity_web" to "[data-testid='answer'], [data-testid='answer-text']",
        "grok_web" to "[data-role='assistant'], .response-content-markdown",
        "hf_chat_web" to "[data-message-role='assistant'], [data-role='assistant']",
        "poe_web" to "[class*='Message_botMessageBubble']",
        "kimi_web" to ".segment-assistant .markdown, [data-role='assistant']",
        "meta_web" to "[data-testid='assistant-message'], [data-role='assistant']",
        "zai_web" to ".chat-assistant-message, [data-role='assistant']",
        "pi_web" to "[data-role='assistant'], [data-testid='assistant-message']",
        "you_web" to "[data-testid='youchat-answer'], [data-role='assistant']",
        "phind_web" to "[data-role='assistant'], [data-testid='assistant-message']",
        "aistudio_web" to "ms-chat-turn[data-role='model'], ms-model-turn",
        "blackbox_web" to "[data-message-role='assistant'], [data-role='assistant']"
    )

    fun all(): List<WebSiteAdapter> = PROVIDERS.filter { it.group == ProviderGroup.WEB }.map { info ->
        // Never use the old Arena submitPrompt URL: opening a session must not send a message.
        val url = if (info.id == "arena") "https://arena.ai/text/direct" else requireNotNull(info.webviewUrl)
        val host = URI(url).host.lowercase()
        val hosts = when (info.id) {
            "arena" -> setOf("arena.ai", "www.arena.ai")
            "chatgpt_web" -> setOf("chatgpt.com", "chat.openai.com")
            else -> setOf(host)
        }
        WebSiteAdapter(info.id, url, hosts,
            when (info.id) {
                "chatgpt_web" -> "#prompt-textarea"
                "claude_web" -> "[contenteditable='true'][role='textbox'], .ProseMirror[contenteditable='true']"
                "gemini_web" -> "rich-textarea [contenteditable='true']"
                else -> INPUT
            },
            when (info.id) {
                "claude_web" -> "button[aria-label='Send message'], button[aria-label='Send Message']"
                "gemini_web" -> "button.send-button, button[aria-label='Send message']"
                else -> SEND
            }, answerSelectors.getValue(info.id),
            if (info.id == "gemini_web") "button.stop-button, $STOP" else STOP, COMPLETE)
    }
    fun get(id: String, prefs: SharedPreferences? = null): WebSiteAdapter? {
        val base = all().firstOrNull { it.id == id } ?: return null
        fun setting(key: String, default: String): String = prefs?.getString("web_dom_${id}_$key", "")
            ?.trim()?.takeIf { it.isNotEmpty() } ?: default
        return base.copy(input = setting("input", base.input), send = setting("send", base.send),
            answer = setting("answer", base.answer), stop = setting("stop", base.stop),
            complete = setting("complete", base.complete))
    }
}
