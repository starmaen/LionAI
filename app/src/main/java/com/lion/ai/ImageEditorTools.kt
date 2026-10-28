package com.lion.ai

import android.graphics.*
import android.graphics.Bitmap.createBitmap
import kotlin.math.roundToInt

/**
 * أدوات تعديل الصور الاحترافية - تعمل مباشرة على Bitmap
 */
object ImageEditorTools {

    enum class FilterType {
        NONE, GRAYSCALE, SEPIA, INVERT, VINTAGE, COLD, WARM, HIGH_CONTRAST
    }

    /**
     * تطبيق فلتر على الصورة
     */
    fun applyFilter(bitmap: Bitmap, filter: FilterType): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            var r = Color.red(pixel)
            var g = Color.green(pixel)
            var b = Color.blue(pixel)
            val a = Color.alpha(pixel)

            when (filter) {
                FilterType.GRAYSCALE -> {
                    val gray = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt()
                    r = g = b = gray
                }
                FilterType.SEPIA -> {
                    r = (0.393 * r + 0.769 * g + 0.189 * b).roundToInt().coerceIn(0, 255)
                    g = (0.349 * r + 0.686 * g + 0.168 * b).roundToInt().coerceIn(0, 255)
                    b = (0.272 * r + 0.534 * g + 0.131 * b).roundToInt().coerceIn(0, 255)
                }
                FilterType.INVERT -> {
                    r = 255 - r; g = 255 - g; b = 255 - b
                }
                FilterType.VINTAGE -> {
                    r = (r * 1.2).roundToInt().coerceIn(0, 255)
                    g = (g * 1.0).roundToInt().coerceIn(0, 255)
                    b = (b * 0.8).roundToInt().coerceIn(0, 255)
                }
                FilterType.COLD -> {
                    r = (r * 0.9).roundToInt().coerceIn(0, 255)
                    g = (g * 1.0).roundToInt().coerceIn(0, 255)
                    b = (b * 1.2).roundToInt().coerceIn(0, 255)
                }
                FilterType.WARM -> {
                    r = (r * 1.2).roundToInt().coerceIn(0, 255)
                    g = (g * 1.1).roundToInt().coerceIn(0, 255)
                    b = (b * 0.8).roundToInt().coerceIn(0, 255)
                }
                FilterType.HIGH_CONTRAST -> {
                    val factor = 1.5f
                    r = (((r - 128) * factor + 128)).roundToInt().coerceIn(0, 255)
                    g = (((g - 128) * factor + 128)).roundToInt().coerceIn(0, 255)
                    b = (((b - 128) * factor + 128)).roundToInt().coerceIn(0, 255)
                }
                FilterType.NONE -> {}
            }
            pixels[i] = Color.argb(a, r, g, b)
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * تعديل السطوع (-100 إلى 100)
     */
    fun adjustBrightness(bitmap: Bitmap, value: Int): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (Color.red(pixel) + value).coerceIn(0, 255)
            val g = (Color.green(pixel) + value).coerceIn(0, 255)
            val b = (Color.blue(pixel) + value).coerceIn(0, 255)
            pixels[i] = Color.argb(Color.alpha(pixel), r, g, b)
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * تعديل التباين (-100 إلى 100)
     */
    fun adjustContrast(bitmap: Bitmap, value: Int): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        val factor = (259 * (value + 255)) / (255 * (259 - value)).toFloat()

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (factor * (Color.red(pixel) - 128) + 128).roundToInt().coerceIn(0, 255)
            val g = (factor * (Color.green(pixel) - 128) + 128).roundToInt().coerceIn(0, 255)
            val b = (factor * (Color.blue(pixel) - 128) + 128).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(Color.alpha(pixel), r, g, b)
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * تعديل التشبع (-100 إلى 100)
     */
    fun adjustSaturation(bitmap: Bitmap, value: Int): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        val factor = 1 + value / 100f

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt()
            val nr = (gray + factor * (r - gray)).roundToInt().coerceIn(0, 255)
            val ng = (gray + factor * (g - gray)).roundToInt().coerceIn(0, 255)
            val nb = (gray + factor * (b - gray)).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(Color.alpha(pixel), nr, ng, nb)
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * تدوير الصورة
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * قلب الصورة أفقياً
     */
    fun flipHorizontal(bitmap: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.postScale(-1f, 1f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * قلب الصورة عمودياً
     */
    fun flipVertical(bitmap: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.postScale(1f, -1f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * دمج صورتين معاً (Blend) - مثل Midjourney Blend
     */
    fun blendImages(bitmap1: Bitmap, bitmap2: Bitmap, alpha: Float = 0.5f): Bitmap {
        val width = maxOf(bitmap1.width, bitmap2.width)
        val height = maxOf(bitmap1.height, bitmap2.height)
        
        val result = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        
        // رسم الصورة الأولى
        canvas.drawBitmap(bitmap1, 0f, 0f, null)
        
        // رسم الصورة الثانية بشفافية
        val paint = Paint().apply { this.alpha = (alpha * 255).roundToInt() }
        canvas.drawBitmap(bitmap2, 0f, 0f, paint)
        
        return result
    }

    /**
     * تكبير الصورة (Upscale 2x) - بسيط باستخدام Bilinear
     */
    fun upscaleBitmap(bitmap: Bitmap, scale: Int = 2): Bitmap {
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
