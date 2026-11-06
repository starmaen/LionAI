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

private const val FS_MAX_BYTES = 3L * 1024 * 1024 * 1024

private fun fsDisplayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull().orEmpty().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "vendor.img" }

private fun fsCopyToUploads(context: Context, uri: Uri): String {
    val dir = File(FileTools.root(context), "uploads").apply { mkdirs() }
    val file = File(dir, fsDisplayName(context, uri))
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    require(total <= FS_MAX_BYTES) { "الصورة أكبر من 3 جيجابايت" }
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

/** واجهة صور system / vendor / product: اختيار ← تحليل ← فك ← تعديل ← تركيب ← تنزيل. */
@Composable
fun FsImageToolCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imagePath by remember { mutableStateOf("") }
    var unpackDir by remember { mutableStateOf("") }
    var outputPath by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun errorText(e: Throwable): String = when (e) {
        is OutOfMemoryError -> "❌ الذاكرة غير كافية لمعالجة الصورة."
        else -> "❌ ${e.message ?: e.javaClass.simpleName}"
    }

    fun run(label: String, block: () -> String, onDone: (String) -> Unit = {}) {
        scope.launch {
            busy = true
            log = "⏳ $label… (قد يستغرق دقائق للصور الكبيرة)"
            val result = withContext(Dispatchers.IO) { try { block() } catch (e: Throwable) { errorText(e) } }
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
                    val path = fsCopyToUploads(context, uri)
                    path to ("fs_unpacked/" + File(path).nameWithoutExtension)
                }
            }
            picked.onSuccess { (path, dir) ->
                imagePath = path; unpackDir = dir; outputPath = ""; log = "✅ جاهزة: $path"
            }.onFailure { e -> log = errorText(e) }
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { handlePicked(it) }
    val fallbackPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { handlePicked(it) }
    fun launchPicker() {
        if (runCatching { picker.launch(arrayOf("*/*")) }.isFailure) {
            runCatching { fallbackPicker.launch("*/*") }.onFailure { e -> log = "❌ تعذّر فتح منتقي الملفات: ${e.message}" }
        }
    }

    val gold = Color(0xFFFFB800)
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF1A1A1A), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("💽 صور System / Vendor / Product", color = gold, fontWeight = FontWeight.Bold)
        Text(
            "EXT4 وEROFS، عادية أو sparse. يستخدم أدوات حقيقية داخل بيئة Linux (يثبّتها عند أول استخدام). " +
                "لا تُنسخ وسوم SELinux ولا المالك الحقيقي، والناتج غير موقّع: للفحص والتجارب على جهاز بوتلودره مفتوح.",
            color = Color(0xFF9A9A9A), fontSize = 11.sp
        )
        Button(enabled = !busy, onClick = { launchPicker() }, colors = ButtonDefaults.buttonColors(containerColor = gold)) {
            Text("📂 اختيار صورة (.img)", color = Color(0xFF121212))
        }
        if (imagePath.isNotBlank()) Text("الصورة: $imagePath", color = Color(0xFFEDEDED), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && imagePath.isNotBlank(), onClick = {
                run("تحليل الصورة", block = { FsImageTools.info(context, imagePath) })
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B2B2B))) { Text("🔍 تحليل", color = gold, fontSize = 12.sp) }
            Button(enabled = !busy && imagePath.isNotBlank(), onClick = {
                run("فك الصورة", block = { FsImageTools.unpack(context, imagePath, unpackDir) })
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B2B2B))) { Text("🧩 فك", color = gold, fontSize = 12.sp) }
            Button(enabled = !busy && unpackDir.isNotBlank(), onClick = {
                val target = "deliverables/" + File(unpackDir).name + "-new.img"
                run("تركيب الصورة", block = { FsImageTools.repack(context, unpackDir, target, 0) }) { result ->
                    if (result.startsWith("✅")) outputPath = target
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))) { Text("🔧 تركيب", color = Color.White, fontSize = 12.sp) }
        }
        if (unpackDir.isNotBlank()) {
            Text("مجلد الفك: $unpackDir — عدّل الملفات من تبويب الملفات أو الطرفية ثم اضغط «تركيب».", color = Color(0xFF9A9A9A), fontSize = 11.sp)
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
