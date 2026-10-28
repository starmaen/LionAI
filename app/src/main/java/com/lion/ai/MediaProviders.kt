package com.lion.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MediaProviders {

    enum class GenerationStyle(val promptSuffix: String) {
        REALISTIC(", photorealistic, 8k, highly detailed, cinematic lighting, masterpiece"),
        ANIME(", anime style, studio ghibli, vibrant colors, masterpiece, best quality"),
        CARTOON(", 3d cartoon style, pixar style, cute, bright colors, disney style"),
        ICON(", minimalist vector icon, flat design, white background, professional, ui/ux"),
        POSTER(", movie poster style, dramatic composition, bold typography space, cinematic")
    }

    /**
     * توليد صورة من نص باستخدام Pollinations.ai (مجاني 100% وسريع جداً)
     */
    suspend fun generateImage(
        prompt: String,
        style: GenerationStyle = GenerationStyle.REALISTIC,
        width: Int = 1024,
        height: Int = 1024,
        seed: Int = (Math.random() * 10000).toInt(),
        enhance: Boolean = true,
        negativePrompt: String = ""
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // استخدام PromptEnhancer لتحسين الـ Prompt
            val enhancedPrompt = if (enhance) {
                PromptEnhancer.enhancePrompt(prompt, style, true, negativePrompt)
            } else {
                "$prompt ${style.promptSuffix}"
            }
            
            val fullPrompt = URLEncoder.encode(enhancedPrompt, "UTF-8")
            // إضافة enhance=true و nologo=true للحصول على أفضل جودة بدون علامة مائية
            val imageUrl = "https://image.pollinations.ai/prompt/$fullPrompt?width=$width&height=$height&seed=$seed&nologo=true&enhance=true"
            
            val connection = URL(imageUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 90000  // زيادة الوقت للجودة العالية
            connection.readTimeout = 90000
            
            if (connection.responseCode == 200) {
                val inputStream: InputStream = connection.inputStream
                BitmapFactory.decodeStream(inputStream)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * توليد فيديو من نص باستخدام Hugging Face Inference API (خطة مجانية)
     * ملاحظة: هذا النموذج مجاني ولكنه قد يكون في طابور انتظار (Queue) لبضع دقائق.
     */
    suspend fun generateVideo(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // نستخدم نموذجاً مجانياً متاحاً للاستدلال
            val apiUrl = "https://api-inference.huggingface.co/models/ali-vilab/text-to-video-ms-1.7b"
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            // ملاحظة: للحصول على أداء أفضل وتجنب طابور الانتظار، يمكن للمستخدم إضافة HF Token في الإعدادات لاحقاً
            
            val jsonInputString = """{"inputs": "$prompt"}"""
            connection.outputStream.use { os ->
                val input = jsonInputString.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            
            // نماذج الفيديو المجانية ترجع غالباً حالة 503 (Service Unavailable) مع وقت تقديرية
            if (connection.responseCode == 200) {
                Result.success("تم توليد الفيديو بنجاح! (سيتم عرض ميزة الحفظ قريباً)")
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                if (errorStream.contains("estimated_time")) {
                    Result.failure(Exception("النموذج مجاني ومزدحم حالياً. الوقت المقدر: يرجى المحاولة لاحقاً أو إضافة مفتاح Hugging Face في الإعدادات."))
                } else {
                    Result.failure(Exception("فشل التوليد: ${connection.responseCode}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
