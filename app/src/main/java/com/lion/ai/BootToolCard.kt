package com.lion.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val MAX_IMAGE_BYTES = BootImageTools.MAX_IMAGE_BYTES

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull().orEmpty().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "boot.img" }

private fun copyToUploads(context: Context, uri: Uri): String {
    val size = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        }
    }.getOrNull() ?: -1L
    require(size <= MAX_IMAGE_BYTES) {
        "الصورة أكبر من ${MAX_IMAGE_BYTES / (1024 * 1024)} ميجابايت؛ اختر صورة boot/recovery أصغر."
    }
    val dir = File(FileTools.root(context), "uploads").apply { mkdirs() }
    val file = File(dir, displayName(context, uri))
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_IMAGE_BYTES) {
                        "الصورة أكبر من ${MAX_IMAGE_BYTES / (1024 * 1024)} ميجابايت؛ اختر صورة boot/recovery أصغر."
                    }
                    out.write(buf, 0, n)
                }
            }
        } ?: error("تعذّر فتح الملف")
    } catch (e: Throwable) {
        file.delete()
        throw e
    }
    return "uploads/" + file.name
}

/** واجهة أداة boot/recovery: اختيار صورة ← تحليل ← فك ← (تعديل الملفات) ← تركيب ← تنزيل. */
@Composable
fun BootToolCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imagePath by remember { mutableStateOf("") }
    var unpackDir by remember { mutableStateOf("") }
    var outputPath by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun errorText(e: Throwable): String = when (e) {
        is OutOfMemoryError -> "❌ الذاكرة غير كافية لمعالجة الصورة. أغلق التطبيقات الأخرى أو استخدم صورة أصغر."
        else -> "❌ ${e.message ?: e.javaClass.simpleName}"
    }

    fun run(label: String, block: () -> String, onDone: (String) -> Unit = {}) {
        scope.launch {
            busy = true
            log = "⏳ $label…"
            val result = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: Throwable) {
                    errorText(e)
                }
            }
            log = result
            onDone(result)
            busy = false
        }
    }

    fun handlePicked(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            busy = true
            log = "⏳ نسخ الصورة إلى مساحة العمل…"
            val picked = withContext(Dispatchers.IO) {
                runCatching {
                    val path = copyToUploads(context, uri)
                    path to ("boot_unpacked/" + File(path).nameWithoutExtension)
                }
            }
            picked.onSuccess { (path, dir) ->
                imagePath = path
                unpackDir = dir
                outputPath = ""
                log = "✅ جاهزة: $path"
            }.onFailure { e ->
                log = errorText(e)
            }
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { handlePicked(it) }
    val fallbackPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { handlePicked(it) }

    fun launchPicker() {
        val opened = runCatching { picker.launch(arrayOf("*/*")) }
        if (opened.isFailure) {
            runCatching { fallbackPicker.launch("*/*") }.onFailure { e ->
                log = "❌ تعذّر فتح منتقي الملفات: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    val gold = Color(0xFFFFB800)
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF1A1A1A), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("📦 أداة صور Boot / Recovery", color = gold, fontWeight = FontWeight.Bold)
        Text(
            "فك boot.img أو recovery.img (ترويسة v0–v4)، عدّل cmdline أو ملفات ramdisk، ثم أعد التركيب. " +
                "الناتج غير موقّع (AVB) ويحتاج بوتلودر مفتوحاً؛ احتفظ بنسخة من الأصلية.",
            color = Color(0xFF9A9A9A), fontSize = 11.sp
        )
        Button(enabled = !busy, onClick = { launchPicker() }, colors = ButtonDefaults.buttonColors(containerColor = gold)) {
            Text("📂 اختيار صورة (.img)", color = Color(0xFF121212))
        }
        if (imagePath.isNotBlank()) Text("الصورة: $imagePath", color = Color(0xFFEDEDED), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && imagePath.isNotBlank(), onClick = {
                run("تحليل الترويسة") { BootImageTools.info(context, imagePath) }
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B2B2B))) { Text("🔍 تحليل", color = gold, fontSize = 12.sp) }
            Button(enabled = !busy && imagePath.isNotBlank(), onClick = {
                run("فك الصورة") { BootImageTools.unpack(context, imagePath, unpackDir) }
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B2B2B))) { Text("🧩 فك", color = gold, fontSize = 12.sp) }
            Button(enabled = !busy && unpackDir.isNotBlank(), onClick = {
                val target = "deliverables/" + File(unpackDir).name + "-new.img"
                run("تركيب الصورة", block = { BootImageTools.repack(context, unpackDir, target) }) {
                    outputPath = target
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))) { Text("🔧 تركيب", color = Color.White, fontSize = 12.sp) }
        }
        if (unpackDir.isNotBlank()) {
            Text(
                "مجلد الفك: $unpackDir — عدّل header.json (cmdline) أو ملفات ramdisk/ من تبويب الملفات أو الطرفية، ثم اضغط «تركيب».",
                color = Color(0xFF9A9A9A), fontSize = 11.sp
            )
        }
        if (log.isNotBlank()) {
            SelectionContainer {
                Text(
                    log,
                    color = if (log.startsWith("❌")) Color(0xFFFF9A9A) else Color(0xFFEDEDED),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF0B0B0B), RoundedCornerShape(10.dp)).padding(10.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
        if (outputPath.isNotBlank() && !busy) FileCard(outputPath, context)
        Spacer(Modifier.height(2.dp))
    }
}
