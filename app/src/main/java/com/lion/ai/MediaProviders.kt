package com.lion.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

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

    enum class AspectRatio(val label: String) {
        SQUARE("1:1"),
        PORTRAIT("3:4"),
        LANDSCAPE("4:3"),
        WIDE("16:9"),
        TALL("9:16")
    }

    suspend fun generateImage(
        prompt: String,
        style: GenerationStyle = GenerationStyle.REALISTIC,
        aspectRatio: AspectRatio = AspectRatio.SQUARE,
        negativePrompt: String = ""
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val apiUrl = "https://api-inference.huggingface.co/models/stabilityai/stable-diffusion-xl-base-1.0"
            
            val fullPrompt = "$prompt ${style.promptSuffix}"
            val negative = if (negativePrompt.isNotBlank()) negativePrompt else "ugly, deformed, noisy, blurry, low contrast"
            
            val jsonPayload = """
                {
                    "inputs": "$fullPrompt",
                    "parameters": {
                        "negative_prompt": "$negative",
                        "width": ${getWidth(aspectRatio)},
                        "height": ${getHeight(aspectRatio)},
                        "num_inference_steps": 30,
                        "guidance_scale": 7.5
                    }
                }
            """.trimIndent()
            
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 120000
            connection.readTimeout = 120000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "LionAI/1.0")
            
            connection.outputStream.use { os ->
                val input = jsonPayload.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            
            val responseCode = connection.responseCode
            
            if (responseCode == 200) {
                val inputStream: InputStream = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                connection.disconnect()
                bitmap
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.readText()
                
                // الإصلاح هنا: استخدام ?.contains مع == true للتعامل الآمن مع القيم الفارغة
                if (responseCode == 503 && errorBody?.contains("estimated_time") == true) {
                    kotlinx.coroutines.delay(5000) // انتظر 5 ثوانٍ
                    connection.disconnect()
                    return@withContext generateImage(prompt, style, aspectRatio, negativePrompt)
                }
                
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun getWidth(ratio: AspectRatio): Int {
        return when (ratio) {
            AspectRatio.SQUARE -> 1024
            AspectRatio.PORTRAIT -> 768
            AspectRatio.LANDSCAPE -> 1024
            AspectRatio.WIDE -> 1280
            AspectRatio.TALL -> 720
        }
    }
    
    private fun getHeight(ratio: AspectRatio): Int {
        return when (ratio) {
            AspectRatio.SQUARE -> 1024
            AspectRatio.PORTRAIT -> 1024
            AspectRatio.LANDSCAPE -> 768
            AspectRatio.WIDE -> 720
            AspectRatio.TALL -> 1280
        }
    }
}
