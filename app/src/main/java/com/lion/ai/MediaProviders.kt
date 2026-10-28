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

    enum class GenerationStyle(val promptSuffix: String, val model: String) {
        REALISTIC(
            ", photorealistic, 8k uhd, dslr, soft lighting, high quality, film grain, Fujifilm XT3, masterpiece",
            "flux"
        ),
        ANIME(
            ", anime style, studio ghibli, makoto shinkai, vibrant colors, detailed background, masterpiece, best quality, 4k",
            "flux"
        ),
        CARTOON(
            ", 3d render, pixar style, disney animation, cute, colorful, smooth shading, high detail, octane render",
            "flux"
        ),
        ICON(
            ", minimalist vector icon, flat design, clean lines, white background, professional, ui/ux design, simple, svg style",
            "flux"
        ),
        POSTER(
            ", movie poster, dramatic lighting, cinematic composition, bold colors, professional photography, 8k, masterpiece",
            "flux"
        ),
        CYBERPUNK(
            ", cyberpunk, neon lights, futuristic, blade runner style, high tech, dark atmosphere, rain, detailed",
            "flux"
        ),
        FANTASY(
            ", fantasy art, epic, magical, detailed, dramatic lighting, concept art, artstation, masterpiece",
            "flux"
        ),
        PORTRAIT(
            ", professional portrait, studio lighting, shallow depth of field, bokeh, 85mm lens, detailed face, masterpiece",
            "flux"
        )
    }

    enum class AspectRatio(val width: Int, val height: Int, val label: String) {
        SQUARE(1024, 1024, "مربع 1:1"),
        PORTRAIT(768, 1024, "طولي 3:4"),
        LANDSCAPE(1024, 768, "عرضي 4:3"),
        WIDE(1280, 720, "سينمائي 16:9"),
        TALL(720, 1280, "ستوري 9:16")
    }

    /**
     * توليد صورة باستخدام Pollinations.ai مع نموذج Flux (الأقوى مجاناً)
     */
    suspend fun generateImage(
        prompt: String,
        style: GenerationStyle = GenerationStyle.REALISTIC,
        aspectRatio: AspectRatio = AspectRatio.SQUARE,
        seed: Int = (Math.random() * 100000).toInt(),
        enhance: Boolean = true,
        negativePrompt: String = ""
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // تحسين الـ Prompt
            val enhancedPrompt = PromptEnhancer.enhancePrompt(prompt, style, enhance, negativePrompt)
            val fullPrompt = URLEncoder.encode(enhancedPrompt, "UTF-8")
            
            // استخدام نموذج Flux مع جميع التحسينات
            val imageUrl = "https://image.pollinations.ai/prompt/$fullPrompt" +
                "?width=${aspectRatio.width}" +
                "&height=${aspectRatio.height}" +
                "&seed=$seed" +
                "&nologo=true" +
                "&enhance=true" +
                "&model=${style.model}"
            
            val connection = URL(imageUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 120000
            connection.readTimeout = 120000
            connection.setRequestProperty("User-Agent", "LionAI/1.0")
            
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
     * توليد فيديو من نص (Hugging Face - مجاني)
     */
    suspend fun generateVideo(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val apiUrl = "https://api-inference.huggingface.co/models/ali-vilab/text-to-video-ms-1.7b"
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            
            val jsonInputString = """{"inputs": "$prompt"}"""
            connection.outputStream.use { os ->
                val input = jsonInputString.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            
            if (connection.responseCode == 200) {
                Result.success("تم توليد الفيديو بنجاح!")
            } else {
                Result.failure(Exception("النموذج مشغول حالياً، حاول لاحقاً"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
