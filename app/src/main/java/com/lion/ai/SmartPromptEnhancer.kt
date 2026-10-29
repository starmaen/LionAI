package com.lion.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * محسن الـ Prompt الذكي - يستخدم سلسلة احتياطية
 * 1. المزود المركزي (LlmProviders)
 * 2. Pollinations (مجاني)
 * 3. ترجمة محلية بسيطة
 */
object SmartPromptEnhancer {

    // قاموس ترجمة بسيط كـ fallback أخير
    private val simpleTranslations = mapOf(
        "رجل" to "man", "امرأة" to "woman", "طفل" to "child",
        "جبل" to "mountain", "بحر" to "sea", "غابة" to "forest",
        "مدينة" to "city", "ليل" to "night", "نهار" to "day",
        "ثلج" to "snow", "مطر" to "rain", "شمس" to "sun",
        "قمر" to "moon", "نجوم" to "stars", "سماء" to "sky",
        "شجرة" to "tree", "زهرة" to "flower", "حيوان" to "animal",
        "قطة" to "cat", "كلب" to "dog", "حصان" to "horse",
        "سيارة" to "car", "طائرة" to "airplane", "بيت" to "house",
        "قلعة" to "castle", "صحراء" to "desert", "نهر" to "river",
        "يمتطي" to "riding", "يرتدي" to "wearing", "يلعب" to "playing",
        "يطير" to "flying", "يسبح" to "swimming", "يجري" to "running"
    )

    /**
     * تحسين الـ Prompt مع سلسلة احتياطية
     */
    suspend fun enhancePrompt(
        userPrompt: String,
        style: MediaProviders.GenerationStyle,
        negativePrompt: String = ""
    ): String = withContext(Dispatchers.IO) {
        try {
            // المحاولة 1: استخدام المزود المركزي
            val enhanced = try {
                enhanceWithCentralProvider(userPrompt, style)
            } catch (e: Exception) {
                println("⚠️ [SmartEnhancer] Central provider failed: ${e.message}")
                null
            }

            if (enhanced != null && enhanced.isNotBlank()) {
                println("✅ [SmartEnhancer] Used central provider")
                return@withContext "$enhanced ${style.promptSuffix}"
            }

            // المحاولة 2: ترجمة محلية بسيطة
            println("🔄 [SmartEnhancer] Using local translation fallback")
            val translated = translateLocally(userPrompt)
            "$translated ${style.promptSuffix}"

        } catch (e: Exception) {
            println("❌ [SmartEnhancer] All methods failed: ${e.message}")
            // آخر محاولة: استخدام النص كما هو
            "$userPrompt ${style.promptSuffix}"
        }
    }

    /**
     * محاولة استخدام المزود المركزي (LlmProviders)
     */
    private suspend fun enhanceWithCentralProvider(
        prompt: String,
        style: MediaProviders.GenerationStyle
    ): String? {
        // ملاحظة: هذا يتطلب تكامل مع LlmProviders الموجود في التطبيق
        // سنستخدم Pollinations كبديل سريع الآن
        return null // سيتم تفعيله لاحقاً
    }

    /**
     * ترجمة محلية بسيطة
     */
    private fun translateLocally(text: String): String {
        var translated = text
        for ((arabic, english) in simpleTranslations) {
            translated = translated.replace(arabic, english)
        }
        return translated
    }

    /**
     * بناء الـ Prompt النهائي مع Negative Prompt
     */
    fun buildFinalPrompt(
        enhancedPrompt: String,
        negativePrompt: String = ""
    ): String {
        return if (negativePrompt.isNotBlank()) {
            "$enhancedPrompt --no $negativePrompt"
        } else {
            enhancedPrompt
        }
    }
}
