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
    val webviewUrl: String? = null
)

val PROVIDERS = listOf(
    ProviderInfo(
        id = "arena",
        label = "Arena AI (مفتوح بدون مفتاح)",
        tagline = "مباشر ومجاني بالكامل — يعمل بدون مفتاح API",
        docsUrl = "https://arena.ai/text/direct?model_a=max",
        defaultModel = "default",
        shortName = "Arena AI",
        modelHint = "هذا المزود لا يحتاج إلى مفتاح API؛ يفتح منصة Arena AI مباشرة داخل التطبيق للاستفادة من أقوى النماذج مجاناً.",
        models = listOf("default"),
        webviewUrl = "https://arena.ai/text/direct?model_a=max"
    ),
    ProviderInfo(
        id = "duckduckgo_web",
        label = "DuckDuckGo AI Chat (بدون مفتاح)",
        tagline = "مجاني بالكامل وبدون تسجيل دخول — عدة نماذج",
        docsUrl = "https://duckduckgo.com/aichat",
        defaultModel = "default",
        shortName = "DuckDuckGo AI",
        modelHint = "دردشة مجانية بالكامل بدون أي تسجيل دخول أو مفتاح، تتيح التبديل بين عدة نماذج داخل الصفحة نفسها.",
        models = listOf("default"),
        webviewUrl = "https://duckduckgo.com/aichat"
    ),
    ProviderInfo(
        id = "qwen_web",
        label = "Qwen Chat (بدون مفتاح)",
        tagline = "مجاني — نماذج Qwen القوية داخل متصفح مدمج",
        docsUrl = "https://chat.qwen.ai",
        defaultModel = "default",
        shortName = "Qwen Chat",
        modelHint = "دردشة Qwen الرسمية مجانية؛ قد تحتاج تسجيل دخول بسيط داخل الصفحة نفسها لتفعيل كامل الميزات.",
        models = listOf("default"),
        webviewUrl = "https://chat.qwen.ai"
    ),
    ProviderInfo(
        id = "mistral_web",
        label = "Mistral Le Chat (بدون مفتاح)",
        tagline = "مجاني — دردشة Mistral الرسمية",
        docsUrl = "https://chat.mistral.ai",
        defaultModel = "default",
        shortName = "Le Chat",
        modelHint = "دردشة Mistral الرسمية مجانية؛ قد تحتاج تسجيل دخول بسيط داخل الصفحة نفسها.",
        models = listOf("default"),
        webviewUrl = "https://chat.mistral.ai"
    ),
    ProviderInfo(
        id = "deepseek_web",
        label = "DeepSeek Chat (بدون مفتاح)",
        tagline = "مجاني — دردشة DeepSeek الرسمية",
        docsUrl = "https://chat.deepseek.com",
        defaultModel = "default",
        shortName = "DeepSeek Chat",
        modelHint = "دردشة DeepSeek الرسمية مجانية؛ قد تحتاج تسجيل دخول بسيط داخل الصفحة نفسها.",
        models = listOf("default"),
        webviewUrl = "https://chat.deepseek.com"
    ),
    ProviderInfo(
        id = "gemini_web",
        label = "Google Gemini (دردشة ويب)",
        tagline = "مجاني بالكامل — دردشة Gemini الرسمية من جوجل",
        docsUrl = "https://gemini.google.com/app",
        defaultModel = "default",
        shortName = "Gemini Web",
        modelHint = "دردشة Gemini الرسمية من جوجل؛ مجانية وتحتاج تسجيل دخول بحساب جوجل داخل الصفحة نفسها.",
        models = listOf("default"),
        webviewUrl = "https://gemini.google.com/app"
    ),
    ProviderInfo(
        id = "copilot_web",
        label = "Microsoft Copilot (بدون مفتاح)",
        tagline = "مجاني بالكامل — دردشة Copilot الرسمية من مايكروسوفت",
        docsUrl = "https://copilot.microsoft.com",
        defaultModel = "default",
        shortName = "Copilot",
        modelHint = "دردشة Copilot الرسمية من مايكروسوفت، مجانية بالكامل ولا تحتاج اشتراكاً.",
        models = listOf("default"),
        webviewUrl = "https://copilot.microsoft.com"
    ),
    ProviderInfo(
        id = "github_copilot_web",
        label = "GitHub Copilot (بدون مفتاح)",
        tagline = "مجاني محدود — دردشة Copilot داخل GitHub",
        docsUrl = "https://github.com/copilot",
        defaultModel = "default",
        shortName = "GitHub Copilot",
        modelHint = "دردشة GitHub Copilot تحتاج تسجيل دخول بحساب GitHub؛ تتوفر حصة مجانية محدودة شهرياً دون اشتراك.",
        models = listOf("default"),
        webviewUrl = "https://github.com/copilot"
    ),
    ProviderInfo(
        id = "claude_web",
        label = "Claude.ai (بدون مفتاح)",
        tagline = "مجاني بحصة يومية — دردشة Claude الرسمية",
        docsUrl = "https://claude.ai",
        defaultModel = "default",
        shortName = "Claude Web",
        modelHint = "دردشة Claude الرسمية من Anthropic؛ تحتاج تسجيل دخول وتوفر حصة مجانية يومية.",
        models = listOf("default"),
        webviewUrl = "https://claude.ai"
    ),
    ProviderInfo(
        id = "chatgpt_web",
        label = "ChatGPT (بدون مفتاح)",
        tagline = "مجاني بحصة محدودة — دردشة ChatGPT الرسمية",
        docsUrl = "https://chatgpt.com",
        defaultModel = "default",
        shortName = "ChatGPT",
        modelHint = "دردشة ChatGPT الرسمية من OpenAI؛ تحتاج تسجيل دخول وتوفر حصة مجانية محدودة.",
        models = listOf("default"),
        webviewUrl = "https://chatgpt.com"
    ),
    ProviderInfo(
        id = "gemini",
        label = "Google Gemini",
        tagline = "Free Tier — gemini-3.8-flash مجاني",
        docsUrl = "https://ai.google.dev/gemini-api/docs/pricing",
        defaultModel = "gemini-3.8-flash",
        shortName = "Gemini",
        modelHint = "الموديلات المجانية تتغير، وبعض موديلات Pro مدفوعة. زر «تحديث» يعرض كل موديلات حسابك وليس المجانية فقط؛ راجع صفحة الأسعار.",
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
        modelHint = "مفتاح Trial يسمح بموديلات Cohere ضمن 1000 طلب شهرياً. «تحديث» يعرض موديلات الدردشة المتاحة لمفتاحك.",
        models = listOf("north-mini-code-1-0", "command-a-03-2025", "command-r7b-12-2024")
    ),
    ProviderInfo(
        id = "mistral",
        label = "Mistral AI (API)",
        tagline = "مجاني — La Plateforme، موديلات مفتوحة",
        docsUrl = "https://docs.mistral.ai/deployment/laplateforme/overview/",
        defaultModel = "mistral-small-latest",
        shortName = "Mistral",
        modelHint = "مفتاح مجاني من console.mistral.ai. «تحديث» يعرض موديلات حسابك؛ بعضها مجاني وبعضها مدفوع حسب الخطة.",
        models = listOf("mistral-small-latest", "open-mistral-nemo", "pixtral-12b-2409")
    ),
    ProviderInfo(
        id = "together",
        label = "Together AI",
        tagline = "موديلات -Free مجانية بالكامل بدون رصيد",
        docsUrl = "https://docs.together.ai/docs/serverless-models",
        defaultModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo-Free",
        shortName = "Together",
        modelHint = "الموديلات المنتهية بـ -Free مجانية تماماً بدون رصيد. «تحديث» يجلب القائمة الحالية ويُظهر المجانية منها.",
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
        modelHint = "كل موديلات Workers AI تُحتسب من حصة 10000 Neuron المجانية يومياً. «تحديث» يحتاج المفتاح وAccount ID.",
        models = listOf(
            "@cf/openai/gpt-oss-120b",
            "@cf/openai/gpt-oss-20b",
            "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
            "@cf/meta/llama-3.1-8b-instruct"
        )
    ),
    ProviderInfo(
        id = "deepseek",
        label = "DeepSeek",
        tagline = "مدفوع رخيص — V4.1 Flash للكود",
        docsUrl = "https://api-docs.deepseek.com/quick_start/pricing",
        defaultModel = "deepseek-flash",
        free = false,
        models = listOf("deepseek-flash")
    ),
    ProviderInfo(
        id = "openai",
        label = "OpenAI",
        tagline = "مدفوع — يتطلب رصيداً",
        docsUrl = "https://developers.openai.com/api/docs/models/all",
        defaultModel = "gpt-6-luna",
        free = false,
        models = listOf("gpt-6-luna")
    ),
    ProviderInfo(
        id = "claude",
        label = "Anthropic Claude",
        tagline = "مدفوع — يتطلب رصيداً",
        docsUrl = "https://platform.claude.com/docs/en/about-claude/models/overview",
        defaultModel = "claude-sonnet-5-5",
        free = false,
        shortName = "Claude",
        models = listOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5")
    )
)

fun providerInfo(id: String): ProviderInfo = PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS.first()
