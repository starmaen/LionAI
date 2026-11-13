package com.lion.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SrvAmber = Color(0xFFFFB800)
private val SrvCard = Color(0xFF151515)
private val SrvMuted = Color(0xFF9A9A9A)

@Composable
private fun SrvPill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) SrvAmber else Color(0xFF262626))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun SrvButton(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
        onClick = onClick
    ) { Text(label, color = SrvAmber, fontSize = 11.sp, maxLines = 1) }
}

/** نموذج إضافة/تعديل خادم. */
@Composable
private fun ServerForm(
    initialName: String,
    initialKind: String,
    initialUrl: String,
    initialToken: String,
    error: String,
    onSave: (name: String, kind: String, url: String, token: String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var kind by remember { mutableStateOf(initialKind) }
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf(initialToken) }
    val textStyle = TextStyle(fontSize = 12.sp, color = Color(0xFFE0E0E0))
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SrvPill("📱 محلي (جهازي / Termux)", kind == ServerRegistry.KIND_LOCAL, Modifier.weight(1f)) {
                kind = ServerRegistry.KIND_LOCAL
            }
            SrvPill("🌐 خارجي (سحابي)", kind == ServerRegistry.KIND_EXTERNAL, Modifier.weight(1f)) {
                kind = ServerRegistry.KIND_EXTERNAL
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("اسم الخادم", fontSize = 11.sp) },
            singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = textStyle
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("الرابط (Base URL)", fontSize = 11.sp) },
            placeholder = {
                Text(
                    if (kind == ServerRegistry.KIND_LOCAL) "127.0.0.1:20128/v1" else "my-gateway.workers.dev/v1",
                    fontSize = 10.sp
                )
            },
            singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = textStyle
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = token, onValueChange = { token = it },
            label = { Text("المفتاح (اختياري للمحلي)", fontSize = 11.sp) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), textStyle = textStyle
        )
        Text(
            "يجب أن يكون الخادم متوافقاً مع OpenAI ويقبل الموديل بصيغة provider/model " +
                "(مثل بوابة LionAI أو LiteLLM أو OmniRoute).",
            color = SrvMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)
        )
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF6B6B), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SrvButton("💾 حفظ الخادم", modifier = Modifier.weight(1f)) { onSave(name, kind, url, token) }
            SrvButton("إلغاء", modifier = Modifier.weight(1f)) { onCancel() }
        }
    }
}

@Composable
private fun ServerCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    switchActive: Boolean,
    onToggle: (Boolean) -> Unit,
    report: String,
    busy: Boolean,
    onTestPlain: () -> Unit,
    onTestProvider: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .background(SrvCard, RoundedCornerShape(12.dp)).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = SrvAmber, fontSize = 14.sp)
                Text(subtitle, color = SrvMuted, fontSize = 10.sp)
            }
            Switch(
                checked = enabled,
                enabled = switchActive,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = SrvAmber)
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SrvButton(if (busy) "⏳ …" else "🩺 فحص بدون مزود", enabled = !busy, modifier = Modifier.weight(1f)) { onTestPlain() }
            SrvButton(if (busy) "⏳ …" else "🧪 فحص مع مزود", enabled = !busy, modifier = Modifier.weight(1f)) { onTestProvider() }
        }
        if (onEdit != null || onDelete != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onEdit != null) TextButton(onClick = onEdit) { Text("✏️ تعديل", color = SrvAmber, fontSize = 11.sp) }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("🗑️ حذف", color = Color(0xFFFF6B6B), fontSize = 11.sp) }
            }
        }
        if (report.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(report.take(1800), color = Color.White, fontSize = 11.sp)
        }
        extra()
    }
}

/**
 * قسم «الخوادم» في تبويب الاتصال: إضافة/تعديل/حذف خوادم محلية وخارجية، فحص كل خادم
 * (بدون مزود ومع مزود)، واختيار «مباشر / خادم واحد / كل الخوادم». المزودات مشتركة وليست
 * جزءاً من هذه الشاشة، وكل هذا مستقل تماماً عن وضع المحادثة (عام/المطوّر) وعن يدوي/تلقائي.
 * غرفة العمليات المركزية قائمة منسدلة أسفل الخوادم.
 */
