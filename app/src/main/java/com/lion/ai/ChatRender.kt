package com.lion.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** أجزاء الرد بالترتيب: نص، كتلة كود، بطاقة ملف. */
internal sealed interface Seg {
    data class Text(val text: String) : Seg
    data class Code(val lang: String, val code: String) : Seg
    data class FileRef(val path: String) : Seg
}

private val SEG_PATTERN = Regex(
    "```([\\w+#.-]*)[ \\t]*\\r?\\n([\\s\\S]*?)```|\\[\\[FILE:([^\\]|]+)(?:\\|[^\\]]*)?\\]\\]"
)

internal fun parseSegments(text: String): List<Seg> {
    val out = mutableListOf<Seg>()
    var cursor = 0
    SEG_PATTERN.findAll(text).forEach { m ->
        if (m.range.first > cursor) {
            val chunk = text.substring(cursor, m.range.first).trim()
            if (chunk.isNotBlank()) out.add(Seg.Text(chunk))
        }
        val filePath = m.groups[3]?.value
        if (filePath != null) {
            if (filePath.isNotBlank()) out.add(Seg.FileRef(filePath.trim()))
        } else {
            out.add(Seg.Code(m.groupValues[1].ifBlank { "code" }, m.groupValues[2].trimEnd()))
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) {
        val tail = text.substring(cursor).trim()
        if (tail.isNotBlank()) out.add(Seg.Text(tail))
    }
    return out
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "تم النسخ", Toast.LENGTH_SHORT).show()
}

private fun mdInline(line: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < line.length) {
        if (line.startsWith("**", i)) {
            val end = line.indexOf("**", i + 2)
            if (end > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if (line[i] == '`') {
            val end = line.indexOf('`', i + 1)
            if (end > 0) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0xFF2A2A2A),
                        color = Color(0xFFFFD36B)
                    )
                ) { append(line.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        append(line[i])
        i++
    }
}

/** نص Markdown خفيف: عناوين، قوائم، **غامق**، `كود مضمّن`. */
@Composable
internal fun MdText(text: String, color: Color, size: Int = 15) {
    Column {
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(6.dp))
                line.trim() == "---" -> Spacer(Modifier.height(4.dp))
                line.trimStart().startsWith("#") -> {
                    val level = line.trimStart().takeWhile { it == '#' }.length.coerceIn(1, 3)
                    val body = line.trimStart().drop(level).trim()
                    Text(
                        mdInline(body),
                        color = color,
                        fontSize = (size + 4 - level).sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = (size + 9).sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    Text(
                        mdInline("• " + line.trimStart().drop(2)),
                        color = color,
                        fontSize = size.sp,
                        lineHeight = (size + 9).sp,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                else -> Text(
                    mdInline(line),
                    color = color,
                    fontSize = size.sp,
                    lineHeight = (size + 9).sp
                )
            }
        }
    }
}

@Composable
internal fun CodeCard(lang: String, code: String, context: Context) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ShellResult?>(null) }
    val runnable = remember(lang) { CodeRunner.plan(lang) != null }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF161616),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF202020))
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(lang, color = Color(0xFF9A9A9A), fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (runnable) {
                    TextButton(enabled = !running, onClick = {
                        scope.launch {
                            running = true
                            result = withContext(Dispatchers.IO) { CodeRunner.run(context, lang, code, 30) }
                            running = false
                        }
                    }) { Text(if (running) "…" else "▶ تشغيل", color = Color(0xFF7CC47C), fontSize = 12.sp) }
                }
                IconButton(
                    onClick = {
                        val name = "code." + FileDelivery.extensionFor(lang)
                        val saved = FileDelivery.saveText(context, name, code)
                        Toast.makeText(
                            context,
                            if (saved != null) "حُفظ في $saved" else "تعذّر الحفظ",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Filled.Download, contentDescription = "حفظ كملف", tint = Color(0xFF9A9A9A), modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { copyToClipboard(context, "code", code) },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "نسخ", tint = Color(0xFF9A9A9A), modifier = Modifier.size(18.dp))
                }
            }
            Text(
                code,
                color = Color(0xFFE6E6E6),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
            result?.let {
                Text(
                    it.output,
                    color = if (it.ok) Color(0xFF7CC47C) else Color(0xFFFF9A9A),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF101010)).padding(12.dp)
                )
            }
        }
    }
}

