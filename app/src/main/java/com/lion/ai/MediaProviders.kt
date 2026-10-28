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
        REALISTIC(", photorealistic, 8k, highly detailed, professional photography, cinematic lighting"),
        ANIME(", anime style, studio ghibli, vibrant colors, detailed, masterpiece"),
        CARTOON(", 3d cartoon, pixar style, disney animation, cute, colorful"),
        ICON(", minimalist vector icon, flat design, clean lines, professional"),
        POSTER(", movie poster, dramatic lighting, cinematic composition"),
        CYBERPUNK(", cyberpunk, neon lights, futuristic, blade runner style"),
        FANTASY(", fantasy art, epic, magical, detailed, dramatic lighting"),
        PORTRAIT(", professional portrait, studio lighting, detailed face")
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
            // 1. توليد seed عشوائي جديد تماماً لكل طلب
            val seed = (System.currentTimeMillis() % 1000000).toInt()
            
            // 2. تحسين الـ Prompt
            val enhancedPrompt = PromptEnhancer.enhancePrompt(prompt, style, true, negativePrompt)
            
            // 3. تشفير صحيح للنص العربي
            val encodedPrompt = URLEncoder.encode(enhancedPrompt, "UTF-8")
                .replace("+", "%20")  // استبدال + بمسافة صحيحة
            
            // 4. بناء URL بشكل صحيح
            val baseUrl = "https://image.pollinations.ai/prompt"
            val imageUrl = buildString {
                append("$baseUrl/$encodedPrompt")
                append("?width=${aspectRatio.width}")
                append("&height=${aspectRatio.height}")
                append("&seed=$seed")
                append("&nologo=true")
                append("&enhance=true")
                append("&model=flux")
                append("&cache_buster=${UUID.randomUUID()}")  // منع الكاش نهائياً
            }
            
            println("🦁 [DEBUG] URL: $imageUrl")
            println("🦁 [DEBUG] Prompt: $enhancedPrompt")
            println("🦁 [DEBUG] Seed: $seed")
            
            // 5. الاتصال
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
                bitmap
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.readText()
                println("❌ [ERROR] Response: ${connection.responseCode}, $errorStream")
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
