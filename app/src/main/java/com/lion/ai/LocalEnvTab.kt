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
import androidx.compose.runtime.mutableStateMapOf
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
    var activeTool by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    val toolMessages = remember { mutableStateMapOf<String, String>() }

    fun refresh() {
        scope.launch {
            val (r, t) = withContext(Dispatchers.IO) { LocalEnv.ensureWorkspace(context) to LocalEnv.inventory(context) }
            report = r
            tools = t
        }
    }
    LaunchedEffect(LinuxEnv.installing) { if (!LinuxEnv.installing) refresh() }
    val envReady = remember(LinuxEnv.installing, report) { LinuxEnv.ready(context) }
    val runtimeOk = remember { LinuxEnv.runtimeAvailable(context) }

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
                Text("🐧 بيئة Linux (Alpine + proot)", color = Color.White, fontWeight = FontWeight.Bold)
                when {
                    !runtimeOk -> Text(
                        "⚠️ proot أو إحدى مكتباته ناقصة (libtalloc/libandroid-shmem). ثبّت البناء المحدّث؛ لا تُعِد تثبيت الأدوات منفردة لأن العطل في مشغّل البيئة.",
                        color = Color(0xFFE94560), fontSize = 12.sp
                    )
                    LinuxEnv.installing -> Text(LinuxEnv.progress, color = ENV_GOLD, fontSize = 12.sp)
                    envReady -> {
                        Text("✅ مفعّلة: كل الأوامر والأدوات تعمل داخلها، ومساحة العمل مربوطة على /workspace", color = Color(0xFF7CC47C), fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !busy && activeTool == null, onClick = {
                                scope.launch {
                                    busy = true
                                    message = "⏳ ترقية الحزم المثبتة فقط؛ لن تُثبّت أدوات جديدة…"
                                    val result = withContext(Dispatchers.IO) { LinuxEnv.updateEnvironment(context) }
                                    message = (if (result.ok) "✅ اكتملت الترقية" else "❌ فشلت الترقية") +
                                        "\n" + result.output.takeLast(6000)
                                    busy = false
                                    refresh()
                                }
                            }) { Text("⬆️ ترقية المثبّت فقط", color = ENV_GOLD, fontSize = 11.sp) }
                            TextButton(enabled = !busy && activeTool == null, onClick = {
                                scope.launch {
                                    busy = true
                                    val result = withContext(Dispatchers.IO) {
                                        val runtime = LinuxEnv.diagnoseRuntime(context)
                                        if (runtime.ok) LinuxEnv.run(context, "apk --version && cat /etc/alpine-release", 30) else runtime
                                    }
                                    message = "🔎 فحص مشغّل البيئة:\n" + result.output.takeLast(6000)
                                    busy = false
                                }
                            }) { Text("🔎 تشخيص", color = ENV_GOLD, fontSize = 11.sp) }
                            TextButton(enabled = !busy && activeTool == null, onClick = { LinuxEnv.remove(context); refresh() }) {
                                Text("🗑️ حذف البيئة", color = Color(0xFFE94560), fontSize = 11.sp)
                            }
                        }
                    }
                    else -> {
                        Text(
                            "غير مفعّلة. التفعيل ينزّل نظام Alpine (نحو 4MB) ثم الأدوات الأساسية، ويحتاج اتصالاً ومساحة تقارب 400MB.",
                            color = ENV_MUTED, fontSize = 12.sp
                        )
                        if (LinuxEnv.lastError.isNotBlank()) {
                            Text("❌ " + LinuxEnv.lastError, color = Color(0xFFE94560), fontSize = 11.sp)
                        }
                        Button(onClick = { LinuxEnv.startInstall(context) }, colors = ButtonDefaults.buttonColors(containerColor = ENV_GOLD)) {
                            Text("⬇️ تحميل وتفعيل", color = Color(0xFF121212))
                        }
                    }
                }
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
                        if (status.tool.id != "ca" && envReady) {
                            Button(
                                enabled = !busy && activeTool == null,
                                onClick = {
                                    val id = status.tool.id
                                    scope.launch {
                                        activeTool = id
                                        toolMessages[id] = "⏳ ${status.tool.label}: بدأت عملية هذه الأداة فقط…"
                                        val res = withContext(Dispatchers.IO) { LocalEnv.install(context, status.tool) }
                                        toolMessages[id] = (if (res.ok) "✅ " else "❌ exit ${res.exitCode}: ") +
                                            status.tool.label + "\n" + res.output.takeLast(6000)
                                        activeTool = null
                                        refresh()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ENV_GOLD)
                            ) {
                                Text(
                                    if (activeTool == status.tool.id) "جارٍ…" else if (status.ok) "تحديث" else "تثبيت",
                                    color = Color(0xFF121212)
                                )
                            }
                        }
                    }
                    toolMessages[status.tool.id]?.let { log ->
                        Text(log, color = if (log.startsWith("✅")) Color(0xFF7CC47C) else Color(0xFFFF9A9A), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        TextButton(onClick = { copyText(context, log) }) { Text("نسخ سجل ${status.tool.label}", color = ENV_GOLD, fontSize = 11.sp) }
                    }
                    if (!status.ok && !envReady) {
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
        Spacer(Modifier.height(12.dp))
        Text("📦 الحزم المساعدة", color = ENV_GOLD, fontWeight = FontWeight.Bold)
        Text("ICU للنصوص العربية، SQLite، وحزم npm داخل /workspace/tools/node.", color = ENV_MUTED, fontSize = 11.sp)
        listOf("icu" to "ICU كامل", "sqlite" to "SQLite CLI", "axios" to "Axios", "lodash" to "Lodash", "cheerio" to "Cheerio", "sqlite3" to "SQLite3 npm").forEach { (id, label) ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = ENV_CARD)) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = Color.White, modifier = Modifier.weight(1f))
                        Button(
                            enabled = envReady && !busy && activeTool == null,
                            onClick = {
                                scope.launch {
                                    activeTool = "support:$id"
                                    toolMessages["support:$id"] = "⏳ تثبيت $label فقط…"
                                    val res = withContext(Dispatchers.IO) { LinuxEnv.installSupportPackage(context, id) }
                                    toolMessages["support:$id"] = (if (res.ok) "✅ " else "❌ ") + res.output.takeLast(6000)
                                    activeTool = null
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ENV_GOLD)
                        ) { Text(if (activeTool == "support:$id") "جارٍ…" else "تثبيت/تحديث", color = Color(0xFF121212), fontSize = 11.sp) }
                    }
                    toolMessages["support:$id"]?.let { log ->
                        Text(log, color = if (log.startsWith("✅")) Color(0xFF7CC47C) else Color(0xFFFF9A9A), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        TextButton(onClick = { copyText(context, log) }) { Text("نسخ السجل", color = ENV_GOLD, fontSize = 11.sp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "كل زر ينفّذ حزمة واحدة فقط. الأدوات والحزم تبقى بعد إغلاق التطبيق.",
            color = ENV_MUTED, fontSize = 11.sp
        )
    }
}
