package com.lion.ai

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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

    private fun limitSize(src: Bitmap, maxSide: Int): Bitmap {
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
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
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
        val canvas = Canvas(out)
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
        "text" -> drawText(bitmap, op.text, op.value.toInt().let { if (it in 1..3) it else 3 }, op.color)
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

@Composable
private fun ToolChip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF2A2A2A),
            contentColor = Color(0xFFFFB800),
            disabledContainerColor = Color(0xFF1C1C1C),
            disabledContentColor = Color(0xFF6A6A6A)
        ),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditorScreen(
    initial: ImageAttachment,
    planEdit: suspend (String) -> String?,
    onBack: () -> Unit,
    onUse: (ImageAttachment) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<Bitmap?>(null) }
    val history = remember { mutableStateListOf<Bitmap>() }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var overlayText by remember { mutableStateOf("") }
    var overlayPos by remember { mutableStateOf(3) }
    var overlayColor by remember { mutableStateOf("white") }
    var instruction by remember { mutableStateOf("") }

    LaunchedEffect(initial) {
        val bitmap = withContext(Dispatchers.Default) { ImageOps.decode(initial) }
        history.clear()
        current = bitmap
        if (bitmap == null) status = "⚠️ تعذر فتح الصورة."
    }

    fun push(bitmap: Bitmap) {
        current?.let { history.add(it) }
        if (history.size > 8) history.removeAt(0)
        current = bitmap
    }

    fun apply(op: EditOp) {
        val bitmap = current ?: return
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { ImageOps.applyOp(bitmap, op) } }
            val output = result.getOrNull()
            if (output != null) {
                push(output)
                status = ""
            } else {
                status = "⚠️ تعذر تنفيذ التعديل: " + (result.exceptionOrNull()?.message ?: "خطأ")
            }
            busy = false
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
                status = "⚠️ تعذر الاتصال بالنموذج. تحقق من المفتاح والإنترنت، أو استخدم الأزرار."
                busy = false
                return@launch
            }
            val (ops, note) = ImageOps.parsePlan(raw)
            if (ops.isEmpty()) {
                status = "⚠️ " + (note ?: "لم أفهم التعديل. جرّب صياغة أبسط أو استخدم الأزرار.")
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
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFF111111)),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = current
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "الصورة",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    )
                } else {
                    CircularProgressIndicator(color = Color(0xFFFFB800))
                }
                if (busy) {
                    CircularProgressIndicator(color = Color(0xFFFFB800), modifier = Modifier.padding(8.dp))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 310.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                val ready = current != null && !busy

                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolChip("⟲ 90°", ready) { apply(EditOp("rotate", -90.0)) }
                    ToolChip("⟳ 90°", ready) { apply(EditOp("rotate", 90.0)) }
                    ToolChip("↔ قلب", ready) { apply(EditOp("flip_h")) }
                    ToolChip("↕ قلب", ready) { apply(EditOp("flip_v")) }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolChip("☀ أفتح", ready) { apply(EditOp("brightness", 15.0)) }
                    ToolChip("🌑 أغمق", ready) { apply(EditOp("brightness", -15.0)) }
                    ToolChip("◐ تباين +", ready) { apply(EditOp("contrast", 20.0)) }
                    ToolChip("◐ تباين −", ready) { apply(EditOp("contrast", -20.0)) }
                    ToolChip("🎨 ألوان +", ready) { apply(EditOp("saturation", 25.0)) }
                    ToolChip("🎨 ألوان −", ready) { apply(EditOp("saturation", -25.0)) }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolChip("⚫ أبيض وأسود", ready) { apply(EditOp("grayscale")) }
                    ToolChip("🟤 بني قديم", ready) { apply(EditOp("sepia")) }
                    ToolChip("◑ عكس", ready) { apply(EditOp("invert")) }
                    ToolChip("🌫 تمويه", ready) { apply(EditOp("blur", 8.0)) }
                    ToolChip("🔪 حدة", ready) { apply(EditOp("sharpen")) }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolChip("✂ 1:1", ready) { apply(EditOp("crop", text = "1:1")) }
                    ToolChip("✂ 4:3", ready) { apply(EditOp("crop", text = "4:3")) }
                    ToolChip("✂ 16:9", ready) { apply(EditOp("crop", text = "16:9")) }
                    ToolChip("✂ 9:16", ready) { apply(EditOp("crop", text = "9:16")) }
                    ToolChip("📏 1080", ready) { apply(EditOp("resize", 1080.0)) }
                    ToolChip("📏 720", ready) { apply(EditOp("resize", 720.0)) }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = overlayText,
                    onValueChange = { overlayText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("نص تكتبه على الصورة", color = Color(0xFF707070), fontSize = 13.sp) },
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
                    ToolChip((if (overlayPos == 1) "● " else "") + "أعلى") { overlayPos = 1 }
                    ToolChip((if (overlayPos == 2) "● " else "") + "وسط") { overlayPos = 2 }
                    ToolChip((if (overlayPos == 3) "● " else "") + "أسفل") { overlayPos = 3 }
                    ToolChip((if (overlayColor == "white") "● " else "") + "أبيض") { overlayColor = "white" }
                    ToolChip((if (overlayColor == "black") "● " else "") + "أسود") { overlayColor = "black" }
                    ToolChip((if (overlayColor == "yellow") "● " else "") + "أصفر") { overlayColor = "yellow" }
                    ToolChip("＋ أضف النص", ready && overlayText.isNotBlank()) {
                        apply(EditOp("text", overlayPos.toDouble(), overlayText.trim(), overlayColor))
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    placeholder = {
                        Text("✨ اكتب التعديل بالكلمات: مثل «أدر الصورة وفتّحها وحوّلها لأبيض وأسود»", color = Color(0xFF707070), fontSize = 12.sp)
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
                    ToolChip("↺ الأصل", current != null && !busy) {
                        scope.launch {
                            val bitmap = withContext(Dispatchers.Default) { ImageOps.decode(initial) }
                            history.clear()
                            current = bitmap
                            status = "تمت العودة للصورة الأصلية"
                        }
                    }
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
                }
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(status, color = Color(0xFFB0B0B0), fontSize = 12.sp)
                }
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}
