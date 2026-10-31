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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    }
}

/** محتوى الرسالة مرتّباً: نص منسّق، أكواد قابلة للنسخ والحفظ، وبطاقات ملفات للتنزيل. */
@Composable
internal fun MessageContent(text: String, isUser: Boolean, context: Context) {
    val segs = remember(text) { parseSegments(text) }
    val color = if (isUser) Color(0xFFF2F2F2) else Color(0xFFE9E9E9)
    Column {
        segs.forEach { seg ->
            when (seg) {
                is Seg.Text -> MdText(if (isUser) seg.text else AnswerGuard.sanitizeForDisplay(seg.text), color)
                is Seg.Code -> CodeCard(seg.lang, seg.code, context)
                is Seg.FileRef -> FileCard(seg.path, context)
            }
        }
    }
}
