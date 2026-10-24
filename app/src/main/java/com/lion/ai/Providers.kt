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
    /** عنوان chat/completions للمزودات المتوافقة مع OpenAI (يُستعمل للمزودات العامة الجديدة). */
    val endpoint: String = "",
    /** true = يعمل بدون مفتاح (المفتاح اختياري لرفع الحدود). */
    val keyless: Boolean = false,
    /** قائمة جاهزة تعمل بدون إنترنت؛ زر «تحديث» في الإعدادات يجلب القائمة الحالية. */
    val models: List<String> = emptyList()
)

val PROVIDERS = listOf(
    ProviderInfo(
        id = "gemini",
        label = "Google Gemini",
        tagline = "Free Tier — gemini-3.8-flash مجاني",
        docsUrl = "https://ai.google.dev/gemini-api/docs/pricing",
        defaultModel = "gemini-3.8-flash",
        shortName = "Gemini",
        modelHint = "الموديلات المجانية تتغير، وبعض موديلات Pro مدفوعة. زر «تحديث» يعرض كل موديلات حسابك وليس المجانية فقط؛ راجع صفحة الأسعار.",
        models = listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite")
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
        modelHint = "كل موديلات Groq متاحة بالخطة المجانية ضمن حدود الطلبات اليومية. أوقفت Groq موديلات compound وبعض موديلات llama، فاضغط «تحديث» لرؤية القائمة الحالية.",
        models = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b")
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
            "@cf/meta/llama-4-scout-17b-16e-instruct",
            "@cf/google/gemma-4-26b-a4b-it",
            "@cf/zai-org/glm-4.7-flash"
        )
    ),
    ProviderInfo(
        id = "kilo",
        label = "Kilo Gateway (بدون مفتاح)",
        tagline = "موديلات مجانية بدون تسجيل — 200 طلب/ساعة لكل شبكة",
        docsUrl = "https://kilo.ai/docs/gateway/authentication",
        defaultModel = "kilo-auto/free",
        shortName = "Kilo",
        keyless = true,
        endpoint = "https://api.kilo.ai/api/gateway/chat/completions",
        modelHint = "يعمل فوراً بدون مفتاح. «kilo-auto/free» يختار موديلاً مجانياً تلقائياً. بعض موديلاته قد تسجّل البيانات، فلا ترسل معلومات سرية.",
        models = listOf(
            "kilo-auto/free",
            "nvidia/nemotron-3-super-120b-a12b:free",
            "cohere/north-mini-code:free",
            "poolside/laguna-s-2.1:free",
            "qwen/qwen3.8-27b:free",
            "stepfun/step-3.7-flash:free",
            "nvidia/nemotron-3.5-lightning:free",
            "tencent/hy3:free"
        )
    ),
    ProviderInfo(
        id = "llm7",
        label = "LLM7.io (بدون مفتاح)",
        tagline = "بوابة مجانية بدون تسجيل — موديلات turbo",
        docsUrl = "https://docs.llm7.io/guides/models",
        defaultModel = "default",
        shortName = "LLM7",
        keyless = true,
        endpoint = "https://api.llm7.io/v1/chat/completions",
        modelHint = "الاستعمال المجهول محدود (حوالي 10 طلبات بالدقيقة). مفتاح مجاني من token.llm7.io يرفع الحدود. الاختيارات default وfast تعني أحدث موديل متاح.",
        models = listOf("default", "fast", "gpt-oss:20b", "DeepSeek-V4-Flash-0731", "minimax-m2.7", "mistral-Nemo-Instruct-2407")
    ),
    ProviderInfo(
        id = "ovh",
        label = "OVHcloud AI (بدون مفتاح)",
        tagline = "خوادم أوروبية — طلبان بالدقيقة لكل موديل بدون تسجيل",
        docsUrl = "https://www.ovhcloud.com/en/public-cloud/ai-endpoints/catalog/",
        defaultModel = "gpt-oss-120b",
        shortName = "OVH",
        keyless = true,
        endpoint = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions",
        modelHint = "الحد المجهول صغير جداً (2/دقيقة)، يصلح كاحتياط فقط. موديلات Qwen منه تقرأ الصور.",
        models = listOf(
            "gpt-oss-120b",
            "gpt-oss-20b",
            "Meta-Llama-3_3-70B-Instruct",
            "Qwen3.8-27B",
            "Qwen3.5-397B-A17B",
            "Mistral-Small-3.2-24B-Instruct"
        )
    ),
    ProviderInfo(
        id = "mistral",
        label = "Mistral AI",
        tagline = "وضع مجاني بدون بطاقة — رصيد شهري مجاني",
        docsUrl = "https://console.mistral.ai/api-keys",
        defaultModel = "mistral-small-latest",
        shortName = "Mistral",
        endpoint = "https://api.mistral.ai/v1/chat/completions",
        modelHint = "الوضع المجاني قد يُستعمل لتحسين موديلات Mistral (يمكن إلغاؤه من حسابك). Codestral ممتاز للبرمجة، وموديلات small وmedium تقرأ الصور.",
        models = listOf("mistral-small-latest", "mistral-medium-latest", "mistral-large-latest", "codestral-latest")
    ),
    ProviderInfo(
        id = "zai",
        label = "Z AI (GLM)",
        tagline = "موديلات GLM-4.7-Flash مجانية دائماً",
        docsUrl = "https://docs.z.ai/guides/overview/pricing",
        defaultModel = "glm-4.7-flash",
        shortName = "GLM",
        endpoint = "https://api.z.ai/api/paas/v4/chat/completions",
        modelHint = "المجاني: glm-4.7-flash (استدلال) وglm-4.6v-flash (يقرأ الصور). طلب واحد في اللحظة.",
        models = listOf("glm-4.7-flash", "glm-4.6v-flash", "glm-4.5-flash")
    ),
    ProviderInfo(
        id = "nvidia",
        label = "NVIDIA NIM",
        tagline = "مجاني لأعضاء NVIDIA Developer — أكثر من 100 موديل",
        docsUrl = "https://build.nvidia.com/explore/discover",
        defaultModel = "nvidia/nemotron-3-super-120b-a12b",
        shortName = "NVIDIA",
        endpoint = "https://integrate.api.nvidia.com/v1/chat/completions",
        modelHint = "استعمال تجريبي: لا ترسل بيانات شخصية. حوالي 40 طلباً بالدقيقة.",
        models = listOf(
            "nvidia/nemotron-3-super-120b-a12b",
            "nvidia/llama-3.1-nemotron-ultra-253b-v1",
            "openai/gpt-oss-20b",
            "google/gemma-4-31b-it",
            "mistralai/mistral-large-2-instruct"
        )
    ),
    ProviderInfo(
        id = "ollama",
        label = "Ollama Cloud",
        tagline = "باقة مجانية — موديلات كبيرة (DeepSeek وKimi وgpt-oss)",
        docsUrl = "https://ollama.com/settings/keys",
        defaultModel = "gpt-oss:120b",
        shortName = "Ollama",
        endpoint = "https://ollama.com/v1/chat/completions",
        modelHint = "حدود الباقة المجانية جلسات كل 5 ساعات وأسبوعية. «تحديث» يعرض موديلات حسابك.",
        models = listOf("gpt-oss:120b", "gpt-oss:20b", "deepseek-v4-flash", "minimax-m3", "kimi-k3", "qwen3.5:397b")
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
