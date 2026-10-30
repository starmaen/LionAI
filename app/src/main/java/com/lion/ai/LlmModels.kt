package com.lion.ai

/** يجلب قائمة الموديلات الحالية من المزود. */
internal fun LlmClient.listModels(provider: String, rawKey: String, accountId: String): List<String> {
        val apiKey = rawKey.filterNot { it.isWhitespace() }
        val bearer = mapOf("Authorization" to "Bearer $apiKey")
        val ids: List<String> = when (provider) {
            "opencode", "opencode-go", "opencode-console" -> {
                val headers = if (apiKey.isNotBlank()) bearer else emptyMap()
                val bases = when (provider) {
                    "opencode" -> listOf(
                        "https://opencode.ai/zen/v1/models",
                        "https://opencode.ai/inference/v1/models"
                    )
                    "opencode-go" -> listOf(
                        "https://opencode.ai/zen/go/v1/models",
                        "https://opencode.ai/inference/go/v1/models"
                    )
                    else -> listOf("https://opencode.ai/inference/v1/models")
                }
                val json = runCatching { getJson(bases.first(), headers) }.getOrElse { first ->
                    bases.drop(1).firstNotNullOfOrNull { url ->
                        runCatching { getJson(url, headers) }.getOrNull()
                    } ?: throw first
                }
                idsOf(json.optJSONArray("data"), "id")
                    .filterNot { it == "test" || it.startsWith("jev-") }
            }

            "openrouter" -> {
                val json = getJson("https://openrouter.ai/api/v1/models", emptyMap())
                val data = json.optJSONArray("data")
                val out = mutableListOf<String>()
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val id = item.optString("id", "")
                        val pricing = item.optJSONObject("pricing")
                        val free = id.endsWith(":free") ||
                            (pricing != null &&
                                pricing.optString("prompt") == "0" &&
                                pricing.optString("completion") == "0")
                        if (id.isNotBlank() && free) out.add(id)
                    }
                }
                out
            }

            "groq" -> {
                requireKey(apiKey)
                val json = getJson("https://api.groq.com/openai/v1/models", bearer)
                idsOf(json.optJSONArray("data"), "id").filterNot {
                    val n = it.lowercase()
                    n.contains("whisper") || n.contains("tts") || n.contains("orpheus") ||
                        n.contains("guard") || n.contains("safeguard")
                }
            }

            "gemini" -> {
                requireKey(apiKey)
                val json = getJson(
                    "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200",
                    mapOf("x-goog-api-key" to apiKey)
                )
                val data = json.optJSONArray("models")
                val out = mutableListOf<String>()
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val name = item.optString("name", "").removePrefix("models/")
                        val methods = item.optJSONArray("supportedGenerationMethods")
                        var canChat = false
                        if (methods != null) {
                            for (j in 0 until methods.length()) {
                                if (methods.optString(j) == "generateContent") canChat = true
                            }
                        }
                        val n = name.lowercase()
                        val excluded = n.contains("tts") || n.contains("image") || n.contains("live") ||
                            n.contains("embedding") || n.contains("robotics") ||
                            n.contains("computer-use") || n.contains("audio")
                        if (canChat && n.startsWith("gemini") && !excluded) out.add(name)
                    }
                }
                out
            }

            "cohere" -> {
                requireKey(apiKey)
                val json = getJson("https://api.cohere.com/v1/models?endpoint=chat&page_size=100", bearer)
                idsOf(json.optJSONArray("models"), "name")
            }

            "cloudflare" -> {
                requireKey(apiKey)
                if (accountId.isBlank()) throw LlmException(400, "أدخل Cloudflare Account ID أولاً")
                val json = getJson(
                    "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/models/search" +
                        "?task=Text%20Generation&per_page=100",
                    bearer
                )
                idsOf(json.optJSONArray("result"), "name")
            }

            "deepseek" -> {
                requireKey(apiKey)
                idsOf(getJson("https://api.deepseek.com/models", bearer).optJSONArray("data"), "id")
            }

            "openai" -> {
                requireKey(apiKey)
                idsOf(getJson("https://api.openai.com/v1/models", bearer).optJSONArray("data"), "id")
                    .filter { it.startsWith("gpt") || it.startsWith("o") }
            }

            "claude" -> {
                requireKey(apiKey)
                val json = getJson(
                    "https://api.anthropic.com/v1/models?limit=100",
                    mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
                )
                idsOf(json.optJSONArray("data"), "id")
            }

            "ollama" -> {
                requireKey(apiKey)
                idsOf(getJson("https://ollama.com/api/tags", bearer).optJSONArray("models"), "name")
            }

            "aihorde" -> {
                val json = getJsonArray(
                    "https://aihorde.net/api/v2/status/models?type=text",
                    if (apiKey.isNotBlank()) mapOf("apikey" to apiKey) else emptyMap()
                )
                val out = mutableListOf<String>()
                for (i in 0 until json.length()) {
                    val item = json.optJSONObject(i) ?: continue
                    val name = item.optString("name", "")
                    if (name.isNotBlank() && item.optInt("count", 0) > 0) out.add(name)
                }
                out
            }

            "kilo", "llm7", "ovh", "mistral", "zai", "nvidia", "together" -> {
                val info = providerInfo(provider)
                if (!info.keyless) requireKey(apiKey)
                val url = info.endpoint.removeSuffix("/chat/completions") + "/models"
                val headers = if (apiKey.isNotBlank()) bearer else emptyMap()
                val found = idsOf(getJson(url, headers).optJSONArray("data"), "id")
                when (provider) {
                    "kilo" -> found.filter { it.endsWith(":free") || it.startsWith("kilo-auto/free") }
                    "mistral" -> found.filterNot {
                        val n = it.lowercase()
                        n.contains("embed") || n.contains("moderation") || n.contains("ocr") || n.contains("voxtral")
                    }
                    "zai" -> found.filter { it.lowercase().contains("glm") }
                    "llm7" -> (listOf("default", "fast") + found).distinct()
                    else -> found
                }
            }

            "qwen" -> {
                requireKey(apiKey)
                val endpoints = listOf(
                    "https://dashscope.aliyuncs.com/compatible-mode/v1/models",
                    "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/models"
                )
                var lastError: Exception? = null
                var found: List<String> = emptyList()
                for (url in endpoints) {
                    try {
                        found = idsOf(getJson(url, bearer).optJSONArray("data"), "id")
                        if (found.isNotEmpty()) break
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                if (found.isEmpty() && lastError != null) lastError?.let { throw it }
                found
            }

            "hf" -> {
                requireKey(apiKey)
                val json = getJson(
                    "https://router.huggingface.co/v1/models",
                    bearer
                )
                idsOf(json.optJSONArray("data"), "id")
            }

            "fireworks" -> {
                requireKey(apiKey)
                val json = getJson(
                    "https://api.fireworks.ai/inference/v1/models",
                    bearer
                )
                idsOf(json.optJSONArray("data"), "id")
            }

            "perplexity" -> {
                requireKey(apiKey)
                val json = getJson(
                    "https://api.perplexity.ai/models",
                    bearer
                )
                idsOf(json.optJSONArray("data"), "id")
            }

            "grok" -> {
                requireKey(apiKey)
                val json = getJson(
                    "https://api.x.ai/v1/models",
                    bearer
                )
                idsOf(json.optJSONArray("data"), "id")
            }

            "custom" -> {
                val raw = endpointOverrides["custom"]?.trim().orEmpty()
                val problem = customUrlProblem(raw)
                if (problem != null) throw LlmException(400, problem)
                val base = normalizeCustomUrl(raw).removeSuffix("/chat/completions")
                val headers = if (apiKey.isNotBlank()) bearer else emptyMap()
                idsOf(getJson("$base/models", headers).optJSONArray("data"), "id")
            }

            else -> emptyList()
        }
        return ids.distinct().sorted().take(200)
    }
