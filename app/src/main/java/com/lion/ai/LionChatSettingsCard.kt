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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Same controls in settings and developer tools; all changes persist immediately. */
@Composable
fun LionChatSettingsCard(prefs: SharedPreferences, onActivate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val orange = Color(0xFFFF6D00)
    var expanded by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickFor by remember { mutableStateOf<String?>(null) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // The listener also updates controls after returning from developer settings.
    val enabled = remember(revision) { prefs.getBoolean("web_chat_enabled", false) }
    val providers = PROVIDERS.filter { it.group == ProviderGroup.WEB }
    fun activate(id: String) {
        prefs.edit().putString("provider", id).putBoolean("auto_route", false).apply()
        LlmClient.reloadOverrides(prefs)
        onActivate(id)
    }
    fun test(ids: List<String>) {
        scope.launch {
            busy = true
            report = "الفحص يرسل رسالة قصيرة إلى المزود الفعلي وقد يستهلك رصيداً."
            try {
                for (id in ids) {
                    val line = withContext(Dispatchers.IO) { WebChatRuntime.test(prefs, id) }
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
                Switch(enabled, { prefs.edit().putBoolean("web_chat_enabled", it).apply() },
                    colors = SwitchDefaults.colors(checkedThumbColor = orange))
            }
            Text("مزودو الويب داخل الشات • اضغط لعرض القائمة", color = orange, fontSize = 12.sp)
            if (expanded) {
                Text("صفحات الويب تبقى مستقلة. الرد هنا من موديل الخادم الذي تربطه صراحةً، وليس من جلسة تسجيل الدخول للموقع. Arena وباقي المواقع تحتاج خلفية فعلية على الخادم؛ لا يُنشأ اتصال وهمي بها.", color = Color.White, fontSize = 12.sp)
                Text("يستخدم إعدادات Cloudflare / WebGateway الحالية. الخادم المحلي يستطيع توجيه الطلب إلى هذا الربط دون الرجوع إلى نفسه. الأدوات وصلاحيات التنفيذ تتبع إعدادات المطور والوضع الحالي.", color = Color.LightGray, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("إتاحة المزودين للعقل المركزي", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(prefs.getBoolean(WebChatRuntime.CENTRAL, true), {
                        prefs.edit().putBoolean(WebChatRuntime.CENTRAL, it).apply()
                    })
                }
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            models = withContext(Dispatchers.IO) { WebGateway.listModels(prefs) }
                            report = if (models.isEmpty()) "⚠️ لم تُجلب موديلات. تحقق من رابط Cloudflare وRELAY_TOKEN واختبار الخادم؛ لا تُعدّ الصفحة المفتوحة مزوداً جاهزاً." else "✅ جُلب ${models.size} موديل. اختر الموديل الفعلي لكل ربط."
                        } finally { busy = false }
                    }
                }) { Text("تحديث موديلات الخادم", color = orange) }
                Row {
                    TextButton(enabled = !busy, onClick = {
                        val edit = prefs.edit()
                        providers.filter { WebChatRuntime.validModel(WebChatRuntime.model(prefs, it.id)) }
                            .forEach { edit.putBoolean("web_chat_enabled_${it.id}", true) }
                        edit.apply()
                    }) { Text("تفعيل المربوطين") }
                    TextButton(enabled = !busy, onClick = {
                        val edit = prefs.edit()
                        providers.forEach { edit.putBoolean("web_chat_enabled_${it.id}", false) }
                        edit.apply()
                    }) { Text("تعطيل الكل") }
                }
                TextButton(enabled = enabled && !busy && WebChatRuntime.members(prefs).isNotEmpty(),
                    onClick = { test(WebChatRuntime.members(prefs).map { it.id }) }) { Text("فحص جماعي (رسائل فعلية)", color = orange) }
                TextButton(enabled = WebChatRuntime.available(prefs, WebChatRuntime.HUB) && !busy,
                    onClick = { activate(WebChatRuntime.HUB) }) { Text("استخدام Lion Chat في الدردشة", color = orange) }
                providers.forEach { info ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(info.shortName, color = orange, modifier = Modifier.weight(1f))
                        Switch(prefs.getBoolean("web_chat_enabled_${info.id}", false), {
                            prefs.edit().putBoolean("web_chat_enabled_${info.id}", it).apply()
                        }, enabled = !busy, colors = SwitchDefaults.colors(checkedThumbColor = orange))
                    }
                    val model = WebChatRuntime.model(prefs, info.id)
                    Text(if (model.isBlank()) "غير مربوط بخلفية — الصفحة وحدها لا تكفي" else "الخلفية الفعلية: $model", color = Color.LightGray, fontSize = 12.sp)
                    TextButton(enabled = !busy && models.isNotEmpty(), onClick = { pickFor = info.id }) { Text("اختيار موديل من الخادم") }
                    Row {
                        TextButton(enabled = !busy && WebChatRuntime.available(prefs, info.id), onClick = { activate(info.id) }) { Text("استخدام بالشات") }
                        TextButton(enabled = !busy && WebChatRuntime.available(prefs, info.id), onClick = { test(listOf(info.id)) }) { Text("فحص") }
                        TextButton(onClick = {
                            context.startActivity(Intent(context, ArenaActivity::class.java).putExtra("ARENA_URL", info.webviewUrl))
                        }) { Text("الصفحة") }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (report.isNotBlank()) Text(report, color = Color.White, fontSize = 12.sp)
            }
        }
    }
    pickFor?.let { id ->
        AlertDialog(onDismissRequest = { pickFor = null }, title = { Text("الخلفية الفعلية لـ ${providerInfo(id).shortName}") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    models.filter { WebChatRuntime.validModel(it) }.forEach { model ->
                        TextButton(onClick = {
                            prefs.edit().putString("web_chat_model_$id", model).apply()
                            pickFor = null
                        }) { Text(model) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { pickFor = null }) { Text("إغلاق") } })
    }
}
