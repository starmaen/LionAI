package com.lion.ai

/** تصنيف المزودات: API، دردشات ويب، وأدوات صور/صوت/فيديو. */
enum class ProviderGroup(val title: String, val icon: String) {
    API("مزودات API (مفتاح أو بدون مفتاح)", "🔑"),
    WEB("دردشات الويب المجانية", "🌐"),
    MEDIA("الصور والصوت والفيديو", "🎨")
}

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
    val endpoint: String = "",
    val category: String = "chat"
) {
    /** التصنيف مشتق: بلا webviewUrl = API، ومع category غير chat = وسائط، وإلا ويب. */
    val group: ProviderGroup
        get() = when {
            category != "chat" -> ProviderGroup.MEDIA
            webviewUrl != null -> ProviderGroup.WEB
            else -> ProviderGroup.API
        }
}

val PROVIDERS = listOf(
    // ===== مزودات API (مفتاح أو بدون مفتاح) =====
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
        tagline = "مفتاح Zen من opencode.ai/auth — يتطلب رصيداً مدفوعاً",
        docsUrl = "https://opencode.ai/docs/zen/",
        defaultModel = "glm-5.3-flash",
        free = false,
        shortName = "OpenCode",
        modelHint = "يستخدم مسار Zen الرسمي (zen/v1) مع ترويسات عميل OpenCode، ثم ينتقل تلقائياً إلى مسار Console (inference) إن لزم. الموديلات المجانية (big-pickle وما ينتهي بـ -free) محجوبة عن التطبيقات الخارجية بـ FreeUsageLimit؛ استخدم موديلاً مدفوعاً برصيد أو مزود OpenCode Go. زر «تحديث» يجلب موديلات حسابك.",
        models = listOf(
            "glm-5.3-flash",
            "deepseek-v4.1-flash",
            "big-pickle",
            "glm-5.3-flash-free",
            "kimi-k2.7-code",
            "minimax-m3",
            "qwen3.8-max",
            "claude-haiku-4-5",
            "gemini-3.5-flash-lite",
            "gpt-5-nano"
        )
    ),
    ProviderInfo(
        id = "opencode-go",
        label = "OpenCode Go",
        tagline = "اشتراك Go — يعمل من هذا التطبيق بنفس مفتاح OpenCode",
        docsUrl = "https://opencode.ai/docs/go/",
        defaultModel = "deepseek-v4.1-flash",
        free = false,
        shortName = "OpenCode Go",
        modelHint = "يتطلب اشتراك OpenCode Go أو Go Plus. استخدم نفس مفتاح OpenCode، واختر موديلاً من قائمة Go. زر «تحديث» يجلب موديلات Go المتاحة لحسابك.",
        models = listOf(
            "deepseek-v4.1-flash",
            "glm-5.3-flash",
            "kimi-k2.7-code",
            "minimax-m3",
            "qwen3.8-max",
            "claude-haiku-5-5",
            "gemini-4-argon",
            "gpt-6-luna",
            "mimo-v2.6-flash",
            "muse-spark-1.3-contributor"
        )
    ),
    ProviderInfo(
        id = "opencode-console",
        label = "OpenCode Console",
        tagline = "رصيد Console المدفوع — مفتاح من opencode.ai/console",
        docsUrl = "https://opencode.ai/docs/zen/",
        defaultModel = "glm-5.3-flash",
        free = false,
        shortName = "OpenCode Console",
        modelHint = "لمفاتيح Console الحديثة ذات الرصيد المدفوع فقط. إن كان مفتاحك من opencode.ai/auth فاستخدم مزود «OpenCode Zen».",
        models = listOf(
            "glm-5.3-flash",
            "deepseek-v4.1-flash",
            "kimi-k2.7-code",
            "minimax-m3",
            "qwen3.8-max",
            "claude-haiku-5-5",
            "gemini-4-argon",
            "gpt-6-luna"
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
    ),
    ProviderInfo(
        id = "kilo",
        label = "Kilo Gateway (مجاني)",
        tagline = "بوابة OpenAI-compatible — موديلات :free بدون مفتاح",
        docsUrl = "https://kilo.ai/docs/gateway",
        defaultModel = "kilo-auto/free",
        shortName = "Kilo",
        endpoint = "https://ai-gateway.kilo.ai/api/v1/chat/completions",
        modelHint = "موديلات :free تعمل بدون مفتاح (حد 200 طلب/ساعة لكل IP)، وبالمفتاح ترتفع الحدود وتتاح موديلات أكثر.",
        models = listOf("kilo-auto/free", "minimax/minimax-m2.7:free", "z-ai/glm-5:free"),
        keyless = true
    ),
    ProviderInfo(
        id = "llm7",
        label = "LLM7.io (مجاني)",
        tagline = "بدون مفتاح — OpenAI-compatible مع موديلات متعددة",
        docsUrl = "https://docs.llm7.io",
        defaultModel = "default",
        shortName = "LLM7",
        endpoint = "https://api.llm7.io/v1/chat/completions",
        modelHint = "«default» أو «fast» يعملان مجاناً بدون مفتاح. الموديلات المتقدمة تحتاج توكن.",
        models = listOf("default", "fast", "DeepSeek-V4-Flash-0731"),
        keyless = true
    ),
    ProviderInfo(
        id = "nvidia",
        label = "NVIDIA NIM",
        tagline = "خطة تجريبية مجانية — موديلات Nemotron وGPT-OSS",
        docsUrl = "https://build.nvidia.com",
        defaultModel = "nvidia/nemotron-3-super-120b-a12b",
        shortName = "NVIDIA",
        endpoint = "https://integrate.api.nvidia.com/v1/chat/completions",
        modelHint = "مفتاح مجاني من build.nvidia.com. الاستخدام التجريبي قد يُسجَّل لتحسين الخدمة.",
        models = listOf(
            "nvidia/nemotron-3-super-120b-a12b",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b"
        )
    ),
    ProviderInfo(
        id = "ollama",
        label = "Ollama Cloud",
        tagline = "سحابي — موديلات مفتوحة عبر مفتاح API",
        docsUrl = "https://docs.ollama.com/cloud",
        defaultModel = "gpt-oss:120b",
        shortName = "Ollama",
        endpoint = "https://ollama.com/v1/chat/completions",
        modelHint = "أنشئ مفتاحاً من ollama.com ثم الصقه هنا. لا يحتاج تثبيت Ollama محلياً.",
        models = listOf("gpt-oss:120b", "gpt-oss:20b", "deepseek-v4-flash")
    ),
    ProviderInfo(
        id = "ovh",
        label = "OVHcloud AI Endpoints",
        tagline = "مجاني (بحدود) — موديلات مفتوحة مستضافة في أوروبا",
        docsUrl = "https://docs.ovhcloud.com/en/guides/public-cloud/ai-machine-learning/ai-endpoints-getting-started",
        defaultModel = "gpt-oss-120b",
        shortName = "OVH",
        endpoint = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions",
        modelHint = "يعمل بدون مفتاح بحدود أعلى للتحكم، أو بمفتاح OVH لرفع الحدود.",
        models = listOf("gpt-oss-120b", "gpt-oss-20b", "Qwen3.8-27B"),
        keyless = true
    ),
    ProviderInfo(
        id = "zai",
        label = "Z AI (Zhipu)",
        tagline = "خطة مجانية محدودة — نماذج GLM",
        docsUrl = "https://docs.z.ai",
        defaultModel = "glm-4.7-flash",
        shortName = "Z AI",
        endpoint = "https://api.z.ai/api/paas/v4/chat/completions",
        modelHint = "مفتاح من z.ai. موديلات flash أرخص/أسرع، وvision للصور.",
        models = listOf("glm-4.7-flash", "glm-4.6v-flash")
    ),

    // ===== دردشات الويب المجانية (بدون مفتاح) =====
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
    ProviderInfo(
        id = "kimi_web",
        label = "Kimi (بدون مفتاح)",
        tagline = "مجاني — مساعد Moonshot AI بنافذة سياق كبيرة",
        docsUrl = "https://www.kimi.com",
        defaultModel = "default",
        shortName = "Kimi",
        modelHint = "دردشة Kimi الرسمية؛ قد تحتاج تسجيل دخول بسيط داخل الصفحة.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://www.kimi.com"
    ),
    ProviderInfo(
        id = "meta_web",
        label = "Meta AI (بدون مفتاح)",
        tagline = "مجاني — مساعد Meta داخل المتصفح",
        docsUrl = "https://www.meta.ai",
        defaultModel = "default",
        shortName = "Meta AI",
        modelHint = "دردشة Meta AI الرسمية؛ قد تحتاج تسجيل دخول بحساب Meta.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://www.meta.ai"
    ),
    ProviderInfo(
        id = "zai_web",
        label = "Z.ai Chat (بدون مفتاح)",
        tagline = "مجاني — نماذج GLM من Zhipu",
        docsUrl = "https://chat.z.ai",
        defaultModel = "default",
        shortName = "Z.ai",
        modelHint = "دردشة Z.ai الرسمية بنماذج GLM؛ قد تحتاج تسجيل دخول.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://chat.z.ai"
    ),
    ProviderInfo(
        id = "pi_web",
        label = "Pi (بدون مفتاح)",
        tagline = "مجاني — مساعد محادثة ودود من Inflection",
        docsUrl = "https://pi.ai",
        defaultModel = "default",
        shortName = "Pi",
        modelHint = "دردشة Pi الرسمية؛ مناسبة للحوار والكتابة والتلخيص.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://pi.ai"
    ),
    ProviderInfo(
        id = "you_web",
        label = "You.com (بدون مفتاح)",
        tagline = "مجاني محدود — بحث ومساعد ذكي",
        docsUrl = "https://you.com",
        defaultModel = "default",
        shortName = "You.com",
        modelHint = "بحث ومساعد ذكي؛ مجاني مع حدود استخدام.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://you.com"
    ),
    ProviderInfo(
        id = "phind_web",
        label = "Phind (بدون مفتاح)",
        tagline = "مجاني — مساعد برمجة وبحث تقني",
        docsUrl = "https://www.phind.com",
        defaultModel = "default",
        shortName = "Phind",
        modelHint = "مخصص للبرمجة والأسئلة التقنية؛ مجاني مع حدود.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://www.phind.com"
    ),
    ProviderInfo(
        id = "aistudio_web",
        label = "Google AI Studio (بدون مفتاح)",
        tagline = "مجاني — تجربة نماذج Gemini مباشرة",
        docsUrl = "https://aistudio.google.com",
        defaultModel = "default",
        shortName = "AI Studio",
        modelHint = "واجهة Google AI Studio الرسمية؛ تحتاج تسجيل دخول بحساب جوجل.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://aistudio.google.com"
    ),
    ProviderInfo(
        id = "blackbox_web",
        label = "Blackbox AI (بدون مفتاح)",
        tagline = "مجاني — مساعد برمجة وأكواد",
        docsUrl = "https://www.blackbox.ai",
        defaultModel = "default",
        shortName = "Blackbox",
        modelHint = "مساعد برمجة؛ مجاني مع حدود استخدام.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://www.blackbox.ai"
    ),

    // ===== أدوات الصور والصوت والفيديو =====
    ProviderInfo(
        id = "bing_create",
        label = "Bing Image Creator (بدون مفتاح)",
        tagline = "مجاني — توليد صور DALL-E من مايكروسوفت",
        docsUrl = "https://www.bing.com/images/create",
        defaultModel = "default",
        shortName = "Bing Creator",
        modelHint = "توليد صور بالذكاء الاصطناعي؛ يتطلب تسجيل دخول بحساب مايكروسوفت.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://www.bing.com/images/create",
        category = "image"
    ),
    ProviderInfo(
        id = "leonardo_web",
        label = "Leonardo AI (بدون مفتاح)",
        tagline = "مجاني — حصة يومية لتوليد وتعديل الصور",
        docsUrl = "https://app.leonardo.ai",
        defaultModel = "default",
        shortName = "Leonardo",
        modelHint = "منصة احترافية لتوليد وتحرير الصور؛ حصة يومية مجانية مع تسجيل دخول.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://app.leonardo.ai",
        category = "image"
    ),
    ProviderInfo(
        id = "ideogram_web",
        label = "Ideogram (بدون مفتاح)",
        tagline = "مجاني — ممتاز للنصوص داخل الصور والتصاميم",
        docsUrl = "https://ideogram.ai",
        defaultModel = "default",
        shortName = "Ideogram",
        modelHint = "يتميز بكتابة نصوص داخل الصور بدقة؛ حصة يومية مجانية مع تسجيل دخول.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://ideogram.ai",
        category = "image"
    ),
    ProviderInfo(
        id = "craiyon_web",
        label = "Craiyon (بدون مفتاح)",
        tagline = "مجاني تماماً — توليد صور بدون تسجيل",
        docsUrl = "https://www.craiyon.com",
        defaultModel = "default",
        shortName = "Craiyon",
        modelHint = "توليد صور مجاني بدون حساب؛ جودة أبسط لكنه سريع ومباشر.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://www.craiyon.com",
        category = "image"
    ),
    ProviderInfo(
        id = "perchance_img",
        label = "Perchance Image (بدون مفتاح)",
        tagline = "مجاني تماماً — توليد صور بدون حدود تقريباً",
        docsUrl = "https://perchance.org/ai-text-to-image-generator",
        defaultModel = "default",
        shortName = "Perchance",
        modelHint = "مولد صور مجاني بدون تسجيل دخول وبدون حدود صارمة.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://perchance.org/ai-text-to-image-generator",
        category = "image"
    ),
    ProviderInfo(
        id = "luma_web",
        label = "Luma Dream Machine (بدون مفتاح)",
        tagline = "مجاني — فيديو واقعي من نص أو صورة",
        docsUrl = "https://dream-machine.lumalabs.ai",
        defaultModel = "default",
        shortName = "Luma",
        modelHint = "توليد فيديو قصير عالي الجودة؛ حصة مجانية يومية مع تسجيل دخول.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://dream-machine.lumalabs.ai",
        category = "video"
    ),
    ProviderInfo(
        id = "kling_web",
        label = "Kling AI (بدون مفتاح)",
        tagline = "مجاني — فيديو وأنيميشن متقدم",
        docsUrl = "https://app.klingai.com",
        defaultModel = "default",
        shortName = "Kling",
        modelHint = "فيديو واقعي وأنيميشن؛ رصيد يومي مجاني مع تسجيل دخول.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://app.klingai.com",
        category = "video"
    ),
    ProviderInfo(
        id = "hailuo_web",
        label = "Hailuo AI Video (بدون مفتاح)",
        tagline = "مجاني — فيديو من MiniMax بجودة عالية",
        docsUrl = "https://hailuoai.video",
        defaultModel = "default",
        shortName = "Hailuo",
        modelHint = "توليد فيديو قصير؛ حصة مجانية يومية مع تسجيل دخول.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://hailuoai.video",
        category = "video"
    ),
    ProviderInfo(
        id = "pika_web",
        label = "Pika (بدون مفتاح)",
        tagline = "مجاني — مؤثرات وأنيميشن للفيديو",
        docsUrl = "https://pika.art",
        defaultModel = "default",
        shortName = "Pika",
        modelHint = "مؤثرات فيديو ممتعة وأنيميشن؛ حصة مجانية مع تسجيل دخول.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://pika.art",
        category = "video"
    ),
    ProviderInfo(
        id = "suno_web",
        label = "Suno Music (بدون مفتاح)",
        tagline = "مجاني — توليد أغانٍ وموسيقى كاملة",
        docsUrl = "https://suno.com",
        defaultModel = "default",
        shortName = "Suno",
        modelHint = "توليد أغانٍ بالكلمات واللحن؛ حصة يومية مجانية مع تسجيل دخول.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://suno.com",
        category = "audio"
    ),
    ProviderInfo(
        id = "udio_web",
        label = "Udio Music (بدون مفتاح)",
        tagline = "مجاني — موسيقى وأغانٍ بجودة استوديو",
        docsUrl = "https://www.udio.com",
        defaultModel = "default",
        shortName = "Udio",
        modelHint = "بديل قوي لـ Suno لتوليد الموسيقى؛ حصة مجانية مع تسجيل دخول.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://www.udio.com",
        category = "audio"
    ),
    ProviderInfo(
        id = "ttsmaker_web",
        label = "TTSMaker صوت (بدون مفتاح)",
        tagline = "مجاني تماماً — تحويل النص إلى كلام",
        docsUrl = "https://ttsmaker.com",
        defaultModel = "default",
        shortName = "TTSMaker",
        modelHint = "تحويل نصوص إلى صوت طبيعي بعشرات اللغات ومنها العربية؛ بدون تسجيل.",
        models = listOf("default"),
        keyless = true,
        webviewUrl = "https://ttsmaker.com",
        category = "audio"
    ),
    ProviderInfo(
        id = "playground",
        label = "Playground AI",
        tagline = "مجاني محدود — توليد صور وتصميم",
        docsUrl = "https://playground.com",
        defaultModel = "default",
        shortName = "Playground",
        modelHint = "توليد وتحرير صور؛ حصة مجانية يومية.",
        models = listOf("default"),
        keyless = true,
        category = "image",
        webviewUrl = "https://playground.com"
    ),
    ProviderInfo(
        id = "recraft",
        label = "Recraft",
        tagline = "مجاني محدود — صور ورسوم متجهة",
        docsUrl = "https://www.recraft.ai",
        defaultModel = "default",
        shortName = "Recraft",
        modelHint = "توليد صور وSVG؛ حصة مجانية محدودة.",
        models = listOf("default"),
        keyless = true,
        category = "image",
        webviewUrl = "https://www.recraft.ai"
    ),
    ProviderInfo(
        id = "firefly",
        label = "Adobe Firefly",
        tagline = "مجاني محدود — توليد صور من Adobe",
        docsUrl = "https://firefly.adobe.com",
        defaultModel = "default",
        shortName = "Firefly",
        modelHint = "توليد صور رسمي من Adobe؛ يحتاج حساب Adobe.",
        models = listOf("default"),
        keyless = true,
        category = "image",
        webviewUrl = "https://firefly.adobe.com"
    ),
    ProviderInfo(
        id = "pollinations",
        label = "Pollinations",
        tagline = "مجاني بالكامل — صور من النص بدون مفتاح",
        docsUrl = "https://pollinations.ai",
        defaultModel = "default",
        shortName = "Pollinations",
        modelHint = "توليد صور مجاني وسريع؛ لا يحتاج حساباً.",
        models = listOf("default"),
        keyless = true,
        category = "image",
        webviewUrl = "https://pollinations.ai"
    ),
    ProviderInfo(
        id = "elevenlabs",
        label = "ElevenLabs",
        tagline = "مجاني محدود — تحويل النص إلى صوت",
        docsUrl = "https://elevenlabs.io",
        defaultModel = "default",
        shortName = "ElevenLabs",
        modelHint = "أصوات واقعية متعددة اللغات؛ حصة مجانية شهرية.",
        models = listOf("default"),
        keyless = true,
        category = "audio",
        webviewUrl = "https://elevenlabs.io"
    ),
    ProviderInfo(
        id = "runway",
        label = "Runway",
        tagline = "مجاني محدود — فيديو وتحرير إبداعي",
        docsUrl = "https://app.runwayml.com",
        defaultModel = "default",
        shortName = "Runway",
        modelHint = "توليد فيديو وتحرير؛ يحتاج حساباً وحصة مجانية محدودة.",
        models = listOf("default"),
        keyless = true,
        category = "video",
        webviewUrl = "https://app.runwayml.com"
    )
)

fun providerInfo(id: String): ProviderInfo = PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS.first()
