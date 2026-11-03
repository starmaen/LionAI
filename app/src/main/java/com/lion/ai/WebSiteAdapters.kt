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
    private const val INPUT = "textarea, [contenteditable='true'][role='textbox'], [contenteditable='true'].ProseMirror, [contenteditable='true']"
    private const val SEND = "button[data-testid='send-button'], button[data-testid*='send'], button.send-button, button[aria-label='Send'], button[aria-label='Send message'], button[aria-label='Send Message'], button[aria-label*='Send'], button[aria-label*='إرسال'], button[type='submit'], [role='button'][aria-label*='Send'], [role='button'][aria-label*='إرسال']"
    private const val STOP = "button[data-testid='stop-button'], button.stop-button, button[aria-label='Stop'], button[aria-label='Stop generating'], button[aria-label='Stop response'], button[aria-label='Stop generation'], button[aria-label*='Stop'], button[aria-label*='إيقاف']"
    private const val COMPLETE = "button[aria-label*='Copy'], button[aria-label*='نسخ'], button[data-testid*='copy'], button[data-test-id*='copy'], button[title*='Copy'], button[title*='نسخ'], [data-state='complete']"
    private const val ANSWER = "[data-message-role='assistant'], [data-message-author-role='assistant'], [data-role='assistant'], [data-testid='assistant-message'], [data-testid*='assistant-message']"

    private data class Spec(
        val url: String? = null,
        val hosts: Set<String> = emptySet(),
        val input: String = "",
        val send: String = "",
        val answer: String = "",
        val stop: String = "",
        val complete: String = ""
    )

    /** Site extras are UNIONED with the generic selectors so a narrow site rule cannot disable the shared bridge. */
    private val SPECS = mapOf(
        "arena" to Spec(
            url = "https://arena.ai/text/direct",
            hosts = setOf("arena.ai"),
            input = "[data-testid*='prompt'], [data-testid*='composer']",
            answer = "[class*='markdown']"
        ),
        "duckduckgo_web" to Spec(
            url = "https://duck.ai",
            hosts = setOf("duck.ai", "duckduckgo.com"),
            input = "[name='user-prompt'], textarea[name='prompt']",
            answer = "[class*='assistantMessage'], [data-testid='assistant-message']"
        ),
        "gemini_web" to Spec(
            hosts = setOf("gemini.google.com"),
            input = "rich-textarea [contenteditable='true'], rich-textarea .ql-editor, .ql-editor[contenteditable='true']",
            send = "button.send-button",
            answer = "model-response message-content, model-response .markdown, message-content .markdown",
            stop = "button.stop-button"
        ),
        "copilot_web" to Spec(
            hosts = setOf("copilot.microsoft.com", "copilot.cloud.microsoft"),
            input = "#userInput, textarea[aria-label*='message'], textarea[aria-label*='Message']",
            answer = "[data-content='ai-message'], [data-testid='copilot-response']"
        ),
        "github_copilot_web" to Spec(
            hosts = setOf("github.com", "copilot.github.com"),
            input = "#copilot-chat-textarea, textarea[name='copilot-chat']",
            answer = "[data-testid='copilot-chat-message-assistant']"
        ),
        "claude_web" to Spec(
            url = "https://claude.ai/new",
            hosts = setOf("claude.ai"),
            input = ".ProseMirror[contenteditable='true'], [contenteditable='true'][role='textbox']",
            send = "button[aria-label='Send message'], button[aria-label='Send Message']",
            answer = "[data-is-streaming] .font-claude-response, .font-claude-response"
        ),
        "deepseek_web" to Spec(
            hosts = setOf("chat.deepseek.com", "deepseek.com"),
            input = "textarea#chat-input, textarea[placeholder], .ds-scroll-area textarea",
            answer = "div.ds-markdown, .ds-markdown"
        ),
        "chatgpt_web" to Spec(
            hosts = setOf("chatgpt.com", "chat.openai.com"),
            input = "#prompt-textarea, div#prompt-textarea, [data-placeholder*='Message']",
            answer = "[data-message-author-role='assistant'] .markdown"
        ),
        "mistral_web" to Spec(
            hosts = setOf("chat.mistral.ai", "mistral.ai"),
            input = "textarea, [contenteditable='true'][role='textbox']",
            answer = "[data-message-role='assistant']"
        ),
        "qwen_web" to Spec(
            hosts = setOf("chat.qwen.ai", "qwen.ai"),
            input = "textarea, [contenteditable='true']",
            answer = ".chat-assistant-message, [class*='assistant']"
        ),
        "perplexity_web" to Spec(
            hosts = setOf("www.perplexity.ai", "perplexity.ai"),
            input = "textarea[placeholder], #ask-input, textarea",
            answer = "[data-testid='answer'], [data-testid='answer-text'], [class*='prose']"
        ),
        "grok_web" to Spec(
            hosts = setOf("grok.com", "grok.x.ai"),
            input = "textarea, [contenteditable='true'][role='textbox']",
            answer = ".response-content-markdown, [class*='response']"
        ),
        "hf_chat_web" to Spec(
            hosts = setOf("huggingface.co", "hf.co"),
            input = "textarea, [contenteditable='true']",
            answer = "[data-message-role='assistant']"
        ),
        "poe_web" to Spec(
            hosts = setOf("poe.com"),
            input = "textarea[class*='GrowingTextArea'], [class*='ChatMessageInputContents'] textarea, textarea",
            answer = "[class*='Message_botMessageBubble'], [class*='botMessage']"
        ),
        "kimi_web" to Spec(
            hosts = setOf("www.kimi.com", "kimi.com", "kimi.moonshot.cn", "kimi.moonshot.ai"),
            input = "textarea, .chat-input textarea, [contenteditable='true']",
            answer = ".segment-assistant .markdown, .segment-assistant"
        ),
        "meta_web" to Spec(
            hosts = setOf("www.meta.ai", "meta.ai"),
            input = "[contenteditable='true'][role='textbox'], textarea",
            answer = "[data-testid='assistant-message']"
        ),
        "zai_web" to Spec(
            hosts = setOf("chat.z.ai", "z.ai"),
            input = "textarea, [contenteditable='true']",
            answer = ".chat-assistant-message"
        ),
        "pi_web" to Spec(
            url = "https://pi.ai/talk",
            hosts = setOf("pi.ai"),
            input = ".send-input, textarea, [contenteditable='true']",
            answer = "[data-testid='assistant-message']"
        ),
        "you_web" to Spec(
            hosts = setOf("you.com"),
            input = "#search-input-textarea, textarea",
            answer = "[data-testid='youchat-answer']"
        ),
        "phind_web" to Spec(
            hosts = setOf("www.phind.com", "phind.com"),
            input = "textarea, [contenteditable='true']",
            answer = "[data-testid='assistant-message']"
        ),
        "aistudio_web" to Spec(
            hosts = setOf("aistudio.google.com", "makersuite.google.com"),
            input = "ms-autosize-textarea textarea, textarea",
            answer = "ms-chat-turn[data-role='model'], ms-model-turn, .model-response"
        ),
        "blackbox_web" to Spec(
            hosts = setOf("www.blackbox.ai", "blackbox.ai"),
            input = "textarea, [contenteditable='true']",
            answer = "[data-message-role='assistant']"
        )
    )

    private fun union(vararg parts: String): String =
        parts.asSequence().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")

    private fun hostsOf(url: String, extra: Set<String>): Set<String> {
        val host = URI(url).host.lowercase()
        val bare = host.removePrefix("www.")
        return extra + host + bare + "www.$bare"
    }

    fun all(): List<WebSiteAdapter> = PROVIDERS.filter { it.group == ProviderGroup.WEB }.map { info ->
        val spec = SPECS[info.id] ?: Spec()
        val url = spec.url ?: requireNotNull(info.webviewUrl)
        WebSiteAdapter(
            info.id, url, hostsOf(url, spec.hosts),
            union(spec.input, INPUT),
            union(spec.send, SEND),
            union(spec.answer, ANSWER),
            union(spec.stop, STOP),
            union(spec.complete, COMPLETE)
        )
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