/** بطاقة ملف/مجلد قابلة للتنزيل والمشاركة (المجلد يصل كملف ZIP). */
@Composable
internal fun FileCard(path: String, context: Context) {
    val file = remember(path) { FileDelivery.file(context, path) }
    val name = path.substringAfterLast('/')
    val ext = name.substringAfterLast('.', "").uppercase()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1E1E1E),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
    ) {
        Column {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2B2B2B)),
                contentAlignment = Alignment.Center
            ) {
                Text(if (ext == "ZIP") "🗂️" else "📄", fontSize = 22.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (file == null) "الملف لم يعد متاحاً" else
                        (if (ext.isBlank()) "ملف" else ext) + " · " + FileTools.formatSize(file.length()),
                    color = Color(0xFF9A9A9A),
                    fontSize = 12.sp
                )
            }
            if (file != null) {
                IconButton(onClick = {
                    val saved = FileDelivery.saveFile(context, file)
                    if (saved != null) {
                        Toast.makeText(context, "حُفظ في $saved", Toast.LENGTH_LONG).show()
                    } else if (!FileDelivery.share(context, file)) {
                        Toast.makeText(context, "تعذّر الحفظ", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Filled.Download, contentDescription = "تنزيل", tint = Color(0xFFFFB800))
                }
                IconButton(onClick = { FileDelivery.share(context, file) }) {
                    Icon(Icons.Filled.Share, contentDescription = "مشاركة", tint = Color(0xFF9A9A9A))
                }
            }
        }
        if (file != null) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
                TextButton(onClick = {
                    if (!FileDelivery.open(context, file)) Toast.makeText(context, "لا يوجد تطبيق مناسب للفتح", Toast.LENGTH_SHORT).show()
                }) { Text(if (ext == "APK") "تثبيت" else "فتح", color = Color(0xFF7CC47C), fontSize = 11.sp) }
                TextButton(onClick = {
                    val uri = FileDelivery.contentUri(context, file)
                    if (uri != null) copyToClipboard(context, "رابط ملف محلي", uri)
                }) { Text("نسخ الرابط", color = Color(0xFF9A9A9A), fontSize = 11.sp) }
                if (FileDelivery.canRunInTermux(file)) {
                    TextButton(onClick = {
                        val ok = FileDelivery.runInTermux(context, file)
                        Toast.makeText(context, if (ok) "أُرسل إلى Termux" else "فعّل Allow External Apps في Termux ثم أعد المحاولة", Toast.LENGTH_LONG).show()
                    }) { Text(if (ext == "APK") "تثبيت عبر Termux" else "تنفيذ عبر Termux", color = Color(0xFF64B5F6), fontSize = 11.sp) }
                }
            }
        }
        }
    }
}

internal const val ASK_MARK = "— 💬 أنتظر ردك"

/** بطاقة قرار/صلاحية: خيارات قابلة للضغط + حقل ملاحظات، ثم يتابع الوكيل بعد الإرسال. */
@Composable
internal fun AskCard(body: String, options: List<String>, enabled: Boolean, onSend: (String) -> Unit) {
    var selected by remember(body) { mutableStateOf(-1) }
    var note by remember(body) { mutableStateOf("") }
    val choices = if (options.isEmpty()) listOf("✅ موافق، تابع", "⛔ لا تنفّذ") else options
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1B1710),
        border = BorderStroke(1.dp, Color(0xFF5A4A1E)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("🦁 يحتاج العمل قرارك للمتابعة", color = Color(0xFFFFB800), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (body.isNotBlank()) MdText(body, Color(0xFFE9E9E9))
            Spacer(Modifier.height(8.dp))
            choices.forEachIndexed { i, label ->
                val on = i == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) Color(0xFF2E2511) else Color(0xFF16130C))
                        .border(1.dp, if (on) Color(0xFFFFB800) else Color(0xFF332B16), RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled) { selected = i }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (on) "◉" else "○", color = Color(0xFFFFB800), fontSize = 14.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(label, color = Color(0xFFEDEDED), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                enabled = enabled,
                placeholder = { Text("ملاحظات للتنفيذ (اختياري)", color = Color(0xFF8A8A8A), fontSize = 12.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFFB800),
                    unfocusedBorderColor = Color(0xFF332B16),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color(0xFFFFB800)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Button(
                enabled = enabled && selected >= 0,
                onClick = {
                    val choice = choices.getOrNull(selected).orEmpty()
                    onSend(choice + if (note.isNotBlank()) "\nملاحظات: ${note.trim()}" else "")
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800), contentColor = Color(0xFF121212))
            ) { Text("متابعة", fontWeight = FontWeight.Bold) }
        }
    }
}

/** محتوى الرسالة مرتّباً: نص منسّق، أكواد قابلة للنسخ والحفظ، بطاقات ملفات، وبطاقة قرار عند انتظار الرد. */
@Composable
internal fun MessageContent(
    text: String,
    isUser: Boolean,
    context: Context,
    askEnabled: Boolean = false,
    onAskReply: (String) -> Unit = {}
) {
    val askAt = if (!isUser) text.indexOf(ASK_MARK) else -1
    val shown = if (askAt >= 0) text.substring(0, askAt).trim() else text
    val segs = remember(shown) { parseSegments(shown) }
    val color = if (isUser) Color(0xFFF2F2F2) else Color(0xFFE9E9E9)
    Column {
        segs.forEach { seg ->
            when (seg) {
                is Seg.Text -> if (askAt >= 0 && seg === segs.last()) {
                    val lines = seg.text.lines()
                    val options = lines.map { it.trim() }
                        .filter { it.startsWith("- ") || it.startsWith("• ") }
                        .map { it.drop(2).trim() }
                    val body = lines.filterNot { it.trim().startsWith("- ") || it.trim().startsWith("• ") }
                        .joinToString("\n").trim()
                    AskCard(AnswerGuard.sanitizeForDisplay(body), options, askEnabled, onAskReply)
                } else {
                    MdText(if (isUser) seg.text else AnswerGuard.sanitizeForDisplay(seg.text), color)
                }
                is Seg.Code -> CodeCard(seg.lang, seg.code, context)
                is Seg.FileRef -> FileCard(seg.path, context)
            }
        }
        if (askAt >= 0 && (segs.isEmpty() || segs.last() !is Seg.Text)) {
            AskCard("", emptyList(), askEnabled, onAskReply)
        }
    }
}
