package com.lion.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * محرك توليد الوسائط - يدعم المزودين المجانيين والسريعين
 */
object MediaProviders {

    // أنماط التوليد المتاحة
    enum class GenerationStyle(val promptSuffix: String) {
        REALISTIC(", photorealistic, 8k, highly detailed, cinematic lighting"),
        ANIME(", anime style, studio ghibli, vibrant colors, masterpiece"),
        CARTOON(", 3d cartoon style, pixar style, cute, bright colors"),
        ICON(", minimalist vector icon, flat design, white background, professional"),
        POSTER(", movie poster style, dramatic composition, bold typography space")
    }

    /**
     * توليد صورة باستخدام Pollinations.ai (مجاني وسريع جداً)
     */
    suspend fun generateImage(
        prompt: String,
        style: GenerationStyle = GenerationStyle.REALISTIC,
        width: Int = 1024,
        height: Int = 1024,
        seed: Int = (Math.random() * 10000).toInt()
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val fullPrompt = URLEncoder.encode("$prompt ${style.promptSuffix}", "UTF-8")
            val imageUrl = "https://image.pollinations.ai/prompt/$fullPrompt?width=$width&height=$height&seed=$seed&nologo=true"
            
            val connection = URL(imageUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 60000 // 60 ثانية انتظار
            connection.readTimeout = 60000
            
            if (connection.responseCode == 200) {
                val inputStream: InputStream = connection.inputStream
                BitmapFactory.decodeStream(inputStream)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * تحويل صورة إلى أنمي (محاكاة عبر Prompt Engineering مع Pollinations)
     * ملاحظة: للتحويل الحقيقي Image-to-Image نحتاج Stable Diffusion API لاحقاً
     */
    suspend fun transformToAnimeStyle(
        description: String, 
        originalImageHint: String = ""
    ): Bitmap? {
        val animePrompt = "anime version of $description, $originalImageHint, anime style, studio ghibli, detailed"
        return generateImage(animePrompt, GenerationStyle.ANIME)
    }
}
