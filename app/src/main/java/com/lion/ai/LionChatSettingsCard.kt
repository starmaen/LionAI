package com.lion.ai

import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.ensureActive

@Composable
fun LionChatSettingsCard(prefs: SharedPreferences, onActivate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val orange = Color(0xFFFF6D00)
    var expanded by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var consent by remember { mutableStateOf(false) }
    var configure by remember { mutableStateOf<String?>(null) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener); testJob?.cancel() }
    }
    val enabled = remember(revision) { prefs.getBoolean(WebChatRuntime.ENABLED, false) }
    val providers = PROVIDERS.filter { it.group == ProviderGroup.WEB }
    fun activate(id: String) {
        prefs.edit().putString("provider", id).putBoolean("auto_route", false).apply()
        LlmClient.reloadOverrides(prefs)
        onActivate(id)
    }
    fun test(ids: List<String>) {
        testJob = scope.launch {
            busy = true
            report = "فحص جلسات المواقع برسائل فعلية — موقع واحد في كل مرة."
            try {
                for (id in ids) {
                    ensureActive()
                    val line = runInterruptible(Dispatchers.IO) { WebChatRuntime.test(prefs, id) }
                    report += "\n$line"
                }
            } finally { busy = false }
        }
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A0A))) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text("🦁 Lion Chat  ${if (expanded) "▴" else "▾"}", color = orange, fontSize = 20.sp, modifier = Modifier.weight(1f))
                Switch(enabled, { value ->
                    if (value) consent = true
                    else prefs.edit().putBoolean(WebChatRuntime.ENABLED, false).apply()
                }, colors = SwitchDefaults.colors(checkedThumbColor = orange))
            }
            Text("جلسات المواقع نفسها • دون API أو Cloudflare", color = orange, fontSize = 12.sp)
            if (expanded) {
                Text("المختار للشات: ${providerInfo(prefs.getString("provider", "gemini") ?: "gemini").shortName}", color = orange, fontSize = 13.sp)
                Text("التفعيل يتيح الموقع فقط؛ اضغط «اختيار للشات» لتوجيه الرسائل إليه. اختيار موقع يمنع الرجوع إلى مزود API.", color = Color.LightGray, fontSize = 12.sp)
                Text("يكتب التطبيق في صفحة الموقع ويقرأ جوابه الجديد. سجّل الدخول من «الجلسة». المحددات مبدئية وقابلة للتعديل؛ فتح الصفحة لا يعني نجاح الربط. العلامة الخضراء لا تظهر إلا بعد استلام جواب مكتمل.", color = Color.LightGray, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("إتاحة المواقع المختبَرة للعقل المركزي", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(prefs.getBoolean(WebChatRuntime.CENTRAL, true), {
                        prefs.edit().putBoolean(WebChatRuntime.CENTRAL, it).apply()
                    })
                }
                Row {
                    TextButton(enabled = !busy, onClick = {
                        val edit = prefs.edit()
                        providers.forEach { edit.putBoolean("web_chat_enabled_${it.id}", true) }
                        edit.apply()
                    }) { Text("تفعيل الكل") }
                    TextButton(enabled = !busy, onClick = {
                        val edit = prefs.edit()
                        providers.forEach { edit.putBoolean("web_chat_enabled_${it.id}", false) }
                        edit.apply()
                    }) { Text("تعطيل الكل") }
                }
                Row {
                    TextButton(enabled = enabled && !busy && WebChatRuntime.members(prefs).isNotEmpty(),
                        onClick = { test(WebChatRuntime.members(prefs).map { it.id }) }) { Text("فحص جماعي", color = orange) }
                    if (busy) TextButton(onClick = { testJob?.cancel() }) { Text("إيقاف الفحص") }
                }
                TextButton(enabled = WebChatRuntime.available(prefs, WebChatRuntime.HUB) && !busy,
                    onClick = { activate(WebChatRuntime.HUB) }) { Text("استخدام المجموعة في الشات", color = orange) }
                providers.forEach { info ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(info.shortName, color = orange, modifier = Modifier.weight(1f))
                        Switch(prefs.getBoolean("web_chat_enabled_${info.id}", false), {
                            prefs.edit().putBoolean("web_chat_enabled_${info.id}", it).apply()
                        }, enabled = !busy, colors = SwitchDefaults.colors(checkedThumbColor = orange))
                    }
                    Text(prefs.getString("web_session_status_${info.id}", "⚪ لم يُختبر الاتصال بالصفحة بعد").orEmpty(),
                        color = Color.LightGray, fontSize = 12.sp)
                    Row {
                        TextButton(enabled = !busy && WebChatRuntime.available(prefs, info.id), onClick = { activate(info.id) }) { Text("اختيار للشات") }
                        TextButton(enabled = !busy && WebChatRuntime.available(prefs, info.id), onClick = { test(listOf(info.id)) }) { Text("فحص") }
                        TextButton(enabled = !busy, onClick = { WebSessionTransport.open(info.id) }) { Text("الجلسة") }
                    }
                    Row {
                        TextButton(enabled = !busy, onClick = { WebSessionTransport.inspect(info.id) }) { Text("تشخيص الصفحة") }
                        TextButton(enabled = prefs.contains("web_session_dom_${info.id}"), onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LionAI DOM", prefs.getString("web_session_dom_${info.id}", "")))
                            report = "نُسخ تقرير محددات الصفحة دون نص المحادثة أو الكوكيز. راجعه قبل مشاركته."
                        }) { Text("نسخ التشخيص") }
                    }
                    Row {
                        TextButton(enabled = !busy, onClick = { configure = info.id }) { Text("محددات المطور") }
                        TextButton(onClick = {
                            val sessionUrl = WebSiteAdapters.get(info.id, prefs)?.url ?: info.webviewUrl
                            context.startActivity(Intent(context, ArenaActivity::class.java).putExtra("ARENA_URL", sessionUrl))
                        }) { Text("الصفحة الجانبية") }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (report.isNotBlank()) Text(report, color = Color.White, fontSize = 12.sp)
            }
        }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("تفعيل جلسات المواقع") },
        text = { Text("عند الإرسال سيصل نص رسالتك وسجل الحوار وتعليمات الأدوات إلى الموقع المختار، باستخدام حسابك داخل WebView. قد يستهلك ذلك حصتك. الفحص الجماعي يرسل رسالة لكل موقع مفعّل. لن تُنقل الكوكيز إلى خادم خارجي. لا تُمنح الصفحة صلاحيات تنفيذ أدوات التطبيق مباشرةً.") },
        confirmButton = { TextButton(onClick = { prefs.edit().putBoolean(WebChatRuntime.ENABLED, true).apply(); consent = false }) { Text("أوافق وأفعّل") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("إلغاء") } })
    configure?.let { id ->
        WebSelectorDialog(id, prefs, onClose = { configure = null })
    }
}

