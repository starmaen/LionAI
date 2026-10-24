package com.lion.ai

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream

data class EditOp(
    val op: String,
    val value: Double = 0.0,
    val text: String = "",
    val color: String = "white"
)

/** عمليات تعديل الصور تعمل على الجهاز بالكامل بدون إنترنت. */
object ImageOps {
    private const val MAX_WORK_SIDE = 2048

    fun decode(image: ImageAttachment): Bitmap? {
        val bytes = try {
            Base64.decode(image.base64, Base64.DEFAULT)
        } catch (e: Exception) {
            return null
        }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val degrees = try {
            when (ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (degrees != 0f) bitmap = rotate(bitmap, degrees)
        return limitSize(bitmap, MAX_WORK_SIDE)
    }

    fun limitSize(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val m = Matrix()
        m.postRotate(degrees)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun flip(src: Bitmap, horizontal: Boolean): Bitmap {
        val m = Matrix()
        if (horizontal) m.postScale(-1f, 1f) else m.postScale(1f, -1f)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun filtered(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        AndroidCanvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    fun brightness(src: Bitmap, value: Float): Bitmap {
        val d = value.coerceIn(-100f, 100f) * 2.55f
        return filtered(
            src,
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, d,
                    0f, 1f, 0f, 0f, d,
                    0f, 0f, 1f, 0f, d,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    fun contrast(src: Bitmap, value: Float): Bitmap {
        val scale = ((100f + value.coerceIn(-100f, 100f)) / 100f).coerceIn(0.1f, 3f)
        val t = (-0.5f * scale + 0.5f) * 255f
        return filtered(
            src,
            ColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, t,
                    0f, scale, 0f, 0f, t,
                    0f, 0f, scale, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    fun saturation(src: Bitmap, value: Float): Bitmap {
        val matrix = ColorMatrix()
        matrix.setSaturation(((100f + value.coerceIn(-100f, 100f)) / 100f).coerceAtLeast(0f))
        return filtered(src, matrix)
    }

    fun grayscale(src: Bitmap): Bitmap {
        val matrix = ColorMatrix()
        matrix.setSaturation(0f)
        return filtered(src, matrix)
    }

    fun sepia(src: Bitmap): Bitmap = filtered(
        src,
        ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    fun invert(src: Bitmap): Bitmap = filtered(
        src,
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    fun blur(src: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 25)
        val factor = 1f / (1f + r * 0.5f)
        val w = (src.width * factor).toInt().coerceAtLeast(2)
        val h = (src.height * factor).toInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    fun sharpen(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = px.copyOf()
        val shifts = intArrayOf(16, 8, 0)
        fun ch(p: Int, shift: Int) = (p shr shift) and 0xFF
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                var result = px[i] and (0xFF shl 24)
                for (shift in shifts) {
                    val v = 5 * ch(px[i], shift) - ch(px[i - 1], shift) - ch(px[i + 1], shift) -
                        ch(px[i - w], shift) - ch(px[i + w], shift)
                    result = result or (v.coerceIn(0, 255) shl shift)
                }
                out[i] = result
            }
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(out, 0, w, 0, 0, w, h)
        return bitmap
    }

    fun cropRatio(src: Bitmap, ratio: String): Bitmap {
        val parts = ratio.split(":", "x", "×", "/")
        val rw = parts.getOrNull(0)?.trim()?.toFloatOrNull() ?: return src
        val rh = parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: return src
        if (rw <= 0f || rh <= 0f) return src
        val target = rw / rh
        val current = src.width.toFloat() / src.height
        var cw = src.width
        var ch = src.height
        if (current > target) cw = (src.height * target).toInt() else ch = (src.width / target).toInt()
        cw = cw.coerceIn(1, src.width)
        ch = ch.coerceIn(1, src.height)
        return Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
    }

    fun resize(src: Bitmap, maxSide: Int): Bitmap = limitSize(src, maxSide.coerceIn(64, MAX_WORK_SIDE))

    fun drawText(src: Bitmap, text: String, position: Int, colorName: String): Bitmap {
        if (text.isBlank()) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = AndroidCanvas(out)
        val size = minOf(out.width / 10f, out.width * 0.9f / text.length * 1.8f).coerceAtLeast(18f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = size
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.color = when (colorName.lowercase()) {
            "black" -> android.graphics.Color.BLACK
            "yellow" -> android.graphics.Color.YELLOW
            "red" -> android.graphics.Color.RED
            else -> android.graphics.Color.WHITE
        }
        paint.setShadowLayer(size / 8f, 0f, 0f, android.graphics.Color.argb(200, 0, 0, 0))
        val y = when (position) {
            1 -> size * 1.4f
            2 -> out.height / 2f + size / 3f
            else -> out.height - size * 0.8f
        }
        canvas.drawText(text, out.width / 2f, y, paint)
        return out
    }


    /** تمويه بقطر بالبكسل (للنسب الكبيرة التي تتجاوز حد blur العادي). */
    fun blurPixels(src: Bitmap, px: Float): Bitmap {
        if (px < 0.5f) return src
        val factor = 1f / (1f + px / 2f)
        val w = (src.width * factor).toInt().coerceAtLeast(2)
        val h = (src.height * factor).toInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    /** قص بنسب الحواف (0..1) من كل جهة. */
    fun cropInsets(src: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val x = (src.width * left).toInt().coerceIn(0, src.width - 2)
        val y = (src.height * top).toInt().coerceIn(0, src.height - 2)
        val w = (src.width * (1f - left - right)).toInt().coerceIn(1, src.width - x)
        val h = (src.height * (1f - top - bottom)).toInt().coerceIn(1, src.height - y)
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    fun scaleBy(src: Bitmap, factor: Float): Bitmap {
        val f = factor.coerceIn(0.05f, 4f)
        val w = (src.width * f).toInt().coerceAtLeast(1)
        val h = (src.height * f).toInt().coerceAtLeast(1)
        return limitSize(Bitmap.createScaledBitmap(src, w, h, true), MAX_WORK_SIDE)
    }

    fun colorOf(name: String): Int = when (name.lowercase()) {
        "black" -> android.graphics.Color.BLACK
        "yellow" -> android.graphics.Color.YELLOW
        "red" -> android.graphics.Color.RED
        "blue" -> android.graphics.Color.rgb(33, 150, 243)
        "green" -> android.graphics.Color.rgb(76, 175, 80)
        else -> android.graphics.Color.WHITE
    }

    /** نص متعدد الأسطر بإحداثيات معيارية (x وy من 0 إلى 1) وحجم كنسبة من عرض الصورة. */
    fun drawTextAt(src: Bitmap, text: String, x: Float, y: Float, sizeFrac: Float, color: Int, bold: Boolean): Bitmap {
        if (text.isBlank()) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = AndroidCanvas(out)
        val fontSize = (sizeFrac * out.width).coerceAtLeast(10f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = fontSize
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER
        paint.color = color
        paint.setShadowLayer(fontSize * 0.12f, 0f, fontSize * 0.04f, android.graphics.Color.argb(115, 0, 0, 0))
        val lines = text.split("\n")
        val lineHeight = fontSize * 1.25f
        val startY = y * out.height - (lines.size - 1) * lineHeight / 2f
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, x * out.width, startY + i * lineHeight + fontSize / 3f, paint)
        }
        return out
    }

    fun drawStrokes(src: Bitmap, strokes: List<BrushStroke>): Bitmap {
        if (strokes.isEmpty()) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = AndroidCanvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            paint.color = stroke.color
            paint.strokeWidth = (stroke.width * out.width).coerceAtLeast(1f)
            val path = android.graphics.Path()
            val first = stroke.points.first()
            path.moveTo(first.first * out.width, first.second * out.height)
            if (stroke.points.size == 1) {
                path.lineTo(first.first * out.width + 0.01f, first.second * out.height)
            }
            stroke.points.drop(1).forEach { path.lineTo(it.first * out.width, it.second * out.height) }
            canvas.drawPath(path, paint)
        }
        return out
    }

    fun applyOp(bitmap: Bitmap, op: EditOp): Bitmap = when (op.op.lowercase()) {
        "rotate" -> rotate(bitmap, op.value.toFloat())
        "flip_h" -> flip(bitmap, true)
        "flip_v" -> flip(bitmap, false)
        "brightness" -> brightness(bitmap, op.value.toFloat())
        "contrast" -> contrast(bitmap, op.value.toFloat())
        "saturation" -> saturation(bitmap, op.value.toFloat())
        "grayscale" -> grayscale(bitmap)
        "sepia" -> sepia(bitmap)
        "invert" -> invert(bitmap)
        "blur" -> blur(bitmap, op.value.toInt().let { if (it <= 0) 8 else it })
        "sharpen" -> sharpen(bitmap)
        "crop" -> cropRatio(bitmap, op.text)
        "resize" -> resize(bitmap, op.value.toInt().let { if (it <= 0) 1080 else it })
        "text" -> drawTextAt(
            bitmap, op.text, 0.5f,
            when (op.value.toInt()) { 1 -> 0.12f; 2 -> 0.5f; else -> 0.88f },
            0.08f, colorOf(op.color), true
        )
        "preset" -> PRESETS.firstOrNull { it.id == op.text.lowercase() }
            ?.let { AdjustFilter.apply(bitmap, it.adj) } ?: bitmap
        "auto" -> AdjustFilter.apply(bitmap, AdjustFilter.autoEnhance(bitmap))
        else -> bitmap
    }
    /** يحلل رد النموذج: (العمليات، ملاحظة). */
    fun parsePlan(raw: String): Pair<List<EditOp>, String?> {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return Pair(emptyList(), "تعذر فهم رد النموذج.")
        return try {
            val json = JSONObject(raw.substring(s, e + 1))
            val array = json.optJSONArray("ops")
            val ops = mutableListOf<EditOp>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val name = item.optString("op", "")
                    if (name.isBlank()) continue
                    ops.add(
                        EditOp(
                            op = name,
                            value = item.optDouble("value", 0.0).let { if (it.isNaN()) 0.0 else it },
                            text = item.optString("text", ""),
                            color = item.optString("color", "white").ifBlank { "white" }
                        )
                    )
                }
            }
            Pair(ops.take(12), json.optString("note", "").ifBlank { null })
        } catch (ex: Exception) {
            Pair(emptyList(), "تعذر فهم رد النموذج.")
        }
    }

    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "LionAI_" + System.currentTimeMillis() + ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LionAI")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = context.contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
        } ?: false
        return if (ok) uri else null
    }
}


data class BrushStroke(val color: Int, val width: Float, val points: List<Pair<Float, Float>>)

/** نفس منطق مرشحات CSS في مشروع محرر الصور (100 = طبيعي). */
data class Adjustments(
    val brightness: Float = 100f,
    val contrast: Float = 100f,
    val saturation: Float = 100f,
    val hue: Float = 0f,
    val blur: Float = 0f,
    val sepia: Float = 0f,
    val grayscale: Float = 0f,
    val invert: Float = 0f
)

data class Preset(val id: String, val name: String, val icon: String, val adj: Adjustments)

val PRESETS = listOf(
    Preset("original", "أصلي", "🖼️", Adjustments()),
    Preset("vivid", "حيوي", "🌈", Adjustments(brightness = 105f, contrast = 112f, saturation = 145f)),
    Preset("bw", "أبيض وأسود", "⚫", Adjustments(brightness = 102f, contrast = 118f, grayscale = 100f)),
    Preset("sepia", "سيبيا", "📜", Adjustments(brightness = 106f, contrast = 105f, sepia = 85f)),
    Preset("warm", "دافئ", "🌅", Adjustments(brightness = 106f, contrast = 104f, saturation = 118f, hue = -12f)),
    Preset("cool", "بارد", "❄️", Adjustments(brightness = 103f, contrast = 106f, saturation = 112f, hue = 14f)),
    Preset("drama", "دراما", "🎭", Adjustments(brightness = 94f, contrast = 142f, saturation = 88f)),
    Preset("fade", "هادئ", "🌫️", Adjustments(brightness = 110f, contrast = 88f, saturation = 82f)),
    Preset("noir", "نوار", "🎬", Adjustments(brightness = 92f, contrast = 155f, grayscale = 100f)),
    Preset("dream", "حالم", "💭", Adjustments(brightness = 108f, contrast = 92f, saturation = 115f, hue = 8f, blur = 0.6f))
)

object AdjustFilter {
    private fun m(vararg v: Float) = ColorMatrix(floatArrayOf(*v))

    fun matrix(a: Adjustments): ColorMatrix {
        val result = ColorMatrix()
        val b = a.brightness / 100f
        result.postConcat(m(b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        val c = a.contrast / 100f
        val t = (-0.5f * c + 0.5f) * 255f
        result.postConcat(m(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
        val sat = ColorMatrix()
        sat.setSaturation((a.saturation / 100f).coerceAtLeast(0f))
        result.postConcat(sat)
        if (a.hue != 0f) {
            val rad = Math.toRadians(a.hue.toDouble())
            val cs = Math.cos(rad).toFloat()
            val sn = Math.sin(rad).toFloat()
            result.postConcat(
                m(
                    0.213f + cs * 0.787f - sn * 0.213f, 0.715f - cs * 0.715f - sn * 0.715f, 0.072f - cs * 0.072f + sn * 0.928f, 0f, 0f,
                    0.213f - cs * 0.213f + sn * 0.143f, 0.715f + cs * 0.285f + sn * 0.140f, 0.072f - cs * 0.072f - sn * 0.283f, 0f, 0f,
                    0.213f - cs * 0.213f - sn * 0.787f, 0.715f - cs * 0.715f + sn * 0.715f, 0.072f + cs * 0.928f + sn * 0.072f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        if (a.sepia > 0f) {
            val q = 1f - (a.sepia / 100f).coerceIn(0f, 1f)
            result.postConcat(
                m(
                    0.393f + 0.607f * q, 0.769f - 0.769f * q, 0.189f - 0.189f * q, 0f, 0f,
                    0.349f - 0.349f * q, 0.686f + 0.314f * q, 0.168f - 0.168f * q, 0f, 0f,
                    0.272f - 0.272f * q, 0.534f - 0.534f * q, 0.131f + 0.869f * q, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        if (a.grayscale > 0f) {
            val q = 1f - (a.grayscale / 100f).coerceIn(0f, 1f)
            result.postConcat(
                m(
                    0.2126f + 0.7874f * q, 0.7152f - 0.7152f * q, 0.0722f - 0.0722f * q, 0f, 0f,
                    0.2126f - 0.2126f * q, 0.7152f + 0.2848f * q, 0.0722f - 0.0722f * q, 0f, 0f,
                    0.2126f - 0.2126f * q, 0.7152f - 0.7152f * q, 0.0722f + 0.9278f * q, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        if (a.invert > 0f) {
            val p = (a.invert / 100f).coerceIn(0f, 1f)
            val d = 1f - 2f * p
            val tr = 255f * p
            result.postConcat(m(d, 0f, 0f, 0f, tr, 0f, d, 0f, 0f, tr, 0f, 0f, d, 0f, tr, 0f, 0f, 0f, 1f, 0f))
        }
        return result
    }

    fun apply(src: Bitmap, a: Adjustments): Bitmap {
        var out = src
        if (a.copy(blur = 0f) != Adjustments()) {
            val colored = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.colorFilter = ColorMatrixColorFilter(matrix(a))
            AndroidCanvas(colored).drawBitmap(src, 0f, 0f, paint)
            out = colored
        }
        if (a.blur > 0f) out = ImageOps.blurPixels(out, a.blur * out.width / 100f)
        return out
    }

    /** التحسين الذكي: يحلل متوسط الإضاءة والتباين والتشبع ويقترح تعديلات (نفس خوارزمية المشروع). */
    fun autoEnhance(src: Bitmap): Adjustments {
        val small = ImageOps.limitSize(src, 160)
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        var lumaSum = 0.0
        var lumaSq = 0.0
        var satSum = 0.0
        for (p in px) {
            val r = ((p shr 16) and 0xFF) / 255.0
            val g = ((p shr 8) and 0xFF) / 255.0
            val b = (p and 0xFF) / 255.0
            val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
            lumaSum += luma
            lumaSq += luma * luma
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            satSum += if (mx == 0.0) 0.0 else (mx - mn) / mx
        }
        val n = maxOf(1, px.size)
        val mean = lumaSum / n
        val std = Math.sqrt(maxOf(0.0, lumaSq / n - mean * mean))
        val sat = satSum / n
        val brightness = (105.0 * (0.56 / maxOf(mean, 0.08))).coerceIn(75.0, 165.0)
        val contrast = when {
            std < 0.16 -> 128.0
            std < 0.22 -> 116.0
            std > 0.32 -> 96.0
            else -> 108.0
        }
        val saturation = (100.0 + (0.24 - sat) * 130.0).coerceIn(92.0, 145.0)
        return Adjustments(
            brightness = Math.round(brightness).toFloat(),
            contrast = contrast.toFloat(),
            saturation = Math.round(saturation).toFloat()
        )
    }
}

@Composable
private fun ToolChip(label: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFFFFB800) else Color(0xFF2A2A2A),
            contentColor = if (selected) Color(0xFF0D0D0D) else Color(0xFFFFB800),
            disabledContainerColor = Color(0xFF1C1C1C),
            disabledContentColor = Color(0xFF6A6A6A)
        ),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(76.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFFB800),
                activeTrackColor = Color(0xFFFFB800),
                inactiveTrackColor = Color(0xFF3A3A3A)
            )
        )
        Text(
            (if (range.endInclusive <= 10f) String.format("%.1f", value) else value.toInt().toString()) + suffix,
            color = Color(0xFFB0B0B0),
            fontSize = 11.sp,
            modifier = Modifier.width(54.dp)
        )
    }
}

private class FitRect(val left: Float, val top: Float, val w: Float, val h: Float)

private fun fitRect(bw: Int, bh: Int, boxW: Float, boxH: Float): FitRect {
    if (bw <= 0 || bh <= 0 || boxW <= 0f || boxH <= 0f) return FitRect(0f, 0f, 1f, 1f)
    val scale = minOf(boxW / bw, boxH / bh)
    val w = bw * scale
    val h = bh * scale
    return FitRect((boxW - w) / 2f, (boxH - h) / 2f, w, h)
}

private val TOOLS = listOf(
    "adjust" to "🎛️ تعديلات",
    "presets" to "🎨 مرشحات",
    "transform" to "🔄 تحويل",
    "crop" to "✂️ قص",
    "resize" to "📐 الحجم",
    "text" to "🔤 نص",
    "brush" to "🖌️ فرشاة",
    "words" to "✨ بالكلمات"
)

private val PALETTE = listOf(
    "white" to "أبيض", "black" to "أسود", "yellow" to "أصفر",
    "red" to "أحمر", "blue" to "أزرق", "green" to "أخضر"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditorScreen(
    initial: ImageAttachment,
    planEdit: suspend (String) -> String?,
    onBack: () -> Unit,
    onUse: (ImageAttachment) -> Unit,
    autoInstruction: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<Bitmap?>(null) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val history = remember { mutableStateListOf<Bitmap>() }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf("adjust") }
    var adj by remember { mutableStateOf(Adjustments()) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    var cropL by remember { mutableStateOf(0f) }
    var cropT by remember { mutableStateOf(0f) }
    var cropR by remember { mutableStateOf(0f) }
    var cropB by remember { mutableStateOf(0f) }
    var cropAspect by remember { mutableStateOf("حر") }

    var overlayText by remember { mutableStateOf("") }
    var textX by remember { mutableStateOf(0.5f) }
    var textY by remember { mutableStateOf(0.85f) }
    var textSize by remember { mutableStateOf(0.08f) }
    var textBold by remember { mutableStateOf(true) }
    var textColor by remember { mutableStateOf("white") }

    val strokes = remember { mutableStateListOf<BrushStroke>() }
    var livePoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var brushColor by remember { mutableStateOf("red") }
    var brushWidth by remember { mutableStateOf(0.015f) }
    var instruction by remember { mutableStateOf("") }

    LaunchedEffect(initial) {
        val bitmap = withContext(Dispatchers.Default) { ImageOps.decode(initial) }
        history.clear()
        current = bitmap
        original = bitmap
        if (bitmap == null) status = "⚠️ تعذر فتح الصورة."
    }

    // معاينة حية للتعديلات على نسخة مصغّرة
    LaunchedEffect(adj, current) {
        val base = current
        if (base == null || adj == Adjustments()) {
            preview = null
            return@LaunchedEffect
        }
        delay(60)
        preview = withContext(Dispatchers.Default) {
            runCatching { AdjustFilter.apply(ImageOps.limitSize(base, 900), adj) }.getOrNull()
        }
    }

    fun resetPending() {
        adj = Adjustments()
        preview = null
        cropL = 0f; cropT = 0f; cropR = 0f; cropB = 0f
        cropAspect = "حر"
        strokes.clear()
        livePoints = emptyList()
    }

    fun push(bitmap: Bitmap) {
        current?.let { history.add(it) }
        if (history.size > 8) history.removeAt(0)
        current = bitmap
        resetPending()
    }

    fun runBackground(label: String? = null, block: (Bitmap) -> Bitmap) {
        val bitmap = current ?: return
        if (busy) return
        busy = true
        if (label != null) status = label
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { block(bitmap) } }
            val output = result.getOrNull()
            if (output != null) {
                push(output)
                status = ""
            } else {
                status = "⚠️ تعذر التنفيذ: " + (result.exceptionOrNull()?.message ?: "خطأ")
            }
            busy = false
        }
    }

    fun apply(op: EditOp) = runBackground { ImageOps.applyOp(it, op) }

    fun commitAdjust() {
        val a = adj
        runBackground("⏳ أطبّق التعديلات...") { AdjustFilter.apply(it, a) }
    }

    fun commitCrop() {
        val l = cropL; val t = cropT; val r = cropR; val b = cropB
        runBackground { ImageOps.cropInsets(it, l, t, r, b) }
    }

    fun commitStrokes() {
        val list = strokes.toList()
        runBackground { ImageOps.drawStrokes(it, list) }
    }

    fun setAspect(label: String, ratio: Float?) {
        cropAspect = label
        val bmp = current ?: return
        if (ratio == null) {
            cropL = 0f; cropT = 0f; cropR = 0f; cropB = 0f
            return
        }
        val bitmapRatio = bmp.width.toFloat() / bmp.height
        if (bitmapRatio > ratio) {
            val keep = ratio / bitmapRatio
            val inset = (1f - keep) / 2f
            cropL = inset; cropR = inset; cropT = 0f; cropB = 0f
        } else {
            val keep = bitmapRatio / ratio
            val inset = (1f - keep) / 2f
            cropT = inset; cropB = inset; cropL = 0f; cropR = 0f
        }
    }

    fun runInstruction() {
        val request = instruction.trim()
        val bitmap = current ?: return
        if (request.isEmpty() || busy) return
        busy = true
        status = "⏳ أحلل طلبك..."
        scope.launch {
            val raw = planEdit(request)
            if (raw == null) {
                status = "⚠️ تعذر الاتصال بالنموذج. تحقق من المفتاح والإنترنت، أو استخدم الأدوات."
                busy = false
                return@launch
            }
            val (ops, note) = ImageOps.parsePlan(raw)
            if (ops.isEmpty()) {
                status = "⚠️ " + (note ?: "لم أفهم التعديل. جرّب صياغة أبسط أو استخدم الأدوات.")
                busy = false
                return@launch
            }
            val result = withContext(Dispatchers.Default) {
                runCatching { ops.fold(bitmap) { acc, op -> ImageOps.applyOp(acc, op) } }
            }
            val output = result.getOrNull()
            if (output != null) {
                push(output)
                instruction = ""
                status = "✅ تم تنفيذ ${ops.size} تعديل" + (note?.let { " — $it" } ?: "")
            } else {
                status = "⚠️ تعذر التنفيذ: " + (result.exceptionOrNull()?.message ?: "خطأ")
            }
            busy = false
        }
    }

    fun saveAndShare(share: Boolean) {
        val bitmap = current ?: return
        scope.launch {
            val uri = withContext(Dispatchers.IO) { runCatching { ImageOps.saveToGallery(context, bitmap) }.getOrNull() }
            if (uri == null) {
                status = "⚠️ تعذر الحفظ في المعرض (قد تحتاج إلى صلاحية التخزين)."
            } else {
                status = "✅ حُفظت الصورة في المعرض ← Pictures/LionAI"
                if (share) {
                    runCatching {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "مشاركة الصورة"))
                    }
                }
            }
        }
    }

    // طلب قادم من المحادثة (مثل «فتّح الصورة وحوّلها لأبيض وأسود») يُنفَّذ تلقائياً عند فتح المحرر
    var autoRan by remember { mutableStateOf(false) }
    LaunchedEffect(current) {
        if (!autoRan && current != null && !autoInstruction.isNullOrBlank()) {
            autoRan = true
            tool = "words"
            instruction = autoInstruction
            runInstruction()
        }
    }

    Scaffold(
        containerColor = Color(0xFF0D0D0D),
        topBar = {
            TopAppBar(
                title = { Text("🖼️ محرر الصور", color = Color(0xFFFFB800)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "رجوع", tint = Color(0xFFFFB800))
                    }
                },
                actions = {
                    IconButton(
                        enabled = history.isNotEmpty() && !busy,
                        onClick = {
                            current = history.removeAt(history.size - 1)
                            resetPending()
                            status = ""
                        }
                    ) {
                        Icon(
                            Icons.Filled.Undo, "تراجع",
                            tint = if (history.isNotEmpty()) Color(0xFFFFB800) else Color(0xFF555555)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            val shown: Bitmap? = if (showOriginal) original else (preview ?: current)
            val fit = if (shown != null) {
                fitRect(shown.width, shown.height, boxSize.width.toFloat(), boxSize.height.toFloat())
            } else {
                FitRect(0f, 0f, 1f, 1f)
            }
            val fitState = rememberUpdatedState(fit)
            val brushColorInt = ImageOps.colorOf(brushColor)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF111111))
                    .onSizeChanged { boxSize = it }
                    .pointerInput(tool) {
                        if (tool == "brush") {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val f = fitState.value
                                    livePoints = listOf(
                                        Pair(
                                            ((offset.x - f.left) / f.w).coerceIn(0f, 1f),
                                            ((offset.y - f.top) / f.h).coerceIn(0f, 1f)
                                        )
                                    )
                                },
                                onDrag = { change, _ ->
                                    val f = fitState.value
                                    livePoints = livePoints + Pair(
                                        ((change.position.x - f.left) / f.w).coerceIn(0f, 1f),
                                        ((change.position.y - f.top) / f.h).coerceIn(0f, 1f)
                                    )
                                },
                                onDragEnd = {
                                    if (livePoints.isNotEmpty()) {
                                        strokes.add(BrushStroke(brushColorInt, brushWidth, livePoints))
                                    }
                                    livePoints = emptyList()
                                },
                                onDragCancel = { livePoints = emptyList() }
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = "الصورة",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (tool == "crop" && !showOriginal) {
                            val x0 = fit.left + cropL * fit.w
                            val y0 = fit.top + cropT * fit.h
                            val x1 = fit.left + (1f - cropR) * fit.w
                            val y1 = fit.top + (1f - cropB) * fit.h
                            val dim = Color(0xAA000000)
                            drawRect(dim, Offset(fit.left, fit.top), Size(fit.w, y0 - fit.top))
                            drawRect(dim, Offset(fit.left, y1), Size(fit.w, fit.top + fit.h - y1))
                            drawRect(dim, Offset(fit.left, y0), Size(x0 - fit.left, y1 - y0))
                            drawRect(dim, Offset(x1, y0), Size(fit.left + fit.w - x1, y1 - y0))
                            val line = Color(0xFFFFB800)
                            drawLine(line, Offset(x0, y0), Offset(x1, y0), 3f)
                            drawLine(line, Offset(x1, y0), Offset(x1, y1), 3f)
                            drawLine(line, Offset(x1, y1), Offset(x0, y1), 3f)
                            drawLine(line, Offset(x0, y1), Offset(x0, y0), 3f)
                        }
                        val all = strokes.toList() + (if (livePoints.isNotEmpty()) {
                            listOf(BrushStroke(brushColorInt, brushWidth, livePoints))
                        } else {
                            emptyList()
                        })
                        all.forEach { stroke ->
                            val c = Color(stroke.color)
                            val widthPx = (stroke.width * fit.w).coerceAtLeast(2f)
                            val pts = stroke.points.map { Offset(fit.left + it.first * fit.w, fit.top + it.second * fit.h) }
                            if (pts.size == 1) {
                                drawCircle(c, widthPx / 2f, pts[0])
                            } else {
                                for (i in 1 until pts.size) {
                                    drawLine(c, pts[i - 1], pts[i], widthPx, StrokeCap.Round)
                                }
                            }
                        }
                    }
                } else {
                    CircularProgressIndicator(color = Color(0xFFFFB800))
                }
                if (busy) {
                    CircularProgressIndicator(color = Color(0xFFFFB800), modifier = Modifier.padding(8.dp))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TOOLS.forEach { (id, label) ->
                    ToolChip(label, selected = tool == id) { tool = id }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 250.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                val ready = current != null && !busy
                val hasAdjust = adj != Adjustments()
                val bmp = current

                when (tool) {
                    "adjust" -> {
                        SliderRow("السطوع", adj.brightness, 20f..200f, "%") { adj = adj.copy(brightness = it) }
                        SliderRow("التباين", adj.contrast, 20f..200f, "%") { adj = adj.copy(contrast = it) }
                        SliderRow("التشبع", adj.saturation, 0f..200f, "%") { adj = adj.copy(saturation = it) }
                        SliderRow("درجة اللون", adj.hue, -180f..180f, "°") { adj = adj.copy(hue = it) }
                        SliderRow("الضبابية", adj.blur, 0f..8f, "%") { adj = adj.copy(blur = it) }
                        SliderRow("سيبيا", adj.sepia, 0f..100f, "%") { adj = adj.copy(sepia = it) }
                        SliderRow("رمادي", adj.grayscale, 0f..100f, "%") { adj = adj.copy(grayscale = it) }
                        SliderRow("عكس", adj.invert, 0f..100f, "%") { adj = adj.copy(invert = it) }
                    }

                    "presets" -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PRESETS.forEach { preset ->
                                ToolChip(preset.icon + " " + preset.name, ready) { adj = preset.adj }
                            }
                        }
                        Text(
                            "اختر مرشحاً لتراه مباشرة، ثم اضغط «✔ تطبيق» أو عدّله من تبويب التعديلات.",
                            color = Color(0xFF9A9A9A), fontSize = 11.sp
                        )
                    }

                    "transform" -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToolChip("⟲ 90°", ready) { apply(EditOp("rotate", -90.0)) }
                            ToolChip("⟳ 90°", ready) { apply(EditOp("rotate", 90.0)) }
                            ToolChip("↔ قلب أفقي", ready) { apply(EditOp("flip_h")) }
                            ToolChip("↕ قلب رأسي", ready) { apply(EditOp("flip_v")) }
                            ToolChip("🔪 حدة", ready) { apply(EditOp("sharpen")) }
                        }
                    }

                    "crop" -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("حر" to null, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)
                                .forEach { (label, ratio) ->
                                    ToolChip(label, ready, selected = cropAspect == label) { setAspect(label, ratio) }
                                }
                        }
                        SliderRow("يسار", cropL, 0f..0.9f, "") { cropL = minOf(it, 0.95f - cropR); cropAspect = "حر" }
                        SliderRow("أعلى", cropT, 0f..0.9f, "") { cropT = minOf(it, 0.95f - cropB); cropAspect = "حر" }
                        SliderRow("يمين", cropR, 0f..0.9f, "") { cropR = minOf(it, 0.95f - cropL); cropAspect = "حر" }
                        SliderRow("أسفل", cropB, 0f..0.9f, "") { cropB = minOf(it, 0.95f - cropT); cropAspect = "حر" }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToolChip("✔ تطبيق القص", ready && (cropL + cropT + cropR + cropB) > 0.001f) { commitCrop() }
                            ToolChip("↺ إعادة", ready) { setAspect("حر", null) }
                        }
                    }

                    "resize" -> {
                        Text(
                            if (bmp != null) "الحجم الحالي: ${bmp.width} × ${bmp.height} بكسل" else "",
                            color = Color(0xFFB0B0B0), fontSize = 12.sp
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToolChip("25%", ready) { runBackground { ImageOps.scaleBy(it, 0.25f) } }
                            ToolChip("50%", ready) { runBackground { ImageOps.scaleBy(it, 0.5f) } }
                            ToolChip("75%", ready) { runBackground { ImageOps.scaleBy(it, 0.75f) } }
                            ToolChip("150%", ready) { runBackground { ImageOps.scaleBy(it, 1.5f) } }
                            ToolChip("📏 1080", ready) { apply(EditOp("resize", 1080.0)) }
                            ToolChip("📏 720", ready) { apply(EditOp("resize", 720.0)) }
                            ToolChip("📏 512", ready) { apply(EditOp("resize", 512.0)) }
                        }
                    }

                    "text" -> {
                        OutlinedTextField(
                            value = overlayText,
                            onValueChange = { overlayText = it },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            placeholder = { Text("النص (يدعم عدة أسطر)", color = Color(0xFF707070), fontSize = 13.sp) },
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFFB800),
                                unfocusedBorderColor = Color(0xFF2A2A2A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFFFFB800)
                            )
                        )
                        SliderRow("الحجم", textSize, 0.03f..0.2f, "") { textSize = it }
                        SliderRow("أفقي", textX, 0f..1f, "") { textX = it }
                        SliderRow("رأسي", textY, 0f..1f, "") { textY = it }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PALETTE.forEach { (id, label) ->
                                ToolChip((if (textColor == id) "● " else "") + label) { textColor = id }
                            }
                            ToolChip((if (textBold) "● " else "") + "عريض") { textBold = !textBold }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToolChip("＋ أضف النص", ready && overlayText.isNotBlank()) {
                                val t = overlayText.trim(); val x = textX; val y = textY
                                val size = textSize; val col = ImageOps.colorOf(textColor); val bold = textBold
                                runBackground { ImageOps.drawTextAt(it, t, x, y, size, col, bold) }
                            }
                        }
                    }

                    "brush" -> {
                        Text("ارسم بإصبعك على الصورة، ثم اضغط «✔ تثبيت».", color = Color(0xFF9A9A9A), fontSize = 11.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PALETTE.forEach { (id, label) ->
                                ToolChip((if (brushColor == id) "● " else "") + label) { brushColor = id }
                            }
                        }
                        SliderRow("السماكة", brushWidth, 0.004f..0.06f, "") { brushWidth = it }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToolChip("↩ تراجع خط", strokes.isNotEmpty()) { strokes.removeAt(strokes.size - 1) }
                            ToolChip("🗑 مسح", strokes.isNotEmpty()) { strokes.clear() }
                            ToolChip("✔ تثبيت", ready && strokes.isNotEmpty()) { commitStrokes() }
                        }
                    }

                    else -> {
                        OutlinedTextField(
                            value = instruction,
                            onValueChange = { instruction = it },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            placeholder = {
                                Text("اكتب التعديل: مثل «حسّن الصورة وفتّحها وحوّلها لأبيض وأسود»", color = Color(0xFF707070), fontSize = 12.sp)
                            },
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFFB800),
                                unfocusedBorderColor = Color(0xFF2A2A2A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFFFFB800)
                            )
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToolChip("✨ نفّذ", ready && instruction.isNotBlank()) { runInstruction() }
                        }
                    }
                }

                if (tool == "adjust" || tool == "presets") {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ToolChip("✨ تحسين ذكي تلقائي", ready) {
                            val b = current
                            if (b != null) {
                                scope.launch {
                                    adj = withContext(Dispatchers.Default) { AdjustFilter.autoEnhance(b) }
                                }
                            }
                        }
                        ToolChip("✔ تطبيق", ready && hasAdjust) { commitAdjust() }
                        ToolChip("↺ تصفير", hasAdjust) { adj = Adjustments(); preview = null }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val ready = current != null && !busy
                ToolChip((if (showOriginal) "● " else "") + "👁 الأصل", current != null) { showOriginal = !showOriginal }
                ToolChip("💾 حفظ", ready) { saveAndShare(false) }
                ToolChip("📤 مشاركة", ready) { saveAndShare(true) }
                ToolChip("🔍 حلّل بالذكاء", ready) {
                    val bitmap = current
                    if (bitmap != null) {
                        scope.launch {
                            val attachment = withContext(Dispatchers.Default) {
                                ImageCodec.toAttachment(bitmap, "edited.jpg")
                            }
                            if (attachment != null) onUse(attachment) else status = "⚠️ الصورة كبيرة جداً للإرسال."
                        }
                    }
                }
                ToolChip("↺ الأصلية", ready) {
                    scope.launch {
                        val bitmap = withContext(Dispatchers.Default) { ImageOps.decode(initial) }
                        history.clear()
                        current = bitmap
                        resetPending()
                        status = "تمت العودة للصورة الأصلية"
                    }
                }
            }
            if (status.isNotBlank()) {
                Text(
                    status,
                    color = Color(0xFFB0B0B0),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
        }
    }
}