@Composable
fun ServersSection(prefs: SharedPreferences, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rev by remember { mutableIntStateOf(0) }
    fun bump() {
        rev++
        onChanged()
    }
    val off = remember(rev) { ServerRegistry.isOff(prefs) }
    val scopeNow = remember(rev) { ServerRegistry.scope(prefs) }
    val directFirst = remember(rev) { RoutePolicy.channelEnabled(prefs, Channel.DIRECT) }
    val customs = remember(rev) { ServerRegistry.list(prefs) }
    val localOn = remember(rev) { ServerRegistry.localOn(prefs) }
    val gwOn = remember(rev) { ServerRegistry.gatewayOn(prefs) }
    val gwUrl = remember(rev) { prefs.getString(WebGateway.KEY_URL, "").orEmpty() }
    val gwToken = remember(rev) { prefs.getString(WebGateway.KEY_TOKEN, "").orEmpty() }
    val reports = remember { mutableStateMapOf<String, String>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    var probeProvider by remember { mutableStateOf("gemini") }
    var adding by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf("") }

    fun runTest(id: String, start: String, job: () -> String) {
        if (busy[id] == true) return
        busy[id] = true
        reports[id] = start
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching(job).getOrElse { "❌ ${it.message ?: it.javaClass.simpleName}" }
            }
            reports[id] = text
            busy[id] = false
        }
    }

    fun probeModel(): String = WebGateway.defaultModel(probeProvider)

    SettingsSection(
        title = "🖥️ الخوادم",
        subtitle = "أضف خوادمك المحلية والخارجية، افحصها، وفعّل واحداً أو كلها أو اتركها مباشرة",
        badge = when {
            off -> "🔌 مباشر"
            scopeNow == ServerRegistry.SCOPE_ONE -> "☝️ خادم واحد"
            else -> "🗂️ كل الخوادم"
        },
        initiallyExpanded = true
    ) {
        Text(
            "هذه الإعدادات تخص طبيعة الاتصال فقط، ولا علاقة لها بخيارات الدردشة (عام/المطوّر أو يدوي/تلقائي).",
            color = SrvMuted, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SrvPill("🔌 مباشر", off, Modifier.weight(1f)) {
                ServerRegistry.selectMode(prefs, off = true)
                bump()
            }
            SrvPill("☝️ خادم واحد", !off && scopeNow == ServerRegistry.SCOPE_ONE, Modifier.weight(1f)) {
                ServerRegistry.setScope(prefs, ServerRegistry.SCOPE_ONE)
                ServerRegistry.selectMode(prefs, off = false)
                bump()
            }
            SrvPill("🗂️ كل الخوادم", !off && scopeNow == ServerRegistry.SCOPE_ALL, Modifier.weight(1f)) {
                ServerRegistry.setScope(prefs, ServerRegistry.SCOPE_ALL)
                ServerRegistry.selectMode(prefs, off = false)
                bump()
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                off -> "مباشر: كل مزود يتصل بمفتاحه وعنوانه مباشرة دون أي خادم (الأولوية التلقائية)."
                scopeNow == ServerRegistry.SCOPE_ONE -> "خادم واحد: فعّل خادماً واحداً فقط من القائمة؛ تفعيل آخر يُوقف السابق."
                else -> "كل الخوادم: تعمل المفعّلة معاً، وإن تعطل خادم ينتقل الطلب إلى التالي."
            },
            color = Color(0xFFB0B0B0), fontSize = 11.sp
        )
        if (!off) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🔌 المباشر أولاً", color = Color.White, fontSize = 13.sp)
                    Text(
                        if (directFirst) "الأولوية للمباشر، والخوادم طريق إضافي عند تعذّره."
                        else "الخوادم وحدها دون الرجوع إلى المباشر.",
                        color = SrvMuted, fontSize = 10.sp
                    )
                }
                Switch(
                    checked = directFirst,
                    onCheckedChange = {
                        ServerRegistry.setDirectFirst(prefs, it)
                        bump()
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = SrvAmber)
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("مزود الفحص «مع مزود»:", color = Color(0xFFB0B0B0), fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WebGateway.providers.forEach { info ->
                SrvPill(info.label, probeProvider == info.id) { probeProvider = info.id }
            }
        }

        // ---- الخادم المحلي المدمج ----
        ServerCard(
            title = "📱 الخادم المحلي (داخل التطبيق)",
            subtitle = if (LocalAIProxy.isRunning()) "يعمل الآن — ${LocalAIProxy.getUrl()}" else "متوقف",
            enabled = localOn,
            switchActive = !off,
            onToggle = { on ->
                ServerRegistry.setOn(prefs, ServerRegistry.BUILTIN_LOCAL, on)
                scope.launch(Dispatchers.IO) {
                    if (on) runCatching { LocalAIProxy.start(ctx, prefs) } else LocalAIProxy.stop()
                    withContext(Dispatchers.Main) { bump() }
                }
                bump()
            },
            report = reports[ServerRegistry.BUILTIN_LOCAL].orEmpty(),
            busy = busy[ServerRegistry.BUILTIN_LOCAL] == true,
            onTestPlain = {
                runTest(ServerRegistry.BUILTIN_LOCAL, "⏳ أفحص الخادم المحلي…") {
                    if (!LocalAIProxy.isRunning()) runCatching { LocalAIProxy.start(ctx, prefs) }
                    LocalAIProxy.healthCheck()
                }
            },
            onTestProvider = {
                runTest(ServerRegistry.BUILTIN_LOCAL, "⏳ أفحص المزود عبر الخادم المحلي…") {
                    if (!LocalAIProxy.isRunning()) runCatching { LocalAIProxy.start(ctx, prefs) }
                    LocalAIProxy.providerCheck(prefs)
                }
            },
            extra = {
                val url = LocalAIProxy.getUrl()
                if (url.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(url, color = Color(0xFF64B5F6), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("LionAI Proxy", url))
                        }) { Text("📋 نسخ", color = SrvAmber, fontSize = 11.sp) }
                    }
                }
            }
        )

        // ---- الخادم الخارجي المعروف (بوابة LionAI) ----
        var gwEditing by remember { mutableStateOf(false) }
        ServerCard(
            title = "🌐 الخادم الخارجي (البوابة)",
            subtitle = if (gwUrl.isBlank()) "غير مضبوط — اضغط تعديل وأدخل الرابط والمفتاح"
            else gwUrl + if (gwToken.isBlank()) "  ⚠️ بلا مفتاح" else "",
            enabled = gwOn,
            switchActive = !off,
            onToggle = { on ->
                ServerRegistry.setOn(prefs, ServerRegistry.BUILTIN_GATEWAY, on)
                bump()
            },
            report = reports[ServerRegistry.BUILTIN_GATEWAY].orEmpty(),
            busy = busy[ServerRegistry.BUILTIN_GATEWAY] == true,
            onTestPlain = {
                runTest(ServerRegistry.BUILTIN_GATEWAY, "⏳ أفحص الخادم…") { WebGateway.test(prefs) }
            },
            onTestProvider = {
                runTest(ServerRegistry.BUILTIN_GATEWAY, "⏳ أفحص ${WebGateway.providerLabel(probeProvider)} عبر الخادم…") {
                    WebGateway.testProvider(prefs, probeProvider, probeModel())
                }
            },
            onEdit = { gwEditing = !gwEditing },
            extra = {
                if (gwEditing) {
                    ServerForm(
                        initialName = "الخادم الخارجي",
                        initialKind = ServerRegistry.KIND_EXTERNAL,
                        initialUrl = gwUrl.ifBlank { WebGateway.DEFAULT_URL },
                        initialToken = gwToken,
                        error = "",
                        onSave = { _, _, url, token ->
                            val mode = prefs.getString(WebGateway.KEY_MODE, WebGateway.MODE_OFF) ?: WebGateway.MODE_OFF
                            WebGateway.save(prefs, ServerRegistry.normalizeUrl(ServerRegistry.KIND_EXTERNAL, url), token, mode)
                            LlmClient.reloadOverrides(prefs)
                            ServerRegistry.applyChannels(prefs)
                            gwEditing = false
                            reports[ServerRegistry.BUILTIN_GATEWAY] = "✅ حُفظ رابط الخادم ومفتاحه."
                            bump()
                        },
                        onCancel = { gwEditing = false }
                    )
                }
            }
        )

        // ---- الخوادم المضافة ----
        customs.forEach { server ->
            val editing = editingId == server.id
            ServerCard(
                title = (if (server.kind == ServerRegistry.KIND_LOCAL) "📱 " else "🌐 ") + server.name,
                subtitle = server.url + if (server.token.isBlank()) "" else "  🔑",
                enabled = server.enabled,
                switchActive = !off,
                onToggle = { on ->
                    ServerRegistry.setOn(prefs, server.id, on)
                    bump()
                },
                report = reports[server.id].orEmpty(),
                busy = busy[server.id] == true,
                onTestPlain = {
                    runTest(server.id, "⏳ أفحص ${server.name}…") { WebGateway.testServerHealth(server.url, server.token) }
                },
                onTestProvider = {
                    runTest(server.id, "⏳ أفحص ${WebGateway.providerLabel(probeProvider)} عبر ${server.name}…") {
                        WebGateway.testServerWithProvider(server.url, server.token, probeProvider, probeModel())
                    }
                },
                onEdit = {
                    formError = ""
                    editingId = if (editing) null else server.id
                    adding = false
                },
                onDelete = {
                    ServerRegistry.remove(prefs, server.id)
                    reports.remove(server.id)
                    if (editingId == server.id) editingId = null
                    bump()
                },
                extra = {
                    if (editing) {
                        ServerForm(
                            initialName = server.name,
                            initialKind = server.kind,
                            initialUrl = server.url,
                            initialToken = server.token,
                            error = formError,
                            onSave = { name, kind, url, token ->
                                val err = ServerRegistry.upsert(prefs, server.id, name, kind, url, token)
                                if (err == null) {
                                    editingId = null
                                    formError = ""
                                    bump()
                                } else formError = err
                            },
                            onCancel = { editingId = null }
                        )
                    }
                }
            )
        }

        Spacer(Modifier.height(10.dp))
        if (adding) {
            Column(Modifier.fillMaxWidth().background(SrvCard, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("➕ إضافة خادم", color = SrvAmber, fontSize = 14.sp)
                ServerForm(
                    initialName = "", initialKind = ServerRegistry.KIND_LOCAL,
                    initialUrl = "", initialToken = "", error = formError,
                    onSave = { name, kind, url, token ->
                        val err = ServerRegistry.upsert(prefs, null, name, kind, url, token)
                        if (err == null) {
                            adding = false
                            formError = ""
                            bump()
                        } else formError = err
                    },
                    onCancel = { adding = false; formError = "" }
                )
            }
        } else {
            SrvButton("➕ إضافة خادم (محلي أو خارجي)", modifier = Modifier.fillMaxWidth()) {
                adding = true
                editingId = null
                formError = ""
            }
        }
        Spacer(Modifier.height(6.dp))
        SrvButton(
            if (busy["all"] == true) "⏳ …" else "🩺 فحص كل الخوادم المفعّلة (بدون مزود)",
            enabled = busy["all"] != true,
            modifier = Modifier.fillMaxWidth()
        ) {
            runTest("all", "⏳ أفحص الخوادم المفعّلة…") {
                val lines = mutableListOf<String>()
                if (ServerRegistry.localOn(prefs)) {
                    lines += "الخادم المحلي:\n" + if (LocalAIProxy.isRunning()) LocalAIProxy.healthCheck() else "ℹ️ متوقف."
                }
                if (ServerRegistry.gatewayOn(prefs) && prefs.getString(WebGateway.KEY_URL, "").orEmpty().isNotBlank()) {
                    lines += "الخادم الخارجي:\n" + WebGateway.test(prefs)
                }
                ServerRegistry.list(prefs).filter { it.enabled }.forEach {
                    lines += "${it.name}:\n" + WebGateway.testServerHealth(it.url, it.token)
                }
                if (lines.isEmpty()) "ℹ️ لا خوادم مفعّلة لفحصها." else lines.joinToString("\n\n")
            }
        }
        reports["all"]?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it.take(2400), color = Color.White, fontSize = 11.sp)
        }
    }

    // غرفة العمليات المركزية: قائمة منسدلة داخل حقل الاتصال أسفل الخوادم.
    SettingsSection(
        title = "🧠 غرفة العمليات المركزية",
        subtitle = "للمهام المعقدة في وضع المطوّر مع التلقائي فقط — اختياري",
        nested = true
    ) {
        OperationsRoomCard(prefs)
    }
}
