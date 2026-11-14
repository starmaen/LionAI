package com.lion.ai

import android.content.SharedPreferences
import java.net.URI
import org.json.JSONObject

/** DOM adapters are NOT API mappings. Selectors are version-sensitive and never imply a verified site. */
data class WebSiteAdapter(
    val id: String, val url: String, val hosts: Set<String>,
    val input: String, val send: String, val answer: String,
    val stop: String, val complete: String,
    /** false = never guess the reply from page-text diffs (it would capture reasoning/thinking text). */
    val fallback: Boolean = true,
    /** true = the reply may be split across several answer nodes: join all new ones in page order. */
    val joinAnswers: Boolean = false,
    /** How long the reply text must stay unchanged before it counts as finished (0 = default 2s). */
    val settleMs: Int = 0,
    /** Selector of the site's own error message (shown instead of a reply); empty = none known. */
    val error: String = "",
    /** مداخل بديلة لنفس الخدمة تُجرَّب آلياً عند فشل تحميل المدخل الأساسي (رفض/تحويل). */
    val altUrls: List<String> = emptyList()
) {
    fun accepts(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host?.lowercase() in hosts && uri.userInfo == null
    }.getOrDefault(false)

    fun entries(): List<String> = (listOf(url) + altUrls).distinct()

    fun json(): JSONObject = JSONObject().put("id", id).put("hosts", org.json.JSONArray(hosts.toList()))
        .put("input", input).put("send", send).put("answer", answer)
        .put("stop", stop).put("complete", complete).put("fallback", fallback).put("joinAnswers", joinAnswers).put("settleMs", settleMs).put("error", error)
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
        val complete: String = "",
        val fallback: Boolean = true,
        val joinAnswers: Boolean = false,
        val settleMs: Int = 0,
        /** true = use only this site's answer selector, not the generic assistant-role ones. */
        val exclusiveAnswer: Boolean = false,
        val error: String = "",
        val altUrls: List<String> = emptyList()
    )

    /** Site extras are UNIONED with the generic selectors so a narrow site rule cannot disable the shared bridge. */
    private val SPECS = mapOf(
        "arena" to Spec(
            url = "https://arena.ai/text/direct",
            hosts = setOf("arena.ai"),
            input = "textarea[name='message'], [data-testid*='prompt'], [data-testid*='composer']",
            // Verified on a real page report: the final answer is div.prose OUTSIDE the .not-prose
            // thinking widget and OUTSIDE the user's bubble (max-w-prose). Never match those.
            answer = "div.prose:not(.not-prose *):not([class*='max-w-prose'] *):not(.text-interactive-negative)",
            // Real page report: Arena shows its own failures as div.prose.text-interactive-negative.
            error = "div.prose.text-interactive-negative",
            fallback = false
        ),
        "duckduckgo_web" to Spec(
            url = "https://duck.ai",
            hosts = setOf("duck.ai", "duckduckgo.com"),
            input = "[name='user-prompt'], textarea[name='prompt']",
            answer = "[class*='assistantMessage'], [data-testid='assistant-message']"
        ),
        "gemini_web" to Spec(
            url = "https://gemini.google.com/",
            hosts = setOf("gemini.google.com"),
            input = "rich-textarea .ql-editor[contenteditable='true'], rich-textarea [contenteditable='true'], .ql-editor[contenteditable='true'], div[role='textbox'][contenteditable='true'], textarea",
            send = "button.send-button, button[aria-label='Send message'], button[aria-label*='Send'], button[mattooltip*='Send'], button:has(mat-icon[fonticon='send'])",
            // الجواب فقط: لا رسالة المستخدم (user-query) ولا مسودات الرد.
            answer = "model-response message-content .markdown, model-response .model-response-text, model-response message-content, [id^='model-response-message-content'], .response-container .markdown",
            stop = "button.stop-button, button[aria-label='Stop response'], button[aria-label*='Stop'], button:has(mat-icon[fonticon='stop'])",
            settleMs = 3000,
            error = "error-message, .error-message, [data-test-id='error-message']"
        ),
        "copilot_web" to Spec(
            url = "https://copilot.com/",
            // تقرير المستخدم: copilot.microsoft.com يحوّل الآن إلى copilot.com (نطاق مايكروسوفت نفسه).
            // مداخل بديلة تُجرَّب آلياً عند رفض المدخل الأساسي (403): سطح المستأجر السحابي، ثم دردشة Bing.
            hosts = setOf("copilot.microsoft.com", "copilot.cloud.microsoft", "copilot.com", "www.copilot.com", "bing.com", "www.bing.com"),
            input = "#userInput, textarea[data-testid='composer-input'], textarea[aria-label*='message' i], textarea[placeholder*='Copilot' i], textarea[placeholder*='Message' i], [contenteditable='true'][role='textbox'], " +
                "cib-serp main[part='chat-input'] textarea, #searchboxroot textarea, #sb_form_q",
            send = "button[data-testid='submit-button'], button[aria-label='Submit message'], button[aria-label*='Submit' i], button[title*='Submit' i], " +
                "button[data-testid='send-button'], button[aria-label*='Submit' i], button[aria-label*='اسأل' i]",
            // رد Copilot فقط (لا رسالة المستخدم data-content='user-message').
            answer = "[data-content='ai-message'], [data-testid='ai-message'], div[data-testid='message-item'][data-author='ai'], [data-testid='copilot-response'], [class*='ai-message'], " +
                "cib-message[type='response'], .ac-textBlock, [class*='ac-textBlock']",
            stop = "button[data-testid='stop-button'], button[aria-label*='Stop' i], button[title*='Stop' i]",
            settleMs = 3500,
            error = "[data-testid='error-message'], [role='alert']",
            altUrls = listOf("https://copilot.cloud.microsoft", "https://www.bing.com/chat")
        ),
        "ptero_web" to Spec(
            url = "https://ptero.pro/",
            hosts = setOf("ptero.pro", "www.ptero.pro"),
            // غير مُتحقق منه على صفحة حقيقية: محددات عامة + التقاط احتياطي بنص الصفحة.
            // استخدم «تقرير البنية» من إعدادات Lion Chat لضبطها إن لزم.
            input = "textarea, [contenteditable='true']",
            answer = "[class*='assistant'], [class*='bot-message'], [class*='ai-message'], [class*='message'][class*='ai'], .markdown, .markdown-body",
            settleMs = 3000
        ),
        "lumo_web" to Spec(
            url = "https://lumo.proton.me/guest",
            hosts = setOf("lumo.proton.me"),
            // غير مُتحقق منه على صفحة حقيقية (تطبيق JS): محددات عامة + التقاط احتياطي.
            input = "[contenteditable='true'][role='textbox'], div[contenteditable='true'], textarea",
            answer = "[class*='lumo-markdown'], [class*='assistant-msg'], [class*='assistant'], [data-testid*='assistant'], [class*='markdown']",
            settleMs = 3500
        ),
        "github_copilot_web" to Spec(
            hosts = setOf("github.com", "copilot.github.com"),
            input = "#copilot-chat-textarea, textarea[name='copilot-chat']",
            // Verified on a real page report: replies are div.markdown-body blocks (a reply can span several).
            answer = "div.markdown-body.copilot-chat-compact-tables, [data-testid='copilot-chat-message-assistant']",
            stop = "button:has(svg.octicon-square-fill), button:has(svg.octicon-stop)",
            fallback = false,
            joinAnswers = true,
            settleMs = 6000
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
            // Real page report: the send control is a div[role=button] (circle, filled), not a <button>.
            send = "div.ds-button--primary.ds-button--filled.ds-button--circle[role='button']",
            // الجواب النهائي فقط: لا كتلة التفكير ولا رسالة المستخدم ولا ما سبقها من الحوار (تقرير المستخدم أظهر
            // التقاط التفكير + الدور السابق + رسالته). بلا التقاط احتياطي بنص الصفحة الذي يخلط الأدوار.
            answer = "div.ds-markdown:not([class*='think'] *):not([class*='think'])",
            exclusiveAnswer = true,
            fallback = false,
            settleMs = 4000
        ),
        "chatgpt_web" to Spec(
            hosts = setOf("chatgpt.com", "chat.openai.com"),
            input = "#prompt-textarea, div#prompt-textarea, [data-placeholder*='Message']",
            answer = "[data-message-author-role='assistant'] .markdown"
        ),
        "mistral_web" to Spec(
            hosts = setOf("chat.mistral.ai", "mistral.ai"),
            input = "div.ProseMirror[contenteditable='true'], textarea, [contenteditable='true'][role='textbox']",
            // Real page report: assistant turns are [data-message-author-role=assistant]; the reply text is
            // .markdown-container-style inside it. The whole turn also holds reasoning/tool chrome, so
            // match only the text container and never fall back to page-text diffs.
            answer = "[data-message-author-role='assistant'] .markdown-container-style",
            exclusiveAnswer = true,
            fallback = false,
            settleMs = 3000
        ),
        "qwen_web" to Spec(
            hosts = setOf("chat.qwen.ai", "qwen.ai"),
            input = "textarea.message-input-textarea, textarea, [contenteditable='true']",
            send = "button.send-button",
            // Unverified (the report had no replies yet): common Qwen reply containers, plus the old guesses.
            answer = ".qwen-markdown, .response-message-content, .chat-response-message, .chat-assistant-message, [class*='assistant']",
            settleMs = 3000
        ),
        "perplexity_web" to Spec(
            hosts = setOf("www.perplexity.ai", "perplexity.ai"),
            // الحقل في Perplexity محرر contenteditable (#ask-input) لا textarea: نقدّمه ونُبقي textarea احتياطاً.
            // غير مُتحقق منه على صفحة حقيقية؛ استخدم «تقرير البنية» إن لزم.
            input = "#ask-input, div[contenteditable='true'][role='textbox'], textarea[placeholder]",
            send = "button[data-testid='submit-button'], button[aria-label='Submit'], button[aria-label*='Submit' i]",
            answer = "div[id^='markdown-content-'], [data-testid='answer'], [data-testid='answer-text']",
            stop = "button[aria-label='Stop'], button[aria-label*='Stop' i]",
            settleMs = 3000
        ),
        "grok_web" to Spec(
            hosts = setOf("grok.com", "grok.x.ai"),
            input = "textarea, [contenteditable='true'][role='textbox']",
            answer = ".response-content-markdown, [class*='response']"
        ),
        "poe_web" to Spec(
            hosts = setOf("poe.com"),
            input = "textarea[class*='GrowingTextArea'], [class*='ChatMessageInputContents'] textarea, textarea",
            answer = "[class*='Message_botMessageBubble'], [class*='botMessage']"
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
        "you_web" to Spec(
            url = "https://you.com/?chatMode=default",
            hosts = setOf("you.com"),
            input = "#search-input-textarea, textarea",
            answer = "[data-testid='youchat-answer']"
        ),
        "aistudio_web" to Spec(
            hosts = setOf("aistudio.google.com", "makersuite.google.com"),
            input = "ms-autosize-textarea textarea, textarea",
            answer = "ms-chat-turn[data-role='model'], ms-model-turn, .model-response"
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
            if (spec.exclusiveAnswer) spec.answer else union(spec.answer, ANSWER),
            union(spec.stop, STOP),
            union(spec.complete, COMPLETE),
            spec.fallback, spec.joinAnswers, spec.settleMs, spec.error, spec.altUrls
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
