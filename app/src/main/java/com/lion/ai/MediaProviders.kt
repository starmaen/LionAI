package com.lion.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

object MediaProviders {

    enum class GenerationStyle(val promptSuffix: String) {
        REALISTIC(", photorealistic, 8k, highly detailed, professional photography, cinematic lighting, masterpiece"),
        ANIME(", anime style, studio ghibli, vibrant colors, detailed background, masterpiece, best quality"),
        CARTOON(", 3d cartoon, pixar style, disney animation, cute, colorful, smooth shading"),
        ICON(", minimalist vector icon, flat design, clean lines, white background, professional"),
        POSTER(", movie poster, dramatic lighting, cinematic composition, bold colors"),
        CYBERPUNK(", cyberpunk, neon lights, futuristic, blade runner style, high tech"),
        FANTASY(", fantasy art, epic, magical, detailed, dramatic lighting, concept art"),
        PORTRAIT(", professional portrait, studio lighting, shallow depth of field, detailed face")
    }

    enum class AspectRatio(val width: Int, val height: Int, val label: String) {
        SQUARE(1024, 1024, "مربع 1:1"),
        PORTRAIT(768, 1024, "طولي 3:4"),
        LANDSCAPE(1024, 768, "عرضي 4:3"),
        WIDE(1280, 720, "سينمائي 16:9"),
        TALL(720, 1280, "ستوري 9:16")
    }

    suspend fun generateImage(
        prompt: String,
        style: GenerationStyle = GenerationStyle.REALISTIC,
        aspectRatio: AspectRatio = AspectRatio.SQUARE,
        negativePrompt: String = ""
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // 1. استخدام SmartPromptEnhancer لتحسين الـ Prompt
            val enhancedPrompt = SmartPromptEnhancer.enhancePrompt(prompt, style, negativePrompt)
            val finalPrompt = SmartPromptEnhancer.buildFinalPrompt(enhancedPrompt, negativePrompt)
            
            println("🦁 [DEBUG] Final Prompt: $finalPrompt")
            
            // 2. تشفير صحيح
            val encodedPrompt = URLEncoder.encode(finalPrompt, "UTF-8")
                .replace("+", "%20")
            
            // 3. بناء URL مع منع الكاش
            val seed = (System.currentTimeMillis() % 1000000).toInt()
            val cacheBuster = UUID.randomUUID().toString()
            val imageUrl = "https://image.pollinations.ai/prompt/$encodedPrompt" +
                "?width=${aspectRatio.width}" +
                "&height=${aspectRatio.height}" +
                "&seed=$seed" +
                "&nologo=true" +
                "&enhance=true" +
                "&model=flux" +
                "&cache_buster=$cacheBuster"
            
            println("🦁 [DEBUG] URL: $imageUrl")
            
            // 4. الاتصال
            val connection = URL(imageUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 120000
            connection.readTimeout = 120000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.instanceFollowRedirects = true
            
            if (connection.responseCode == 200) {
                val inputStream: InputStream = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                connection.disconnect()
                println("✅ [SUCCESS] Image generated!")
                bitmap
            } else {
                println("❌ [ERROR] Response: ${connection.responseCode}")
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            println("❌ [EXCEPTION] ${e.message}")
            e.printStackTrace()
            null
        }
    }
}