@Composable
private fun WebSelectorDialog(id: String, prefs: SharedPreferences, onClose: () -> Unit) {
    val adapter = remember(id) { requireNotNull(WebSiteAdapters.get(id, prefs)) }
    val fields = remember(id) { mutableStateMapOf("input" to adapter.input, "send" to adapter.send,
        "answer" to adapter.answer, "stop" to adapter.stop, "complete" to adapter.complete) }
    val labels = mapOf("input" to "حقل الكتابة", "send" to "زر الإرسال", "answer" to "رسالة المساعد فقط",
        "stop" to "زر إيقاف التوليد", "complete" to "زر نسخ/اكتمال داخل رسالة المساعد")
    AlertDialog(onDismissRequest = onClose, title = { Text("CSS • ${providerInfo(id).shortName}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("لا تستخدم body أو حاوية المحادثة كاملة لالتقاط الرد. تغيير المحددات يلغي حالة النجاح السابقة.", fontSize = 12.sp)
                labels.forEach { (key, label) ->
                    OutlinedTextField(value = fields[key].orEmpty(), onValueChange = { fields[key] = it },
                        label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = {
                    val edit = prefs.edit()
                    labels.keys.forEach { edit.remove("web_dom_${id}_$it") }
                    edit.putBoolean("web_session_verified_$id", false)
                        .remove("web_session_status_$id").apply()
                    onClose()
                }) { Text("استعادة المحددات المبدئية") }
            }
        }, confirmButton = { TextButton(onClick = {
            val edit = prefs.edit()
            fields.forEach { (key, value) -> edit.putString("web_dom_${id}_$key", value.trim()) }
            edit.putBoolean("web_session_verified_$id", false).remove("web_session_status_$id").apply()
            onClose()
        }) { Text("حفظ") } }, dismissButton = { TextButton(onClick = onClose) { Text("إغلاق") } })
}
