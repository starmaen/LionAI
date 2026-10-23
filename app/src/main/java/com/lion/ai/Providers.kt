package com.lion.ai

data class ProviderInfo(
    val id: String,
    val label: String,
    val tagline: String,
    val docsUrl: String,
    val defaultModel: String,
    val free: Boolean = true,
    val needsAccountId: Boolean = false,
    val shortName: String = label
)

val PROVIDERS = listOf(
    ProviderInfo(
        id = "gemini",
        label = "Google Gemini",
        tagline = "Free Tier — gemini-3.8-flash مجاني",
        docsUrl = "https://ai.google.dev/gemini-api/docs/pricing",
        defaultModel = "gemini-3.8-flash",
        shortName = "Gemini"
    ),
    ProviderInfo(
        id = "groq",
        label = "Groq",
        tagline = "خطة مجانية — gpt-oss-120b بسرعة عالية",
        docsUrl = "https://console.groq.com/docs/rate-limits",
        defaultModel = "openai/gpt-oss-120b"
    ),
    ProviderInfo(
        id = "openrouter",
        label = "OpenRouter",
        tagline = "موديلات :free — 50 طلباً يومياً",
        docsUrl = "https://openrouter.ai/docs/api-reference/limits",
        defaultModel = "cohere/north-mini-code:free"
    ),
    ProviderInfo(
        id = "cohere",
        label = "Cohere",
        tagline = "مفتاح تجريبي مجاني — 1000 طلب شهرياً",
        docsUrl = "https://docs.cohere.com/docs/rate-limits",
        defaultModel = "north-mini-code-1-0"
    ),
    ProviderInfo(
        id = "cloudflare",
        label = "Cloudflare Workers AI",
        tagline = "10000 Neuron يومياً — يحتاج Account ID",
        docsUrl = "https://developers.cloudflare.com/workers-ai/platform/pricing/",
        defaultModel = "@cf/openai/gpt-oss-120b",
        needsAccountId = true,
        shortName = "Cloudflare"
    ),
    ProviderInfo(
        id = "deepseek",
        label = "DeepSeek",
        tagline = "مدفوع رخيص — V4.1 Flash للكود",
        docsUrl = "https://api-docs.deepseek.com/quick_start/pricing",
        defaultModel = "deepseek-flash",
        free = false
    ),
    ProviderInfo(
        id = "openai",
        label = "OpenAI",
        tagline = "مدفوع — يتطلب رصيداً",
        docsUrl = "https://developers.openai.com/api/docs/models/all",
        defaultModel = "gpt-6-luna",
        free = false
    ),
    ProviderInfo(
        id = "claude",
        label = "Anthropic Claude",
        tagline = "مدفوع — يتطلب رصيداً",
        docsUrl = "https://platform.claude.com/docs/en/about-claude/models/overview",
        defaultModel = "claude-sonnet-5-5",
        free = false,
        shortName = "Claude"
    )
)

fun providerInfo(id: String): ProviderInfo = PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS.first()
