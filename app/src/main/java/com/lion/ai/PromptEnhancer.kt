package com.lion.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * محرك تحسين الـ Prompt - يترجم العربية للإنجليزية ويضيف تفاصيل احترافية
 */
object PromptEnhancer {
    
    // قاموس بسيط للترجمة السريعة (يمكن توسيعه أو استبداله بـ API)
    private val arabicToEnglishMap = mapOf(
        "رجل" to "man", "امرأة" to "woman", "طفل" to "child",
        "جبل" to "mountain", "بحر" to "sea", "غابة" to "forest",
        "مدينة" to "city", "ليل" to "night", "نهار" to "day",
        "ثلج" to "snow", "مطر" to "rain", "شمس" to "sun",
        "قمر" to "moon", "نجوم" to "stars", "سماء" to "sky",
        "شجرة" to "tree", "زهرة" to "flower", "حيوان" to "animal",
        "قطة" to "cat", "كلب" to "dog", "حصان" to "horse",
        "سيارة" to "car", "طائرة" to "airplane", "بيت" to "house",
        "قلعة" to "castle", "صحراء" to "desert", "نهر" to "river"
    )
    
    /**
     * ترجمة بسيطة من العربية للإنجليزية
     */
    fun translateArabicToEnglish(text: String): String {
        var translated = text
        for ((arabic, english) in arabicToEnglishMap) {
            translated = translated.replace(arabic, english)
        }
        return translated
    }
    
    /**
     * تحسين الـ Prompt بإضافة تفاصيل احترافية
     */
    fun enhancePrompt(
        originalPrompt: String,
        style: MediaProviders.GenerationStyle,
        isHighQuality: Boolean = true,
        negativePrompt: String = ""
    ): String {
        // 1. ترجمة إذا كان النص عربياً
        val basePrompt = if (originalPrompt.any { it in 'أ'..'ي' }) {
            translateArabicToEnglish(originalPrompt)
        } else {
            originalPrompt
        }
        
        // 2. إضافة تفاصيل النمط
        val styleDetails = when (style) {
            MediaProviders.GenerationStyle.REALISTIC -> 
                "photorealistic, 8k uhd, dslr, soft lighting, high quality, film grain, Fujifilm XT3"
            MediaProviders.GenerationStyle.ANIME -> 
                "anime style, studio ghibli, makoto shinkai, vibrant colors, detailed background, masterpiece, best quality"
            MediaProviders.GenerationStyle.CARTOON -> 
                "3d render, pixar style, disney animation, cute, colorful, smooth shading, high detail"
            MediaProviders.GenerationStyle.ICON -> 
                "minimalist vector icon, flat design, clean lines, white background, professional, ui/ux design, simple"
            MediaProviders.GenerationStyle.POSTER -> 
                "movie poster, dramatic lighting, cinematic composition, bold colors, professional photography, 8k"
        }
        
        // 3. بناء الـ Prompt النهائي
        val enhanced = "$basePrompt, $styleDetails"
        
        // 4. إضافة Negative Prompt إذا كان موجوداً
        val withNegative = if (negativePrompt.isNotBlank()) {
            "$enhanced --no $negativePrompt"
        } else {
            enhanced
        }
        
        return withNegative
    }
}
