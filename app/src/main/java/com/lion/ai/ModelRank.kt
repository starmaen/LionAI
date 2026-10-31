package com.lion.ai

/**
 * ترتيب تقريبي لقوة الموديلات اعتماداً على العائلة والحجم واللواحق.
 * الهدف: أن يقترح التطبيق الموديل الأقوى المتاح من كل مزود بدلاً من أول اسم أبجدي.
 */
object ModelRank {
    private val families = listOf(
        "gpt-6" to 60, "gpt-5" to 55, "claude" to 55, "gemini" to 50,
        "nemotron-3-ultra" to 55, "nemotron-3-super" to 42, "nemotron" to 30,
        "inkling" to 45, "deepseek" to 45, "kimi" to 38, "qwen3" to 38, "qwen" to 28,
        "llama-4" to 35, "llama-3.3" to 25, "gpt-oss-120b" to 40, "gpt-oss-20b" to 20,
        "gpt-oss" to 30, "mistral-large" to 35, "mistral-medium" to 25,
        "command-a" to 28, "glm" to 26, "gemma-4" to 25, "laguna" to 20,
        "north-mini-code" to 8
    )

    private val weakTokens = listOf(
        "mini" to -25, "small" to -20, "nano" to -30, "lite" to -25,
        "instant" to -25, "tiny" to -35, "1b" to -30, "2b" to -25, "3b" to -15,
        "guard" to -200, "safety" to -200, "embed" to -200, "rerank" to -200,
        "whisper" to -200, "tts" to -200, "orpheus" to -200, "moderation" to -200
    )

    private val sizeRegex = Regex("""(\d+(?:\.\d+)?)\s*b\b""")

    fun score(id: String): Int {
        val n = id.lowercase()
        var s = 0
        families.firstOrNull { n.contains(it.first) }?.let { s += it.second }
        weakTokens.forEach { if (n.contains(it.first)) s += it.second }
        sizeRegex.findAll(n).forEach { match ->
            val size = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            s += (size / 10.0).toInt().coerceAtMost(60)
        }
        if (n.contains("ultra") || n.contains("-max") || n.contains(":max")) s += 15
        if (n.contains("pro")) s += 10
        if (n.contains("large")) s += 8
        return s
    }

    fun sorted(models: List<String>): List<String> =
        models.sortedByDescending { score(it) }

    fun strongest(models: List<String>): String? =
        models.maxByOrNull { score(it) }
}
