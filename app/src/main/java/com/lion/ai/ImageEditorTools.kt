package com.lion.ai

import android.graphics.*
import kotlin.math.roundToInt

object ImageEditorTools {

    enum class FilterType {
        NONE, GRAYSCALE, SEPIA, INVERT, VINTAGE, COLD, WARM, HIGH_CONTRAST
    }

    fun applyFilter(bitmap: Bitmap, filter: FilterType): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = Color.alpha(pixel)
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            var newR = r
            var newG = g
            var newB = b

            when (filter) {
                FilterType.GRAYSCALE -> {
                    val gray = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt()
                    newR = gray; newG = gray; newB = gray
                }
                FilterType.SEPIA -> {
                    newR = (0.393 * r + 0.769 * g + 0.189 * b).roundToInt().coerceIn(0, 255)
                    newG = (0.349 * r + 0.686 * g + 0.168 * b).roundToInt().coerceIn(0, 255)
                    newB = (0.272 * r + 0.534 * g + 0.131 * b).roundToInt().coerceIn(0, 255)
                }
                FilterType.INVERT -> {
                    newR = 255 - r; newG = 255 - g; newB = 255 - b
                }
                FilterType.VINTAGE -> {
                    newR = (r * 1.2).roundToInt().coerceIn(0, 255)
                    newG = (g * 1.0).roundToInt().coerceIn(0, 255)
                    newB = (b * 0.8).roundToInt().coerceIn(0, 255)
                }
                FilterType.COLD -> {
                    newR = (r * 0.9).roundToInt().coerceIn(0, 255)
                    newG = (g * 1.0).roundToInt().coerceIn(0, 255)
                    newB = (b * 1.2).roundToInt().coerceIn(0, 255)
                }
                FilterType.WARM -> {
                    newR = (r * 1.2).roundToInt().coerceIn(0, 255)
                    newG = (g * 1.1).roundToInt().coerceIn(0, 255)
                    newB = (b * 0.8).roundToInt().coerceIn(0, 255)
                }
                FilterType.HIGH_CONTRAST -> {
                    val factor = 1.5f
                    newR = (((r - 128) * factor + 128)).roundToInt().coerceIn(0, 255)
                    newG = (((g - 128) * factor + 128)).roundToInt().coerceIn(0, 255)
                    newB = (((b - 128) * factor + 128)).roundToInt().coerceIn(0, 255)
                }
                FilterType.NONE -> {}
            }
            pixels[i] = Color.argb(a, newR, newG, newB)
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    fun adjustBrightness(bitmap: Bitmap, value: Int): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val newR = (Color.red(pixel) + value).coerceIn(0, 255)
            val newG = (Color.green(pixel) + value).coerceIn(0, 255)
            val newB = (Color.blue(pixel) + value).coerceIn(0, 255)
            pixels[i] = Color.argb(Color.alpha(pixel), newR, newG, newB)
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    fun adjustContrast(bitmap: Bitmap, value: Int): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        val factor = (259 * (value + 255)) / (255 * (259 - value)).toFloat()

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val newR = (factor * (Color.red(pixel) - 128) + 128).roundToInt().coerceIn(0, 255)
            val newG = (factor * (Color.green(pixel) - 128) + 128).roundToInt().coerceIn(0, 255)
            val newB = (factor * (Color.blue(pixel) - 128) + 128).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(Color.alpha(pixel), newR, newG, newB)
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

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
            val newR = (gray + factor * (r - gray)).roundToInt().coerceIn(0, 255)
            val newG = (gray + factor * (g - gray)).roundToInt().coerceIn(0, 255)
            val newB = (gray + factor * (b - gray)).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(Color.alpha(pixel), newR, newG, newB)
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun flipHorizontal(bitmap: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.postScale(-1f, 1f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun flipVertical(bitmap: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.postScale(1f, -1f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun blendImages(bitmap1: Bitmap, bitmap2: Bitmap, alpha: Float = 0.5f): Bitmap {
        val width = maxOf(bitmap1.width, bitmap2.width)
        val height = maxOf(bitmap1.height, bitmap2.height)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        canvas.drawBitmap(bitmap1, 0f, 0f, null)
        val paint = Paint().apply { this.alpha = (alpha * 255).roundToInt() }
        canvas.drawBitmap(bitmap2, 0f, 0f, paint)
        return result
    }

    fun upscaleBitmap(bitmap: Bitmap, scale: Int = 2): Bitmap {
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
