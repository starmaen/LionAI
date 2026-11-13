package com.lion.ai

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
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

/** خيار رقمي صغير داخل [AgentStepBudgetSetting]. */
@Composable
private fun StepOptionChip(label: String, selected: Boolean, onSelect: () -> Unit) {
    Text(
        label,
        color = if (selected) Amber else Color(0xFFB0B0B0),
        fontSize = 12.sp,
        modifier = Modifier
            .background(if (selected) Color(0xFF3A2E00) else Color(0xFF1F1F1F), RoundedCornerShape(8.dp))
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

/**
 * سقف خطوات الأدوات في دورة الوكيل، والتمديد التلقائي، وانتظار إجابة [[ASK]] داخل الحلقة.
 * «تلقائي» (0) لا يضع رقماً ثابتاً: يبدأ من السقف الافتراضي ويمتد ما دام العمل يتقدّم،
 * والقيد الحقيقي يبقى الوقت وطلب المستخدم للإيقاف.
 */
@Composable
fun AgentStepBudgetSetting(prefs: SharedPreferences) {
    var maxSteps by remember { mutableStateOf(prefs.getInt(AGENT_MAX_STEPS, 0)) }
    var autoExtend by remember { mutableStateOf(prefs.getBoolean(AGENT_AUTO_EXTEND_STEPS, true)) }
    var inlineAsk by remember { mutableStateOf(prefs.getBoolean(AGENT_INLINE_ASK, true)) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("سقف خطوات الأدوات في الدورة الواحدة", color = Color.White, fontSize = 14.sp)
        Text(
            "«تلقائي» يفتح السقف ويمتد تلقائياً ما دام العمل يتقدّم، فالمهمة الطويلة لا تتوقف في منتصفها.",
            color = Color(0xFF9A9A9A),
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "تلقائي", 40 to "40", 80 to "80", 160 to "160").forEach { (value, label) ->
                StepOptionChip(label = label, selected = maxSteps == value) {
                    maxSteps = value
                    prefs.edit().putInt(AGENT_MAX_STEPS, value).apply()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = autoExtend,
                onCheckedChange = {
                    autoExtend = it
                    prefs.edit().putBoolean(AGENT_AUTO_EXTEND_STEPS, it).apply()
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Amber)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("تمديد تلقائي للسقف عند تقدّم فعلي", color = Color.White, fontSize = 14.sp)
                Text(
                    "كلما نجحت أداة يُضاف سقف إضافي (حتى حدّ أمان 400 خطوة)، بدل التوقف وإعادة الطلب.",
                    color = Color(0xFF9A9A9A),
                    fontSize = 11.sp
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = inlineAsk,
                onCheckedChange = {
                    inlineAsk = it
                    prefs.edit().putBoolean(AGENT_INLINE_ASK, it).apply()
                    if (!it) AgentAsk.clear()
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Amber)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("إجابة أسئلة الوكيل داخل نفس حلقة التنفيذ", color = Color.White, fontSize = 14.sp)
                Text(
                    "عند سؤال [[ASK]] تنتظر الحلقة ردّك دون إنهاء الجلسة ولا فقدان أي خطوة منفذة (مهلة 8 دقائق ثم حفظ آمن).",
                    color = Color(0xFF9A9A9A),
                    fontSize = 11.sp
                )
            }
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

    // منصة أُضيفت بعد بناء الخرائط: حمّل مفتاحها وموديلها وعنوانها من prefs بدل مفتاح فارغ.
    LaunchedEffect(id) {
        if (!keys.containsKey(id)) keys[id] = prefs.getString("api_key_$id", "") ?: ""
        if (!models.containsKey(id)) models[id] = prefs.getString("model_$id", "") ?: ""
        if (!endpoints.containsKey(id)) endpoints[id] = prefs.getString("endpoint_$id", "") ?: ""
        if (!modelCache.containsKey(id)) modelCache[id] = ModelCatalog.cached(prefs, id)
    }
    val hasKey = keys[id].orEmpty().isNotBlank()
    var healthTick by remember { mutableStateOf(0) }
    val health = remember(id, testing, healthTick) { ProviderHealth.get(prefs, id) }

    // يحفظ ما في الحقول فوراً: الخروج بالإيماءة لم يعد يضيّع المفتاح أو الموديل أو العنوان.
    fun saveFields() {
        val editor = prefs.edit()
        val k = keys[id].orEmpty().filterNot { it.isWhitespace() }
        if (k.isNotBlank()) editor.putString("api_key_$id", k)
        editor.putString("model_$id", models[id].orEmpty())
        editor.putString("endpoint_$id", endpoints[id].orEmpty().trim())
        if (info.needsAccountId) editor.putString("cf_account_id", accountId)
        editor.apply()
    }

    val activate: () -> Unit = {
        onActivate()
        saveFields()
        LlmClient.reloadOverrides(prefs)
        ProviderSelection.syncActiveTab(prefs)
    }
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
                onClick = activate,
                colors = RadioButtonDefaults.colors(selectedColor = Amber)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(info.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(info.tagline, color = Color(0xFF9A9A9A), fontSize = 11.sp)
            }
            ProviderToggle(prefs = prefs, id = id)
            Spacer(Modifier.width(6.dp))
            val h = health
            Text(
                when {
                    h != null && h.ok -> "🟢 " + ProviderHealth.ageLabel(h.atMs)
                    h != null -> "🔴 فشل " + ProviderHealth.ageLabel(h.atMs)
                    hasKey -> "✅ مفتاح"
                    info.keyless -> "🆓 بلا مفتاح"
                    else -> "⚪ بلا مفتاح محفوظ"
                },
                color = when {
                    h != null && h.ok -> Color(0xFF7CC47C)
                    h != null -> Color(0xFFFF6B6B)
                    hasKey || info.keyless -> Color(0xFF7CC47C)
                    else -> Color(0xFF8A8A8A)
                },
                fontSize = 11.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "▲" else "▼", color = Amber, fontSize = 12.sp)
        }

        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                TextButton(onClick = activate) {
                    Text(if (active) "✔ هذا هو المزود النشط" else "✔ استخدم هذا المزود", color = Amber, fontSize = 12.sp)
                }
                if (active) {
                    Text("● المزود النشط حالياً", color = Amber, fontSize = 12.sp)
                }
                health?.takeIf { !it.ok && it.note.isNotBlank() }?.let {
                    Text("آخر خطأ: " + it.note, color = Color(0xFFFF6B6B), fontSize = 11.sp)
                }

                Spacer(Modifier.height(4.dp))
                Text("🔑 مفتاح ${info.label}", color = Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = keys[id].orEmpty(),
                    onValueChange = { keys[id] = it; saveFields() },
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
                    // نحفظ الحقول أولاً ليطابق الاختبار ما ستستخدمه المحادثة فعلاً.
                    saveFields()
                    LlmClient.reloadOverrides(prefs)
                    val key = keys[id].orEmpty().ifBlank { prefs.getString("api_key_$id", "") ?: "" }
                    val account = accountId
                    val isServer = info.custom || id == "local_proxy" || id.startsWith(UserPlatforms.PREFIX)
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
                                val probe = "أجب بكلمة واحدة فقط: تم"
                                // نفس ترتيب المحادثة اليدوية: المباشر أولاً ثم الخادم الخارجي.
                                var reply = LlmClient.ask(probe, key, id, emptyList(), model, account, via = Channel.DIRECT)
                                if (LlmClient.isErrorReply(reply) && WebGateway.shouldRoute(id)) {
                                    val viaServer = LlmClient.ask(probe, key, id, emptyList(), model, account, via = Channel.GATEWAY)
                                    if (!LlmClient.isErrorReply(viaServer)) reply = viaServer
                                }
                                Outcome(list, model, reply)
                            }
                        }
                        testing = false
                        healthTick++
                        outcome.onSuccess { value ->
                            if (value.list.isNotEmpty()) {
                                modelCache[id] = value.list
                                ModelCatalog.saveCache(prefs, id, value.list)
                                if (id.startsWith(UserPlatforms.PREFIX)) UserPlatforms.storeModels(prefs, id, value.list)
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

                // زر الاختبار تحت المفتاح - يتكيف تلقائياً بناءً على نوع المزود
                var testButtonLabel by remember { mutableStateOf("") }
                var testButtonOnClick by remember { mutableStateOf<() -> Unit>({}) }

                when {
                    info.custom && endpoints[id].orEmpty().isNotBlank() -> {
                        testButtonLabel = "🧪 اختبار الاتصال بالخادم"
                        testButtonOnClick = {
                            testing = true
                            testStatus = "⏳ أختبر الاتصال بالخادم..."
                            testProvider()
                        }
                    }
                    id != "custom" -> {
                        testButtonLabel = "🧪 اختبار المفتاح والموديل"
                        testButtonOnClick = {
                            testing = true
                            testStatus = "⏳ أختبر المفتاح..."
                            testProvider()
                        }
                    }
                }

                if (testButtonLabel.isNotBlank()) {
                    TextButton(
                        enabled = !testing,
                        onClick = testButtonOnClick
                    ) {
                        Text(testButtonLabel, color = Amber, fontSize = 12.sp)
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
                        onValueChange = { models[id] = it; saveFields() },
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
                                models[id] = ""; saveFields()
                                modelMenuOpen = false
                            }
                        )
                        modelOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(if (option == activeModel) "$option  ✓" else option) },
                                onClick = {
                                    models[id] = option; saveFields()
                                    modelMenuOpen = false
                                }
                            )
                        }
                    }
                }
                if (id in setOf("opencode", "opencode-go", "opencode-console") && (activeModel == "big-pickle" || activeModel.endsWith("-free"))) {
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
                            val key = keys[id].orEmpty().trim().ifBlank { (prefs.getString("api_key_$id", "") ?: "").trim() }
                            val account = accountId.trim()
                            LlmClient.endpointOverrides =
                                LlmClient.endpointOverrides + (id to endpoints[id].orEmpty().trim())
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { LlmClient.listModels(id, key, account) }
                                }
                                val list = result.getOrNull()
                                if (!list.isNullOrEmpty()) {
                                    val ranked = if (id == "apinex") list else ModelRank.sorted(list)
                                    modelCache[id] = ranked
                                    ModelCatalog.saveCache(prefs, id, ranked)
                                    if (id.startsWith(UserPlatforms.PREFIX)) UserPlatforms.storeModels(prefs, id, ranked)
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
                    id in setOf("groq", "openrouter", "apinex", "opencode", "opencode-go", "opencode-console", "deepseek", "openai")
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (info.custom) "Base URL للخادم المخصص" else "عنوان وسيط (اختياري)",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = endpoints[id].orEmpty(),
                        onValueChange = { endpoints[id] = it; saveFields() },
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
                        when {
                            endpoints[id].orEmpty().isNotBlank() -> {
                                testButtonLabel = "🧪 اختبار الاتصال بالخادم"
                                testButtonOnClick = {
                                    testing = true
                                    testStatus = "⏳ أختبر الاتصال بالخادم..."
                                    testProvider()
                                }
                            }
                            else -> {}
                        }
                        if (testButtonLabel.isNotBlank()) {
                            TextButton(
                                enabled = !testing,
                                onClick = testButtonOnClick
                            ) {
                                Text(testButtonLabel, color = Amber, fontSize = 12.sp)
                            }
                            if (testStatus.isNotBlank()) {
                                Text(testStatus, color = Color(0xFFB0B0B0), fontSize = 12.sp)
                            }
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
