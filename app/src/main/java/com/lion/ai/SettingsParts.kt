package com.lion.ai

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Amber = Color(0xFFFFB800)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Amber,
    unfocusedBorderColor = Color(0xFF2A2A2A),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Amber
)

/** قسم قابل للطي: العنوان دائماً ظاهر، والمحتوى يظهر عند الضغط. nested = قائمة فرعية داخل قسم. */
@Composable
fun SettingsSection(
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    initiallyExpanded: Boolean = false,
    nested: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (nested) 8.dp else 14.dp)
            .background(if (nested) Color(0xFF131313) else Color(0xFF181818), shape)
            .border(1.dp, if (expanded) Color(0xFF5A4A1A) else Color(0xFF2A2A2A), shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = if (nested) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = Amber,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (nested) 14.sp else 16.sp
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, color = Color(0xFF9A9A9A), fontSize = 11.sp)
                }
            }
            if (!badge.isNullOrBlank()) {
                Text(badge, color = Color(0xFFB0B0B0), fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (expanded) "▲" else "▼", color = Amber, fontSize = 13.sp)
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                content = content
            )
        }
    }
}

/**
 * مفتاح تفعيل/تعطيل مزود مستقل. يُحفظ فوراً، ويُستبعد المزود المعطّل من
 * التوجيه التلقائي والبدائل دون المساس باختيارك اليدوي المحفوظ.
 */
@Composable
fun ProviderToggle(
    prefs: SharedPreferences,
    id: String,
    onChange: (Boolean) -> Unit = {}
) {
    var enabled by remember(id) { mutableStateOf(providerEnabled(prefs, id)) }
    Switch(
        checked = enabled,
        onCheckedChange = { value ->
            enabled = value
            setProviderEnabled(prefs, id, value)
            onChange(value)
        },
        colors = SwitchDefaults.colors(checkedThumbColor = Amber)
    )
}

/**
 * بطاقة مزود API قابلة للطي: المفتاح والموديل وزرا التحديث والاختبار تحت اسم المزود مباشرة.
 * الدائرة = المزود النشط (يُحفظ كالسابق في prefs عبر زر الحفظ).
 */
