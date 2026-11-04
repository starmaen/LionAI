package com.lion.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ENV_CARD = Color(0xFF1A1A1A)
private val ENV_GOLD = Color(0xFFFFB800)
private val ENV_MUTED = Color(0xFF9A9A9A)

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("cmd", text))
}

/** تبويب «البيئة المحلية»: فحص/إنشاء مساحة العمل + قائمة أدوات المطوّر مع تثبيت الناقص. */
@Composable
fun LocalEnvTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<WorkspaceReport?>(null) }
    var tools by remember { mutableStateOf<List<DevToolStatus>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            busy = true
            val (r, t) = withContext(Dispatchers.IO) { LocalEnv.ensureWorkspace(context) to LocalEnv.inventory(context) }
            report = r
            tools = t
            busy = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🖥️ البيئة المحلية", color = ENV_GOLD, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Button(enabled = !busy, onClick = { refresh() }, colors = ButtonDefaults.buttonColors(containerColor = ENV_CARD)) {
                Text(if (busy) "…" else "🔄 فحص", color = ENV_GOLD)
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = ENV_CARD)) {
            Column(Modifier.padding(12.dp)) {
                Text("📁 مساحة العمل", color = Color.White, fontWeight = FontWeight.Bold)
                val r = report
                if (r == null) {
                    Text("جارٍ الفحص…", color = ENV_MUTED, fontSize = 12.sp)
                } else {
                    Text(
                        if (r.ok) "✅ جاهزة: ملفات، ZIP، مجلدات ومشاريع" else "⚠️ فيها مشكلة، راجع التفاصيل",
                        color = if (r.ok) Color(0xFF7CC47C) else Color(0xFFE94560), fontSize = 12.sp
                    )
                    Text(r.root, color = ENV_MUTED, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    r.checks.forEach { c ->
                        Text((if (c.ok) "✅ " else "❌ ") + c.name + ": " + c.detail, color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("🧰 الأدوات المطلوبة", color = ENV_GOLD, fontWeight = FontWeight.Bold)
        if (message.isNotBlank()) {
            Text(message, color = ENV_MUTED, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        tools.forEach { status ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = ENV_CARD)) {
                Column(Modifier.padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (status.ok) "✅" else "❌", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(status.tool.label, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(status.version, color = ENV_MUTED, fontSize = 11.sp)
                        }
                        if (!status.ok && status.tool.id != "ca") {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        val res = withContext(Dispatchers.IO) { LocalEnv.install(context, status.tool) }
                                        message = (if (res.ok) "✅ " else "❌ ") + status.tool.label + ": " + res.output.takeLast(300)
                                        busy = false
                                        refresh()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ENV_GOLD)
                            ) { Text("تثبيت", color = Color(0xFF121212)) }
                        }
                    }
                    if (!status.ok) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                status.tool.manualCommand,
                                color = ENV_MUTED, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { copyText(context, status.tool.manualCommand) }) {
                                Text("نسخ", color = ENV_GOLD, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "التثبيت التلقائي يعمل إذا كان على الجهاز مدير حزم (pkg/apt). غير ذلك انسخ الأمر ونفّذه في Termux ثم اضغط «فحص».",
            color = ENV_MUTED, fontSize = 11.sp
        )
    }
}
