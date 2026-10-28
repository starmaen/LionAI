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
        REALISTIC(", photorealistic, 8k, highly detailed, masterpiece"),
        ANIME(", anime style, studio ghibli style, vibrant colors, masterpiece"),
        CARTOON(", 3d cartoon, pixar style, cute, colorful"),
        ICON(", minimalist icon, flat design, professional"),
        POSTER(", movie poster style, dramatic, cinematic"),
        CYBERPUNK(", cyberpunk, neon lights, futuristic"),
        FANTASY(", fantasy art, epic, magical"),
        PORTRAIT(", professional portrait, detailed face")
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
        // المحاولة الأولى: Pollinations (الأسرع)
        tryGenerateWithPollinations(prompt, style, aspectRatio) 
            ?: tryGenerateWithSimpleAPI(prompt, style, aspectRatio)
    }
    
    private suspend fun tryGenerateWithPollinations(
        prompt: String,
        style: GenerationStyle,
        aspectRatio: AspectRatio
    ): Bitmap? {
        return try {
            val fullPrompt = "$prompt ${style.promptSuffix}"
            val encodedPrompt = URLEncoder.encode(fullPrompt, "UTF-8")
                .replace("+", "%20")
            
            val width = getWidth(aspectRatio)
            val height = getHeight(aspectRatio)
            val seed = (System.currentTimeMillis() % 100000).toInt()
            
            val url = "https://image.pollinations.ai/prompt/$encodedPrompt" +
                "?width=$width&height=$height&seed=$seed&nologo=true"
            
            println("🦁 Pollinations URL: $url")
            
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 60000
            connection.readTimeout = 60000
            
            if (connection.responseCode == 200) {
                val inputStream: InputStream = connection.inputStream
                BitmapFactory.decodeStream(inputStream).also {
                    inputStream.close()
                }
            } else {
                println("❌ Pollinations failed: ${connection.responseCode}")
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            println("❌ Pollinations error: ${e.message}")
            null
        }
    }
    
    private suspend fun tryGenerateWithSimpleAPI(
        prompt: String,
        style: GenerationStyle,
        aspectRatio: AspectRatio
    ): Bitmap? {
        return try {
            // API بديل بسيط (مجانى)
            val fullPrompt = "$prompt ${style.promptSuffix}"
            val encodedPrompt = URLEncoder.encode(fullPrompt, "UTF-8")
            
            val width = getWidth(aspectRatio)
            val height = getHeight(aspectRatio)
            
            val url = "https://api.photoroom.com/v1/sg/$encodedPrompt?width=$width&height=$height"
            
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 60000
            connection.readTimeout = 60000
            
            if (connection.responseCode == 200) {
                val inputStream: InputStream = connection.inputStream
                BitmapFactory.decodeStream(inputStream).also {
                    inputStream.close()
                }
            } else {
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            println("❌ Fallback API error: ${e.message}")
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