@Composable
fun ProviderCard(
    info: ProviderInfo,
    active: Boolean,
    onActivate: () -> Unit,
    keys: SnapshotStateMap<String, String>,
    models: SnapshotStateMap<String, String>,
    endpoints: SnapshotStateMap<String, String>,
    modelCache: SnapshotStateMap<String, List<String>>,
    accountId: String,
    onAccountIdChange: (String) -> Unit,
    prefs: SharedPreferences,
    scope: CoroutineScope
) {
    val uriHandler = LocalUriHandler.current
    var expanded by rememberSaveable { mutableStateOf(active) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var modelStatus by remember { mutableStateOf("") }
    var testStatus by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val id = info.id
    val hasKey = keys[id].orEmpty().isNotBlank()
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(Color(0xFF111111), shape)
            .border(1.dp, if (active) Amber else Color(0xFF262626), shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = active,
                onClick = onActivate,
                colors = RadioButtonDefaults.colors(selectedColor = Amber)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(info.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(info.tagline, color = Color(0xFF9A9A9A), fontSize = 11.sp)
            }
            ProviderToggle(prefs = prefs, id = id)
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    hasKey -> "✅ مفتاح"
                    info.keyless -> "🆓 بلا مفتاح"
                    else -> "⚪ بلا مفتاح محفوظ"
                },
                color = if (hasKey || info.keyless) Color(0xFF7CC47C) else Color(0xFF8A8A8A),
                fontSize = 11.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "▲" else "▼", color = Amber, fontSize = 12.sp)
        }

        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                if (active) {
                    Text("● المزود النشط حالياً", color = Amber, fontSize = 12.sp)
                } else {
                    TextButton(onClick = onActivate) {
                        Text("✔ استخدم هذا المزود", color = Amber, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("🔑 مفتاح ${info.label}", color = Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = keys[id].orEmpty(),
                    onValueChange = { keys[id] = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("الصق المفتاح هنا", color = Color(0xFF707070)) },
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = fieldColors()
                )
                if (info.keyless) {
                    Text(
                        "✅ هذا المزود يعمل بدون مفتاح. المفتاح اختياري لرفع الحدود فقط.",
                        color = Color(0xFF7CC47C),
                        fontSize = 12.sp
                    )
                }

                if (info.needsAccountId) {
                    Spacer(Modifier.height(10.dp))
                    Text("Cloudflare Account ID", color = Color.White, fontSize = 14.sp)
                    OutlinedTextField(
                        value = accountId,
                        onValueChange = onAccountIdChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("من لوحة Cloudflare", color = Color(0xFF707070)) },
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        singleLine = true,
                        colors = fieldColors()
                    )
                }

                fun testProvider() {
                    data class Outcome(val list: List<String>, val model: String, val reply: String)
                    val key = keys[id].orEmpty()
                    val account = accountId
                    val isServer = info.custom || id == "local_proxy"
                    // نحفظ العنوان الوسيط/المخصص الحالي مؤقتاً ليشمله الاختبار
                    LlmClient.endpointOverrides =
                        LlmClient.endpointOverrides + (id to endpoints[id].orEmpty().trim())
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                var model = models[id].orEmpty().trim()
                                var list = emptyList<String>()
                                if (model.isBlank() && isServer) {
                                    list = runCatching { LlmClient.listModels(id, key, account) }
                                        .getOrDefault(emptyList())
                                    model = list.firstOrNull() ?: info.defaultModel
                                }
                                if (model.isBlank()) model = info.defaultModel
                                val reply = LlmClient.ask(
                                    "أجب بكلمة واحدة فقط: تم", key, id, emptyList(), model, account
                                )
                                Outcome(list, model, reply)
                            }
                        }
                        testing = false
                        outcome.onSuccess { value ->
                            if (value.list.isNotEmpty()) {
                                modelCache[id] = value.list
                                ModelCatalog.saveCache(prefs, id, value.list)
                            }
                            testStatus = when {
                                LlmClient.isErrorReply(value.reply) -> "❌ فشل اختبار $id: " + value.reply
                                value.list.isNotEmpty() ->
                                    "✅ الخادم يعمل: ${value.list.size} موديل متاح، والاختبار نجح على «${value.model}». الرد: " +
                                        value.reply.take(60)
                                else -> "✅ يعمل على «${value.model}». الرد: " + value.reply.take(80)
                            }
                        }.onFailure {
                            testStatus = "❌ فشل الاختبار: " + (it.message ?: "خطأ غير معروف")
                        }
                    }
                }

                // زر الاختبار تحت المفتاح مباشرة (للمخصص يظهر بعد حقل Base URL)
                if (id != "custom") {
                    TextButton(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            testStatus = "⏳ أختبر المفتاح..."
                            testProvider()
                        }
                    ) {
                        Text("🧪 اختبار المفتاح والموديل", color = Amber, fontSize = 12.sp)
                    }
                    if (testStatus.isNotBlank()) {
                        Text(testStatus, color = Color(0xFFB0B0B0), fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    if (info.free) "الموديل — اختر من القائمة أو اكتب اسماً" else "الموديل (اختياري)",
                    color = Color.White,
                    fontSize = 14.sp
                )
                val modelOptions = ModelCatalog.options(info, modelCache[id].orEmpty())
                val activeModel = models[id].orEmpty().ifBlank { info.defaultModel }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = models[id].orEmpty(),
                        onValueChange = { models[id] = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(info.defaultModel, color = Color(0xFF707070)) },
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { modelMenuOpen = true }) {
                                Icon(Icons.Filled.ArrowDropDown, "قائمة الموديلات", tint = Amber)
                            }
                        },
                        colors = fieldColors()
                    )
                    DropdownMenu(
                        expanded = modelMenuOpen,
                        onDismissRequest = { modelMenuOpen = false },
                        modifier = Modifier.heightIn(max = 380.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text("↺ الافتراضي (${info.defaultModel})", color = Amber) },
                            onClick = {
                                models[id] = ""
                                modelMenuOpen = false
                            }
                        )
                        modelOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(if (option == activeModel) "$option  ✓" else option) },
                                onClick = {
                                    models[id] = option
                                    modelMenuOpen = false
                                }
                            )
                        }
                    }
                }
                if (id.startsWith("opencode") && (activeModel == "big-pickle" || activeModel.endsWith("-free"))) {
                    Text(
                        "ℹ️ هذا موديل مجاني في OpenCode. إن رفضه المزود (FreeUsageLimit) اختر موديلاً مدفوعاً برصيد Zen أو فعّل مزود OpenCode Go.",
                        color = Amber,
                        fontSize = 12.sp
                    )
                }

                TextButton(
                    enabled = !refreshing,
                    onClick = {
                        val problem = if (id == "custom") LlmClient.customUrlProblem(endpoints[id].orEmpty()) else null
                        if (problem != null) {
                            modelStatus = "⚠️ $problem"
                        } else {
                            refreshing = true
                            modelStatus = "⏳ جارٍ جلب القائمة..."
                            val key = keys[id].orEmpty().trim()
                            val account = accountId.trim()
                            LlmClient.endpointOverrides =
                                LlmClient.endpointOverrides + (id to endpoints[id].orEmpty().trim())
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { LlmClient.listModels(id, key, account) }
                                }
                                val list = result.getOrNull()
                                if (!list.isNullOrEmpty()) {
                                    val ranked = ModelRank.sorted(list)
                                    modelCache[id] = ranked
                                    ModelCatalog.saveCache(prefs, id, ranked)
                                    modelStatus = "✅ تم جلب ${ranked.size} موديل — الأقوى: " +
                                        (ModelRank.strongest(ranked) ?: "—") + ". افتح القائمة ▼."
                                } else {
                                    modelStatus = "⚠️ " + (result.exceptionOrNull()?.message ?: "لم يتم العثور على موديلات")
                                }
                                refreshing = false
                            }
                        }
                    }
                ) {
                    Text("🔄 تحديث قائمة الموديلات من الإنترنت", color = Amber, fontSize = 12.sp)
                }
                if (modelStatus.isNotBlank()) {
                    Text(modelStatus, color = Color(0xFFB0B0B0), fontSize = 12.sp)
                }

                if (info.endpoint.isNotBlank() || info.custom ||
                    id in setOf("groq", "openrouter", "opencode", "opencode-go", "opencode-console", "deepseek", "openai")
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (info.custom) "Base URL للخادم المخصص" else "عنوان وسيط (اختياري)",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = endpoints[id].orEmpty(),
                        onValueChange = { endpoints[id] = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = {
                            Text(
                                if (info.custom) "http://192.168.1.5:4000/v1  أو  https://gateway.example.com/v1/chat/completions"
                                else "https://اسم-الوسيط.workers.dev/openai/v1/chat/completions",
                                color = Color(0xFF707070),
                                fontSize = 11.sp
                            )
                        },
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                        colors = fieldColors()
                    )
                    Text(
                        if (info.custom) {
                            "يُقبل Base URL أو الرابط الكامل. إذا أدخلت http:// فيجب أن يكون localhost أو شبكة محلية (192.168/10/172.16-31) لحماية المفتاح."
                        } else {
                            "للشبكات التي يحجبها المزود: ضع رابط وسيط يمرّر الطلبات إليه (مثل Cloudflare Worker). اتركه فارغاً للاتصال المباشر."
                        },
                        color = Color(0xFF9A9A9A),
                        fontSize = 11.sp
                    )
                    if (info.custom) {
                        val typed = endpoints[id].orEmpty()
                        val problem = if (typed.isBlank()) null else LlmClient.customUrlProblem(typed)
                        if (problem != null) {
                            Text("⚠️ $problem", color = Color(0xFFFFB300), fontSize = 11.sp)
                        }
                        TextButton(
                            enabled = !testing,
                            onClick = {
                                testing = true
                                testStatus = "⏳ أختبر الاتصال بالخادم..."
                                testProvider()
                            }
                        ) {
                            Text("🧪 اختبار الاتصال بالخادم", color = Amber, fontSize = 12.sp)
                        }
                        if (testStatus.isNotBlank()) {
                            Text(testStatus, color = Color(0xFFB0B0B0), fontSize = 12.sp)
                        }
                    }
                }

                if (info.modelHint.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(info.modelHint, color = Color(0xFF9A9A9A), fontSize = 11.sp)
                }
                TextButton(onClick = { runCatching { uriHandler.openUri(info.docsUrl) } }) {
                    Text("📖 التوثيق الرسمي وحدود الاستخدام", color = Amber, fontSize = 12.sp)
                }
            }
        }
    }
}
