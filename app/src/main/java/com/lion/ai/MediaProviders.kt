package com.lion.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.File

object MediaProviders {

    enum class GenerationStyle(val promptSuffix: String) {
        REALISTIC(", photorealistic, 8k, highly detailed, cinematic lighting, masterpiece"),
        ANIME(", anime style, studio ghibli, vibrant colors, masterpiece, best quality"),
        CARTOON(", 3d cartoon style, pixar style, cute, bright colors, disney style"),
        ICON(", minimalist vector icon, flat design, white background, professional, ui/ux"),
        POSTER(", movie poster style, dramatic composition, bold typography space, cinematic")
    }

    /**
     * توليد صورة من نص باستخدام Pollinations.ai (مجاني 100% وسريع)
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
            connection.connectTimeout = 60000
            connection.readTimeout = 60000
            
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
     * تحويل صورة موجودة إلى نمط معين (أنمي/كرتون)
     * ملاحظة: نستخدم هنا نهج Prompt Engineering المتقدم مع Pollinations كبداية مجانية 100%
     * يمكن لاحقاً ربطها بـ Hugging Face Inference API للحصول على Image-to-Image حقيقي.
     */
    suspend fun transformImageToStyle(
        imageDescription: String,
        style: GenerationStyle
    ): Bitmap? {
        val transformPrompt = "Transform this into: $imageDescription. Style: ${style.name}, high quality, detailed"
        return generateImage(transformPrompt, style, 1024, 1024)
    }
}
