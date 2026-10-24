package com.lion.ai

data class ProviderInfo(
    val id: String,
    val label: String,
    val tagline: String,
    val docsUrl: String,
    val defaultModel: String,
    val free: Boolean = true,
    val needsAccountId: Boolean = false,
    val shortName: String = label,
    val modelHint: String = "",
    val models: List<String> = emptyList(),
    val webviewUrl: String? = null,
    val keyless: Boolean = false,
    val endpoint: String = ""
)

val PROVIDERS = listOf(
    // ===== مواقع دردشة مدمجة (بدون مفتاح) =====
    ProviderInfo(
        id = "arena",
        label = "Arena AI (مفتوح بدون مفتاح)",
        tagline = "مباشر ومجاني بالكامل — يعمل بدون مفتاح API",
        docsUrl = "https://arena.ai",
        defaultModel = "default",
        shortName = "Arena AI",
        modelHint = "يفتح منصة Arena AI داخل التطبيق للاستفادة من أقوى النماذج مجاناً.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://arena.ai/submitPrompt?mode=direct-battle&modality=chat&prompt=%D9%85%D8%B1%D8%AD%D8%A8%D8%A7&selectedModel=019f19f2-41f1-7c6d-9891-48d02fd9952c"
    ),
    ProviderInfo(
        id = "duckduckgo_web",
        label = "DuckDuckGo AI Chat (بدون مفتاح)",
        tagline = "مجاني بالكامل وبدون تسجيل دخول — عدة نماذج",
        docsUrl = "https://duckduckgo.com/aichat",
        defaultModel = "default",
        shortName = "DuckDuckGo AI",
        modelHint = "دردشة مجانية بدون تسجيل دخول أو مفتاح، تتيح التبديل بين عدة نماذج داخل الصفحة.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://duckduckgo.com/aichat"
    ),
    ProviderInfo(
        id = "gemini_web",
        label = "Google Gemini (دردشة ويب)",
        tagline = "مجاني — دردشة Gemini الرسمية من جوجل",
        docsUrl = "https://gemini.google.com/app",
        defaultModel = "default",
        shortName = "Gemini Web",
        modelHint = "دردشة Gemini الرسمية؛ تحتاج تسجيل دخول بحساب جوجل داخل الصفحة.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://gemini.google.com/app"
    ),
    ProviderInfo(
        id = "copilot_web",
        label = "Microsoft Copilot (بدون مفتاح)",
        tagline = "مجاني — دردشة Copilot الرسمية من مايكروسوفت",
        docsUrl = "https://copilot.microsoft.com",
        defaultModel = "default",
        shortName = "Copilot",
        modelHint = "دردشة Copilot الرسمية من مايكروسوفت، مجانية ولا تحتاج اشتراكاً.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://copilot.microsoft.com"
    ),
    ProviderInfo(
        id = "github_copilot_web",
        label = "GitHub Copilot (بدون مفتاح)",
        tagline = "مجاني محدود — دردشة Copilot داخل GitHub",
        docsUrl = "https://github.com/copilot",
        defaultModel = "default",
        shortName = "GitHub Copilot",
        modelHint = "تحتاج تسجيل دخول بحساب GitHub، وتتوفر حصة مجانية محدودة.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://github.com/copilot"
    ),
    ProviderInfo(
        id = "claude_web",
        label = "Claude.ai (بدون مفتاح)",
        tagline = "مجاني بحصة يومية — دردشة Claude الرسمية",
        docsUrl = "https://claude.ai",
        defaultModel = "default",
        shortName = "Claude Web",
        modelHint = "دردشة Claude الرسمية من Anthropic؛ تحتاج تسجيل دخول وتوفر حصة يومية.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://claude.ai"
    ),
    ProviderInfo(
        id = "deepseek_web",
        label = "DeepSeek Chat (بدون مفتاح)",
        tagline = "مجاني — دردشة DeepSeek الرسمية",
        docsUrl = "https://chat.deepseek.com",
        defaultModel = "default",
        shortName = "DeepSeek Chat",
        modelHint = "دردشة DeepSeek الرسمية مجانية؛ قد تحتاج تسجيل دخول بسيط داخل الصفحة.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://chat.deepseek.com"
    ),
    ProviderInfo(
        id = "chatgpt_web",
        label = "ChatGPT (بدون مفتاح)",
        tagline = "مجاني بحصة محدودة — دردشة ChatGPT الرسمية",
        docsUrl = "https://chatgpt.com",
        defaultModel = "default",
        shortName = "ChatGPT",
        modelHint = "دردشة ChatGPT الرسمية من OpenAI؛ تحتاج تسجيل دخول وتوفر حصة محدودة.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://chatgpt.com"
    ),
    ProviderInfo(
        id = "mistral_web",
        label = "Mistral Le Chat (بدون مفتاح)",
        tagline = "مجاني — دردشة Mistral الرسمية",
        docsUrl = "https://chat.mistral.ai",
        defaultModel = "default",
        shortName = "Le Chat",
        modelHint = "دردشة Mistral الرسمية مجانية؛ قد تحتاج تسجيل دخول بسيط داخل الصفحة.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://chat.mistral.ai"
    ),
    ProviderInfo(
        id = "qwen_web",
        label = "Qwen Chat (بدون مفتاح)",
        tagline = "مجاني — نماذج Qwen داخل متصفح مدمج",
        docsUrl = "https://chat.qwen.ai",
        defaultModel = "default",
        shortName = "Qwen Chat",
        modelHint = "دردشة Qwen الرسمية مجانية؛ قد تحتاج تسجيل دخول بسيط داخل الصفحة.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://chat.qwen.ai"
    ),
    ProviderInfo(
        id = "perplexity_web",
        label = "Perplexity AI (بدون مفتاح)",
        tagline = "مجاني — بحث ذكي مع إجابات مصدرة",
        docsUrl = "https://www.perplexity.ai",
        defaultModel = "default",
        shortName = "Perplexity",
        modelHint = "محرك بحث ذكي يعرض المصادر مع الإجابات؛ مجاني مع حدود استخدام.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://www.perplexity.ai"
    ),
    ProviderInfo(
        id = "grok_web",
        label = "Grok (بدون مفتاح)",
        tagline = "مجاني محدود — دردشة Grok من xAI",
        docsUrl = "https://grok.com",
        defaultModel = "default",
        shortName = "Grok",
        modelHint = "دردشة Grok الرسمية من xAI؛ قد تحتاج تسجيل دخول داخل الصفحة.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://grok.com"
    ),
    ProviderInfo(
        id = "hf_chat_web",
        label = "HuggingFace Chat (بدون مفتاح)",
        tagline = "مجاني — نماذج مفتوحة المصدر",
        docsUrl = "https://huggingface.co/chat",
        defaultModel = "default",
        shortName = "HuggingFace",
        modelHint = "منصة نماذج مفتوحة المصدر؛ قد تحتاج تسجيل دخول مجاني داخل الصفحة.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://huggingface.co/chat"
    ),
    ProviderInfo(
        id = "poe_web",
        label = "Poe (بدون مفتاح)",
        tagline = "مجاني محدود — عدة نماذج في مكان واحد",
        docsUrl = "https://poe.com",
        defaultModel = "default",
        shortName = "Poe",
        modelHint = "منصة تجمع عدة نماذج؛ قد تحتاج تسجيل دخول داخل الصفحة.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://poe.com"
    ),

    // ===== مزودو API (تحتاج مفتاحاً في الإعدادات) =====
    ProviderInfo(
        id = "gemini",
        label = "Google Gemini (API)",
        tagline = "Free Tier — gemini-3.8-flash مجاني",
        docsUrl = "https://ai.google.dev/gemini-api/docs/pricing",
        defaultModel = "gemini-3.8-flash",
        shortName = "Gemini",
        modelHint = "الموديلات المجانية تتغير، وبعض موديلات Pro مدفوعة. زر «تحديث» يعرض موديلات حسابك؛ راجع صفحة الأسعار.",
        models = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.1-flash-lite")
    ),
    ProviderInfo(
        id = "opencode",
        label = "OpenCode Zen",
        tagline = "موديلات مجانية ممتازة للكود — المفتاح من opencode.ai/auth",
        docsUrl = "https://opencode.ai/docs/zen/",
        defaultModel = "big-pickle",
        shortName = "OpenCode",
        modelHint = "زر «تحديث» يعرض الموديلات المجانية الحالية فقط. تعمل هنا موديلات chat/completions.",
        models = listOf(
            "big-pickle",
            "deepseek-v4-flash-free",
            "minimax-m3-free",
            "mimo-v2.5-free",
            "nemotron-3-super-free"
        )
    ),
    ProviderInfo(
        id = "groq",
        label = "Groq",
        tagline = "خطة مجانية — gpt-oss-120b بسرعة عالية",
        docsUrl = "https://console.groq.com/docs/rate-limits",
        defaultModel = "openai/gpt-oss-120b",
        modelHint = "كل موديلات Groq متاحة بالخطة المجانية ضمن حدود الطلبات اليومية لكل موديل.",
        models = listOf(
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "groq/compound",
            "groq/compound-mini"
        )
    ),
    ProviderInfo(
        id = "openrouter",
        label = "OpenRouter",
        tagline = "موديلات :free — 50 طلباً يومياً",
        docsUrl = "https://openrouter.ai/docs/api-reference/limits",
        defaultModel = "cohere/north-mini-code:free",
        modelHint = "زر «تحديث» يجلب كل الموديلات المجانية (:free) الحالية، ولا يحتاج مفتاحاً للقائمة.",
        models = listOf("cohere/north-mini-code:free")
    ),
    ProviderInfo(
        id = "cohere",
        label = "Cohere",
        tagline = "مفتاح تجريبي مجاني — 1000 طلب شهرياً",
        docsUrl = "https://docs.cohere.com/docs/rate-limits",
        defaultModel = "north-mini-code-1-0",
        modelHint = "مفتاح Trial يسمح بموديلات Cohere ضمن 1000 طلب شهرياً.",
        models = listOf("north-mini-code-1-0", "command-a-03-2025", "command-r7b-12-2024")
    ),
    ProviderInfo(
        id = "mistral",
        label = "Mistral AI (API)",
        tagline = "مجاني — La Plateforme، موديلات مفتوحة",
        docsUrl = "https://docs.mistral.ai/deployment/laplateforme/overview/",
        defaultModel = "mistral-small-latest",
        shortName = "Mistral",
        endpoint = "https://api.mistral.ai/v1/chat/completions",
        modelHint = "مفتاح مجاني من console.mistral.ai. «تحديث» يعرض موديلات حسابك.",
        models = listOf("mistral-small-latest", "open-mistral-nemo", "pixtral-12b-2409")
    ),
    ProviderInfo(
        id = "together",
        label = "Together AI",
        tagline = "موديلات -Free مجانية بالكامل بدون رصيد",
        docsUrl = "https://docs.together.ai/docs/serverless-models",
        defaultModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo-Free",
        shortName = "Together",
        endpoint = "https://api.together.xyz/v1/chat/completions",
        modelHint = "الموديلات المنتهية بـ -Free مجانية تماماً. «تحديث» يجلب القائمة ويُظهر المجانية منها.",
        models = listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo-Free")
    ),
    ProviderInfo(
        id = "cloudflare",
        label = "Cloudflare Workers AI",
        tagline = "10000 Neuron يومياً — يحتاج Account ID",
        docsUrl = "https://developers.cloudflare.com/workers-ai/platform/pricing/",
        defaultModel = "@cf/openai/gpt-oss-120b",
        needsAccountId = true,
        shortName = "Cloudflare",
        modelHint = "كل موديلات Workers AI تُحتسب من حصة 10000 Neuron المجانية يومياً.",
        models = listOf(
            "@cf/openai/gpt-oss-120b",
            "@cf/openai/gpt-oss-20b",
            "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
            "@cf/meta/llama-3.1-8b-instruct"
        )
    ),
    ProviderInfo(
        id = "deepseek",
        label = "DeepSeek (API)",
        tagline = "مدفوع رخيص — V4.1 Flash للكود",
        docsUrl = "https://api-docs.deepseek.com/quick_start/pricing",
        defaultModel = "deepseek-flash",
        free = false,
        models = listOf("deepseek-flash")
    ),
    ProviderInfo(
        id = "openai",
        label = "OpenAI (API)",
        tagline = "مدفوع — يتطلب رصيداً",
        docsUrl = "https://developers.openai.com/api/docs/models/all",
        defaultModel = "gpt-6-luna",
        free = false,
        models = listOf("gpt-6-luna")
    ),
    ProviderInfo(
        id = "claude",
        label = "Anthropic Claude (API)",
        tagline = "مدفوع — يتطلب رصيداً",
        docsUrl = "https://platform.claude.com/docs/en/about-claude/models/overview",
        defaultModel = "claude-sonnet-5-5",
        free = false,
        shortName = "Claude",
        models = listOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5")
    )
)

fun providerInfo(id: String): ProviderInfo = PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS.first()
