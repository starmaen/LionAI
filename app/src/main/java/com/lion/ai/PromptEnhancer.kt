package com.lion.ai

object PromptEnhancer {
    
    /**
     * تحسين الـ Prompt بإضافة تفاصيل احترافية
     * نرسل النص كما هو (عربي أو إنجليزي) مع إضافة تفاصيل الجودة
     */
    fun enhancePrompt(
        originalPrompt: String,
        style: MediaProviders.GenerationStyle,
        isHighQuality: Boolean = true,
        negativePrompt: String = ""
    ): String {
        // نستخدم النص كما هو بدون ترجمة - النموذج يفهم العربية
        val basePrompt = originalPrompt.trim()
        
        // إضافة تفاصيل النمط
        val styleDetails = when (style) {
            MediaProviders.GenerationStyle.REALISTIC -> 
                "photorealistic, 8k resolution, highly detailed, professional photography, cinematic lighting, masterpiece, best quality"
            MediaProviders.GenerationStyle.ANIME -> 
                "anime style, studio ghibli, vibrant colors, detailed background, masterpiece, best quality, 4k"
            MediaProviders.GenerationStyle.CARTOON -> 
                "3d cartoon, pixar style, disney animation, cute, colorful, smooth shading, high detail, octane render"
            MediaProviders.GenerationStyle.ICON -> 
                "minimalist vector icon, flat design, clean lines, white background, professional, ui/ux design"
            MediaProviders.GenerationStyle.POSTER -> 
                "movie poster, dramatic lighting, cinematic composition, bold colors, professional photography, 8k"
            MediaProviders.GenerationStyle.CYBERPUNK -> 
                "cyberpunk, neon lights, futuristic, blade runner style, high tech, dark atmosphere, detailed"
            MediaProviders.GenerationStyle.FANTASY -> 
                "fantasy art, epic, magical, detailed, dramatic lighting, concept art, artstation, masterpiece"
            MediaProviders.GenerationStyle.PORTRAIT -> 
                "professional portrait, studio lighting, shallow depth of field, bokeh, 85mm lens, detailed face"
        }
        
        // بناء الـ Prompt النهائي
        val enhanced = "$basePrompt, $styleDetails"
        
        // إضافة Negative Prompt إذا وجد
        return if (negativePrompt.isNotBlank()) {
            "$enhanced --no $negativePrompt"
        } else {
            enhanced
        }
    }
}
