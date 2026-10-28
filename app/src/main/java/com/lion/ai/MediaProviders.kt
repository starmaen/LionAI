package com.lion.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
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
            // استخدام Hugging Face Inference API مع Stable Diffusion XL
            val apiUrl = "https://api-inference.huggingface.co/models/stabilityai/stable-diffusion-xl-base-1.0"
            
            // بناء الـ Prompt
            val fullPrompt = "${prompt} ${style.promptSuffix}"
            val negative = if (negativePrompt.isNotBlank()) negativePrompt else "ugly, deformed, noisy, blurry, low contrast"
            
            // JSON payload
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
            
            println("🦁 [DEBUG] Sending to HF: $fullPrompt")
            println(" [DEBUG] Size: ${getWidth(aspectRatio)}x${getHeight(aspectRatio)}")
            
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 120000
            connection.readTimeout = 120000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "LionAI/1.0")
            
            // إرسال الـ JSON
            connection.outputStream.use { os ->
                val input = jsonPayload.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            
            val responseCode = connection.responseCode
            println("🦁 [DEBUG] Response Code: $responseCode")
            
            if (responseCode == 200) {
                val inputStream: InputStream = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                connection.disconnect()
                println("✅ [SUCCESS] Image generated!")
                bitmap
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.readText()
                println("❌ [ERROR] HF API Error: $responseCode - $errorBody")
                
                // إذا كان الخطأ 503 (Model Loading)، انتظر وحاول مرة أخرى
                if (responseCode == 503 && errorBody.contains("estimated_time")) {
                    println(" [WAIT] Model is loading, waiting...")
                    kotlinx.coroutines.delay(5000) // انتظر 5 ثواني
                    return@withContext generateImage(prompt, style, aspectRatio, negativePrompt) // حاول مرة أخرى
                }
                
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            println("❌ [EXCEPTION] ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    // دوال مساعدة للأبعاد
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
