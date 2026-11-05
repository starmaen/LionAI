package com.lion.ai

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

private val LION_GOLD = Color(0xFFFFB800)
private val LION_ORANGE = Color(0xFFFF6D00)
private val TERM_BG = Color(0xFF0B0B0B)
private val TERM_TEXT = Color(0xFFEDEDED)
private val TERM_MUTED = Color(0xFF9A9A9A)

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007")

/** يزيل رموز ANSI ويحوّل \r إلى سطر جديد حتى تُقرأ مخرجات أدوات التقدّم بوضوح. */
internal fun cleanTerminalChunk(raw: String): String =
    ANSI.replace(raw, "").replace("\r\n", "\n").replace('\r', '\n')

/**
 * طرفية حقيقية داخل البيئة المحلية: مخرجات حيّة أثناء التنفيذ، وإدخال تفاعلي للعملية الجارية
 * (للأسئلة التي تطلب إجابة)، وزر إيقاف، بلا مهلة 30 ثانية. تعمل داخل Alpine على /workspace.
 */
@Composable
fun LionTerminal() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("🦁 Lion Terminal — Alpine /workspace\n") }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var process by remember { mutableStateOf<Process?>(null) }
    val scroll = rememberScrollState()

    LaunchedEffect(output) { scroll.animateScrollTo(scroll.maxValue) }
    DisposableEffect(Unit) { onDispose { process?.destroy() } }

    fun append(text: String) {
        output = (output + text).takeLast(60_000)
    }

    fun runCommand(command: String) {
        if (!LinuxEnv.ready(context)) {
            append("\n⚠️ فعّل بيئة Linux من البطاقة أعلاه أولاً ثم أعد المحاولة.\n")
            return
        }
        running = true
        append("\n🦁 $ $command\n")
        val app = context.applicationContext
        thread(name = "lion-terminal", isDaemon = true) {
            try {
                val p = LinuxEnv.start(app, command, FileTools.root(app))
                process = p
                val reader = p.inputStream.reader(Charsets.UTF_8)
                val buffer = CharArray(2048)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    append(cleanTerminalChunk(String(buffer, 0, n)))
                }
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                append("\n[انتهى — رمز الخروج $code]\n")
            } catch (e: Exception) {
                append("\n❌ ${e.message ?: e.javaClass.simpleName}\n")
            } finally {
                process = null
                running = false
            }
        }
    }

    fun submit() {
        val text = input
        if (running) {
            val p = process ?: return
            input = ""
            append("$text\n")
            thread(isDaemon = true) {
                runCatching {
                    p.outputStream.write((text + "\n").toByteArray())
                    p.outputStream.flush()
                }
            }
        } else if (text.isNotBlank()) {
            input = ""
            runCommand(text.trim())
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text("🦁 Terminal", color = LION_GOLD, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "طرفية Alpine حقيقية: المخرجات تظهر لحظياً، وأثناء تشغيل أمر يصبح الحقل إدخالاً للعملية نفسها.",
            color = TERM_MUTED, fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "opencode --version", "opencode models", "opencode auth list",
                "ls -la /workspace", "git --version", "node --version"
            ).forEach { cmd ->
                TextButton(
                    enabled = !running,
                    onClick = { input = cmd },
                    modifier = Modifier.border(1.dp, LION_ORANGE.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                ) { Text(cmd, color = LION_ORANGE, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(TERM_BG, RoundedCornerShape(12.dp))
                .border(1.dp, LION_GOLD.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Text(
                output,
                color = TERM_TEXT,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                modifier = Modifier.verticalScroll(scroll)
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (running) "↩" else "🦁", color = LION_ORANGE, fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text(if (running) "إدخال للعملية الجارية…" else "اكتب أمراً…", color = TERM_MUTED, fontSize = 12.sp) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LION_GOLD,
                    unfocusedBorderColor = Color(0xFF2A2A2A),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = LION_GOLD
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = { submit() }, colors = ButtonDefaults.buttonColors(containerColor = LION_GOLD)) {
                Text(if (running) "إرسال" else "تشغيل", color = Color(0xFF121212), fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = running, onClick = { process?.destroy() }) {
                Text("■ إيقاف", color = Color(0xFFE94560), fontSize = 12.sp)
            }
            TextButton(onClick = { output = "🦁 Lion Terminal — Alpine /workspace\n" }) {
                Text("🧹 مسح", color = LION_GOLD, fontSize = 12.sp)
            }
        }
    }
}

/** ربط مزود بـ OpenCode بلا طرفية تفاعلية: يرسل المفتاح إلى خادم OpenCode المحلي ثم يحدّث النماذج. */
@Composable
fun OpenCodeAuthCard() {
    val scope = rememberCoroutineScope()
    var providerId by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Color(0xFF1A1A1A), RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text("🔑 ربط مزود بـ OpenCode", color = LION_GOLD, fontWeight = FontWeight.Bold)
        Text(
            "بديل عن opencode auth login (يحتاج طرفية تفاعلية كاملة). اكتب معرّف المزود مثل openai أو anthropic أو openrouter ومفتاحه.",
            color = TERM_MUTED, fontSize = 11.sp
        )
        val colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LION_GOLD, unfocusedBorderColor = Color(0xFF2A2A2A),
            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = LION_GOLD
        )
        OutlinedTextField(providerId, { providerId = it.trim() }, singleLine = true, label = { Text("معرّف المزود") }, colors = colors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it.trim() }, singleLine = true, label = { Text("المفتاح") }, colors = colors, modifier = Modifier.fillMaxWidth())
        Button(
            enabled = !busy && providerId.isNotBlank() && key.isNotBlank(),
            onClick = {
                scope.launch {
                    busy = true
                    status = "⏳ جارٍ الربط…"
                    status = withContext(Dispatchers.IO) {
                        runCatching { OpenCodeLocal.setAuth(providerId, key) }
                            .getOrElse { "❌ ${it.message ?: it.javaClass.simpleName}" }
                    }
                    busy = false
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = LION_ORANGE)
        ) { Text("ربط", color = Color.White) }
        if (status.isNotBlank()) Text(status, color = if (status.startsWith("✅")) Color(0xFF7CC47C) else Color(0xFFFF9A9A), fontSize = 11.sp)
    }
}
