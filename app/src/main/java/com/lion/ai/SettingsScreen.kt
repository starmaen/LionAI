package com.lion.ai

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: android.content.SharedPreferences,
    autoSpeak: Boolean,
    onAutoSpeakChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onOpenGitHub: () -> Unit = {},
    onOpenDevTools: () -> Unit = {},
    onOpenConnectors: () -> Unit = {}
) {
    val uriHandler = LocalUriHandler.current
    val ctx = LocalContext.current
    var provider by remember {
        mutableStateOf(
            (prefs.getString("provider", "gemini") ?: "gemini").let {
                if (providerInfo(it).group == ProviderGroup.API) it else "gemini"
            }
        )
    }
    val keys = remember {
        mutableStateMapOf<String, String>().apply {
            PROVIDERS.filter { it.group == ProviderGroup.API }.forEach { info ->
                put(info.id, prefs.getString("api_key_${info.id}", "") ?: "")
            }
        }
    }
    val models = remember {
        mutableStateMapOf<String, String>().apply {
            PROVIDERS.filter { it.group == ProviderGroup.API }.forEach { info ->
                put(info.id, prefs.getString("model_${info.id}", "") ?: "")
            }
        }
    }
    var accountId by remember {
        mutableStateOf(prefs.getString("cf_account_id", "") ?: "")
    }
    var smartTitles by remember { mutableStateOf(prefs.getBoolean("smart_titles", true)) }
    var autoRouteSetting by remember { mutableStateOf(prefs.getBoolean("auto_route", true)) }
    var manualAssist by remember { mutableStateOf(prefs.getBoolean("manual_assist", false)) }
    val settingsScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    val endpoints = remember {
        mutableStateMapOf<String, String>().apply {
            PROVIDERS.filter { it.group == ProviderGroup.API }.forEach { info ->
                put(info.id, prefs.getString("endpoint_" + info.id, "") ?: "")
            }
        }
    }
    val modelCache = remember {
        mutableStateMapOf<String, List<String>>().apply {
            PROVIDERS.filter { it.group == ProviderGroup.API }.forEach { info ->
                put(info.id, ModelCatalog.cached(prefs, info.id))
            }
        }
    }
    var confirmActions by remember { mutableStateOf(prefs.getBoolean("confirm_actions", true)) }
    var answerMode by remember { mutableStateOf(AnswerMode.read(prefs).id) }
    var answerLength by remember {
        mutableStateOf(prefs.getString("answer_length", "normal") ?: "normal")
    }
    var webGrounding by remember { mutableStateOf(prefs.getBoolean("web_grounding", true)) }
    var consensus by remember { mutableStateOf(prefs.getBoolean("consensus", false)) }
    var githubToken by remember { mutableStateOf(prefs.getString("github_token", "") ?: "") }
    var githubAgent by remember { mutableStateOf(prefs.getBoolean("agent_tools", true)) }
    var githubWrite by remember { mutableStateOf(prefs.getBoolean("agent_write", true)) }
    var githubRepo by remember { mutableStateOf(prefs.getString("github_default_repo", "") ?: "") }
    var githubStatus by remember { mutableStateOf("") }
    var githubTesting by remember { mutableStateOf(false) }

    val current = providerInfo(provider)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    fun ensureNotificationPermission() {
        if (!notificationPermissionGranted()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val versionName = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "1.4.0"
    }

    fun persist() {
        val editor = prefs.edit()
        val previous = (prefs.getString("provider", "gemini") ?: "gemini").trim()
        val savedBackend = (prefs.getString("local_proxy_backend", "") ?: "").trim()
        if (provider == "local_proxy" && savedBackend.isBlank() &&
            previous != "local_proxy" && previous != "custom"
        ) {
            editor.putString("local_proxy_backend", previous)
        }
        editor.putString("provider", provider)
        keys.forEach { (id, value) -> editor.putString("api_key_$id", value.filterNot { it.isWhitespace() }) }
        endpoints.forEach { (id, value) -> editor.putString("endpoint_$id", value.trim()) }
        models.forEach { (id, value) -> editor.putString("model_$id", value) }
        editor.putString("cf_account_id", accountId)
        editor.putBoolean("auto_speak", autoSpeak)
        editor.putBoolean("smart_titles", smartTitles)
        editor.putBoolean("auto_route", autoRouteSetting)
        editor.putBoolean("manual_assist", manualAssist)
        editor.putBoolean("confirm_actions", confirmActions)
        editor.putString("answer_mode", answerMode)
        editor.putString("answer_length", answerLength)
        editor.putBoolean("web_grounding", webGrounding)
        editor.putBoolean("consensus", consensus)
        editor.putString("github_token", githubToken.filterNot { it.isWhitespace() })
        editor.putBoolean("agent_tools", githubAgent)
        editor.putBoolean("agent_write", githubWrite)
        editor.putString("github_default_repo", githubRepo.trim())
        editor.apply()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Color(0xFF0D0D0D),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "⚙️ الإعدادات",
                        color = Color(0xFFFFB800),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { persist(); onBack() }) {
                        Icon(Icons.Filled.ArrowBack, "رجوع", tint = Color(0xFFFFB800))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A)
                )
            )
        },
        bottomBar = {
            Surface(color = Color(0xFF1A1A1A), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        persist()
                        onSave()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
                ) {
                    Text("💾 حفظ", color = Color(0xFF0D0D0D), fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "المفاتيح تُحفظ مشفّرة على الجهاز ولا تُرسل إلا إلى مزود الخدمة.",
                color = Color(0xFFB0B0B0),
                fontSize = 12.sp
            )
            if (SecurePrefs.usingPlaintextFallback) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠️ تعذّر تشغيل التخزين المشفّر على هذا الجهاز؛ المفاتيح محفوظة مؤقتاً بدون تشفير. " +
                        "أعد فتح التطبيق أو أعد تثبيته فوق النسخة الحالية لتفعيل التشفير.",
                    color = Color(0xFFE94560),
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(16.dp))

            SettingsSection(
                title = "🔑 المزودات الأساسية (API)",
                subtitle = "اضغط على المزود لفتح مفتاحه وموديله وزر الاختبار. الدائرة ● = المزود النشط",
                badge = "${PROVIDERS.count { it.group == ProviderGroup.API && it.id != "local_proxy" }} مزود",
                initiallyExpanded = true
            ) {
                val apiProviders = PROVIDERS.filter {
                    it.group == ProviderGroup.API && it.id != "local_proxy"
                }
                listOf(
                    "🔧 مخصص (Gateway / LiteLLM)" to apiProviders.filter { it.custom },
                    "🆓 بدون مفتاح" to apiProviders.filter { !it.custom && it.keyless },
                    "🟢 مجانية بمفتاح" to apiProviders.filter { !it.custom && !it.keyless && it.free },
                    "🔵 مدفوعة أو برصيد" to apiProviders.filter { !it.custom && !it.keyless && !it.free }
                ).forEach { (groupTitle, list) ->
                    if (list.isNotEmpty()) {
                        SettingsSection(
                            title = groupTitle,
                            badge = "${list.size}",
                            nested = true,
                            initiallyExpanded = list.any { it.id == provider }
                        ) {
                            list.forEach { info ->
                                ProviderCard(
                                    info = info,
                                    active = provider == info.id,
                                    onActivate = {
                                        // اختيار مزود محدد = وضع يدوي صريح، فلا يتجاوزه العقل المركزي.
                                        val previous = (prefs.getString("provider", "") ?: "").trim()
                                        provider = info.id
                                        autoRouteSetting = false
                                        val editor = prefs.edit()
                                            .putString("provider", info.id)
                                            .putBoolean("auto_route", false)
                                        // اختيار البروكسي بعد مزود مباشر يحفظ ذلك المزود كخادم خلفي.
                                        if (info.id == "local_proxy" && previous.isNotBlank() &&
                                            previous != "local_proxy" && previous != "custom"
                                        ) {
                                            editor.putString("local_proxy_backend", previous)
                                        }
                                        editor.apply()
                                    },
                                    keys = keys,
                                    models = models,
                                    endpoints = endpoints,
                                    modelCache = modelCache,
                                    accountId = accountId,
                                    onAccountIdChange = { accountId = it },
                                    prefs = prefs,
                                    scope = settingsScope
                                )
                            }
                        }
                    }
                }
            }

            // بطاقة الخادم المحلي (Proxy) لعرض البورت الذي اختاره نظام أندرويد تلقائياً
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "🖥️ الخادم المحلي (Proxy داخل التطبيق)",
                            color = Color(0xFFFFB800),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (LocalAIProxy.isRunning()) "الحالة: يعمل تلقائياً" else "الحالة: متوقف",
                            color = if (LocalAIProxy.isRunning()) Color(0xFF4CAF50) else Color(0xFFF44336),
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        var backend by remember { mutableStateOf(LocalAIProxy.backendProvider(prefs)) }
                        val backendOptions = remember {
                            PROVIDERS.filter {
                                it.group == ProviderGroup.API && it.id != "local_proxy" && it.id != "custom"
                            }
                        }
                        val backendName = backendOptions.firstOrNull { it.id == backend }?.label ?: backend
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (autoRouteSetting) Color(0xFFFFB800) else Color(0xFF2A2A2A)
                                ),
                                onClick = {
                                    // التلقائي لا يغيّر مزودك المحفوظ؛ العقل المركزي يدير
                                    // الاختيار مؤقتاً، وعند العودة لليدوي يعود مزودك كما كان.
                                    autoRouteSetting = true
                                    prefs.edit().putBoolean("auto_route", true).apply()
                                }
                            ) {
                                Text(
                                    "🧠 تلقائي (العقل المركزي)", fontSize = 11.sp, maxLines = 1,
                                    color = if (autoRouteSetting) Color(0xFF121212) else Color(0xFFE0E0E0)
                                )
                            }
                            Button(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!autoRouteSetting) Color(0xFFFFB800) else Color(0xFF2A2A2A)
                                ),
                                onClick = {
                                    // اليدوي يوقف تدخّل العقل المركزي ولا يغيّر المزود ولا الخادم.
                                    autoRouteSetting = false
                                    prefs.edit().putBoolean("auto_route", false).apply()
                                }
                            ) {
                                Text(
                                    "✋ يدوي (أختار المزود)", fontSize = 11.sp, maxLines = 1,
                                    color = if (!autoRouteSetting) Color(0xFF121212) else Color(0xFFE0E0E0)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        val chosenName = if (provider == RoutePolicy.LOCAL_PROXY) {
                            "الخادم المحلي ($backendName)"
                        } else {
                            providerInfo(provider).shortName
                        }
                        Text(
                            if (autoRouteSetting)
                                "🧠 تلقائي: العقل المركزي يختار الأفضل والأسرع حسب نوع المحادثة (عام/تعلّم/برمجة) وينتقل لمزود آخر عند الفشل. اختيارك المحفوظ لليدوي: $chosenName."
                            else
                                "✋ يدوي: المزود المختار = $chosenName. يعمل كما هو، والبديل يعمل فقط إذا توقف عن الرد.",
                            color = Color(0xFFB0B0B0),
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        // اختيار الخادم المحلي نفسه (وسيط) — مستقل عن مزودات API المباشرة.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (!autoRouteSetting && provider == RoutePolicy.LOCAL_PROXY)
                                        Color(0xFFFFB800) else Color(0xFF2A2A2A),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    provider = RoutePolicy.LOCAL_PROXY
                                    autoRouteSetting = false
                                    prefs.edit()
                                        .putString("provider", RoutePolicy.LOCAL_PROXY)
                                        .putString("local_proxy_backend", backend)
                                        .putBoolean("auto_route", false)
                                        .apply()
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "🖥️ استخدم الخادم المحلي (وسيط → $backendName)",
                                color = if (!autoRouteSetting && provider == RoutePolicy.LOCAL_PROXY)
                                    Color(0xFF121212) else Color(0xFFE0E0E0),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        backendOptions.chunked(3).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowItems.forEach { info ->
                                    // شبكة الخادم: تختار مزودَ routing الخادم فقط، مستقلة تماماً
                                    // عن المزود الفردي النشط في المحادثة.
                                    val selected = info.id == backend
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(
                                                if (selected) Color(0xFFFFB800) else Color(0xFF2A2A2A),
                                                RoundedCornerShape(16.dp)
                                            )
                                            .clickable {
                                                // اختيار مزود الخادم = تفعيل يدوي صريح:
                                                // تتحول المحادثة كلها إلى الخادم بهذا المزود فوراً.
                                                backend = info.id
                                                provider = RoutePolicy.LOCAL_PROXY
                                                autoRouteSetting = false
                                                prefs.edit()
                                                    .putString("local_proxy_backend", info.id)
                                                    .putString("provider", RoutePolicy.LOCAL_PROXY)
                                                    .putBoolean("auto_route", false)
                                                    .apply()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            if (info.id == "openai") "ChatGPT" else info.shortName,
                                            color = if (selected) Color(0xFF121212) else Color(0xFFE0E0E0),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        val selectedInfo = backendOptions.firstOrNull { it.id == backend }

                        // المكان الوحيد المطلوب من المستخدم: مفتاح المزود المختار
                        if (selectedInfo != null && !selectedInfo.keyless && !backend.startsWith("imp_")) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = keys[backend] ?: "",
                                onValueChange = { value ->
                                    keys[backend] = value
                                    prefs.edit()
                                        .putString("api_key_$backend", value.filterNot { it.isWhitespace() })
                                        .apply()
                                },
                                label = { Text("🔑 مفتاح API لـ ${selectedInfo.shortName}", fontSize = 11.sp) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = TextStyle(fontSize = 12.sp, color = Color(0xFFE0E0E0))
                            )
                            Text(
                                "يُحفظ تلقائياً — بعد إدخاله يعمل المزود مباشرة في المحادثة، وعبر الخادم المحلي عند اختياره.",
                                color = Color(0xFF7A7A7A),
                                fontSize = 10.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        val activeBackendModel = (prefs.getString("model_$backend", "") ?: "")
                            .ifBlank { selectedInfo?.defaultModel ?: "" }
                        Text(
                            "الموديل التلقائي: ${activeBackendModel.ifBlank { "غير محدد" }} — يبدأ الخادم تلقائياً عند فتح التطبيق.",
                            color = Color(0xFF7A7A7A),
                            fontSize = 11.sp
                        )

                        // تبويب فرعي: مزودات خادم الويب (Cloudflare/Render) — اختياري وغير إلزامي.
                        Spacer(Modifier.height(10.dp))
                        var gwMode by remember {
                            mutableStateOf(
                                WebGateway.normalizeMode(
                                    prefs.getString(WebGateway.KEY_MODE, WebGateway.MODE_OFF)
                                )
                            )
                        }
                        var gwUrl by remember { mutableStateOf(prefs.getString(WebGateway.KEY_URL, "").orEmpty()) }
                        var gwToken by remember { mutableStateOf(prefs.getString(WebGateway.KEY_TOKEN, "").orEmpty()) }
                        var gwReport by remember { mutableStateOf("") }
                        var gwBusy by remember { mutableStateOf(false) }
                        var gwModels by remember { mutableStateOf<List<String>>(emptyList()) }
                        var gwModelsFor by remember { mutableStateOf("") }
                        var gwModelsBusy by remember { mutableStateOf(false) }
                        var gwSelectedModel by remember { mutableStateOf("") }
                        fun saveGateway() {
                            prefs.edit()
                                .putString(WebGateway.KEY_MODE, gwMode)
                                .putString(WebGateway.KEY_URL, gwUrl.trim())
                                .putString(WebGateway.KEY_TOKEN, gwToken.filterNot { it.isWhitespace() })
                                .apply()
                            LlmClient.reloadOverrides(prefs)
                        }
                        SettingsSection(
                            title = "🌐 مزودات خادم الويب",
                            subtitle = "اختياري: مباشر أو عبر Cloudflare/Render — افحص ثم فعّل",
                            badge = WebGateway.modeLabel(gwMode),
                            nested = true
                        ) {
                            Text(
                                "الوضع المختار: " + when (gwMode) {
                                    WebGateway.MODE_AUTO -> "يجرّب الخادم أولاً ثم يعود تلقائياً للمباشر عند فشله."
                                    WebGateway.MODE_ONLY -> "عبر الخادم فقط. للعودة اضغط «مباشر»."
                                    else -> "مباشر فقط — لا يستخدم أي خادم خارجي."
                                },
                                color = Color(0xFF9A9A9A),
                                fontSize = 10.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    WebGateway.MODE_OFF to "🔌 مباشر",
                                    WebGateway.MODE_AUTO to "🌐 خادم أولاً",
                                    WebGateway.MODE_ONLY to "☁️ خادم فقط"
                                ).forEach { (mode, label) ->
                                    Button(
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (gwMode == mode) Color(0xFFFFB800) else Color(0xFF2A2A2A)
                                        ),
                                        onClick = {
                                            gwMode = mode
                                            saveGateway()
                                        }
                                    ) {
                                        Text(
                                            label,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            color = if (gwMode == mode) Color(0xFF121212) else Color(0xFFE0E0E0)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = gwUrl,
                                onValueChange = { gwUrl = it; saveGateway() },
                                label = { Text("رابط الخادم (Base URL)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("https://name.workers.dev/v1", fontSize = 10.sp) },
                                textStyle = TextStyle(fontSize = 12.sp, color = Color(0xFFE0E0E0))
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = gwToken,
                                onValueChange = { gwToken = it; saveGateway() },
                                label = { Text("مفتاح الخادم (RELAY_TOKEN)", fontSize = 11.sp) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = TextStyle(fontSize = 12.sp, color = Color(0xFFE0E0E0))
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    enabled = !gwBusy,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                                    onClick = {
                                        gwBusy = true
                                        gwReport = "⏳ أفحص الخادم…"
                                        saveGateway()
                                        settingsScope.launch {
                                            gwReport = withContext(Dispatchers.IO) { WebGateway.test(prefs) }
                                            gwBusy = false
                                        }
                                    }
                                ) { Text("1) الخادم", color = Color(0xFFFFB800), fontSize = 11.sp, maxLines = 1) }
                                Button(
                                    enabled = !gwBusy,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                                    onClick = {
                                        val pid = gwModelsFor.ifBlank { backend }
                                        val model = gwSelectedModel.ifBlank { WebGateway.defaultModel(pid) }
                                        gwBusy = true
                                        gwReport = "⏳ أفحص ${WebGateway.providerLabel(pid)} عبر الخادم…"
                                        saveGateway()
                                        settingsScope.launch {
                                            gwReport = withContext(Dispatchers.IO) {
                                                WebGateway.testProvider(prefs, pid, model)
                                            }
                                            gwBusy = false
                                        }
                                    }
                                ) { Text("2) المزود", color = Color(0xFFFFB800), fontSize = 11.sp, maxLines = 1) }
                                Button(
                                    enabled = !gwBusy,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                                    onClick = {
                                        gwBusy = true
                                        gwReport = "⏳ أحدّث موديلات الخادم…"
                                        saveGateway()
                                        settingsScope.launch {
                                            val models = withContext(Dispatchers.IO) { WebGateway.listModels(prefs) }
                                            val pid = gwModelsFor.ifBlank { backend }
                                            if (models.isNotEmpty() && pid.isNotBlank()) {
                                                val subs = models.filter { it.startsWith("$pid/") }
                                                    .map { it.removePrefix("$pid/") }
                                                if (subs.isNotEmpty()) {
                                                    gwModels = subs.distinct().take(30)
                                                }
                                            }
                                            gwReport = if (models.isEmpty()) {
                                                "⚠️ لا موديلات — تحقق من الرابط و RELAY_TOKEN."
                                            } else {
                                                "✅ ${models.size} موديلاً متاحاً من الخادم."
                                            }
                                            gwBusy = false
                                        }
                                    }
                                ) { Text("3) النماذج", color = Color(0xFFFFB800), fontSize = 11.sp, maxLines = 1) }
                            }
                            if (gwReport.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(gwReport.take(1200), color = Color.White, fontSize = 10.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "المزودات المدعومة عبر الخادم (اختر للفحص ثم فعّل):",
                                color = Color(0xFFB0B0B0),
                                fontSize = 11.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            WebGateway.providers
                                .chunked(3)
                                .forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        rowItems.forEach { info ->
                                            val active = info.id == backend && provider == RoutePolicy.LOCAL_PROXY
                                            val browsing = info.id == gwModelsFor
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .background(
                                                        when {
                                                            active -> Color(0xFFFFB800)
                                                            browsing -> Color(0xFF64B5F6)
                                                            else -> Color(0xFF2A2A2A)
                                                        },
                                                        RoundedCornerShape(16.dp)
                                                    )
                                                    .clickable {
                                                        // اختيار للفحص فقط — لا يفعّل شيئاً بعد.
                                                        gwModelsFor = info.id
                                                        gwSelectedModel = ""
                                                        gwModelsBusy = true
                                                        gwModels = emptyList()
                                                        gwReport = "ℹ️ ${info.shortName}: افحص المزود ثم اختر موديلاً أو اضغط «تفعيل»."
                                                        settingsScope.launch {
                                                            val all = withContext(Dispatchers.IO) {
                                                                WebGateway.listModels(prefs)
                                                            }
                                                            val subs = all
                                                                .filter { it.startsWith("${info.id}/") }
                                                                .map { it.removePrefix("${info.id}/") }
                                                            gwModels = (subs.ifEmpty { listOf(info.defaultModel) })
                                                                .distinct().take(30)
                                                            gwModelsBusy = false
                                                        }
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    info.label + if (active) " ✓" else "",
                                                    color = if (active || browsing) Color(0xFF121212) else Color(0xFFE0E0E0),
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontWeight = if (active || browsing) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                        }
                                        repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                            if (gwModelsFor.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "🧩 الموديلات الفرعية لـ ${WebGateway.providerLabel(gwModelsFor)}:",
                                    color = Color(0xFFFFB800),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                when {
                                    gwModelsBusy -> Text(
                                        "⏳ أجلب الموديلات من الخادم…",
                                        color = Color(0xFF9A9A9A),
                                        fontSize = 10.sp
                                    )
                                    gwModels.isEmpty() -> Text(
                                        "لا موديلات معروضة — اكتب الموديل يدوياً في بطاقة المزود بالأعلى، أو اضغط «📋 الموديلات».",
                                        color = Color(0xFF9A9A9A),
                                        fontSize = 10.sp
                                    )
                                    else -> gwModels.chunked(2).forEach { modelRow ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            modelRow.forEach { modelId ->
                                                val chosen = modelId == gwSelectedModel
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .background(
                                                            if (chosen) Color(0xFF4CAF50) else Color(0xFF1F1F1F),
                                                            RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable {
                                                            gwSelectedModel = modelId
                                                            prefs.edit()
                                                                .putString("model_$gwModelsFor", modelId)
                                                                .putString(WebGateway.KEY_MODE, WebGateway.MODE_AUTO)
                                                                .apply()
                                                            gwMode = WebGateway.MODE_AUTO
                                                            LlmClient.reloadOverrides(prefs)
                                                            gwReport = "✅ حُفظ $gwModelsFor/$modelId. اختر المزود نفسه من قائمة المزودات، وسيمرّ عبر الخادم في وضع «خادم أولاً»."
                                                        }
                                                        .padding(horizontal = 6.dp, vertical = 6.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        modelId,
                                                        color = if (chosen) Color(0xFF0D0D0D) else Color(0xFFCFCFCF),
                                                        fontSize = 9.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            repeat(2 - modelRow.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                                if (gwSelectedModel.isNotBlank()) {
                                    Text(
                                        "الموديل المختار: $gwSelectedModel",
                                        color = Color(0xFF4CAF50),
                                        fontSize = 10.sp
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Button(
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                        onClick = {
                                            prefs.edit()
                                                .putString("model_$gwModelsFor", gwSelectedModel)
                                                .putString(WebGateway.KEY_MODE, WebGateway.MODE_AUTO)
                                                .apply()
                                            gwMode = WebGateway.MODE_AUTO
                                            LlmClient.reloadOverrides(prefs)
                                            gwReport = "✅ حُفظ $gwModelsFor/$gwSelectedModel. اختر المزود من القائمة الرئيسية — سيمرّ عبر الخادم تلقائياً."
                                        }
                                    ) { Text("💾 حفظ الموديل", color = Color(0xFF0D0D0D), fontSize = 11.sp) }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "🔒 الوضع المباشر يبقى متاحاً دائماً: مفاتيح المزودات محفوظة كما هي، ويمكنك العودة بضغطة «مباشر». وعند تعطّل الخادم في وضع «خادم أولاً» يكمل التطبيق تلقائياً بالمفتاح المباشر.",
                                color = Color(0xFF7A7A7A),
                                fontSize = 10.sp
                            )
                        }

                        // مزودون محفوظون سابقاً (اختيار سريع فقط — دون رابط أو كتابة)
                        var importedVersion by remember { mutableStateOf(0) }
                        val importedList = remember(importedVersion) { ImportedProviders.list(prefs) }
                        if (importedList.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "🌐 مزودون إضافيون محفوظون:",
                                color = Color(0xFFB0B0B0),
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            importedList.chunked(3).forEach { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    rowItems.forEach { entry ->
                                        val selected = entry.id == backend
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .background(
                                                    if (selected) Color(0xFF64B5F6) else Color(0xFF2A2A2A),
                                                    RoundedCornerShape(16.dp)
                                                )
                                                .clickable {
                                                    // مزود خلفي مستورد = تفعيل يدوي للخادم به فوراً.
                                                    backend = entry.id
                                                    provider = RoutePolicy.LOCAL_PROXY
                                                    autoRouteSetting = false
                                                    prefs.edit()
                                                        .putString("local_proxy_backend", entry.id)
                                                        .putString("provider", RoutePolicy.LOCAL_PROXY)
                                                        .putBoolean("auto_route", false)
                                                        .apply()
                                                }
                                                .padding(horizontal = 8.dp, vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                entry.label,
                                                color = if (selected) Color(0xFF121212) else Color(0xFFE0E0E0),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        var importJson by remember { mutableStateOf("") }
                        var importNote by remember { mutableStateOf("") }
                        Text(
                            "➕ استيراد مزود/منصة (OpenAI-Compatible JSON)",
                            color = Color(0xFF64B5F6),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = importJson,
                            onValueChange = { importJson = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                            placeholder = {
                                Text(
                                    """[{"label":"Gateway","baseUrl":"https://host/v1","model":"hf/deepseek-ai/DeepSeek-V4.1-Flash-1017","apiKey":"..."}]""",
                                    color = Color(0xFF707070),
                                    fontSize = 10.sp
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF64B5F6),
                                unfocusedBorderColor = Color(0xFF3A3A3A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = {
                                val added = ImportedProviders.importFrom(prefs, importJson)
                                importNote = if (added > 0) {
                                    "✅ أُضيف $added مزود. اضغط اسمه أدناه لتفعيله."
                                } else {
                                    "⚠️ لم يُضف شيء — تحقق من JSON (baseUrl مطلوب)."
                                }
                                importJson = ""
                                importedVersion++
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64B5F6))
                        ) { Text("استيراد", color = Color(0xFF0D0D0D), fontSize = 12.sp) }
                        if (importNote.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(importNote, color = Color(0xFFB0B0B0), fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        var proxyReport by remember { mutableStateOf("") }
                        var proxyBusy by remember { mutableStateOf(false) }
                        Text("🧪 فحص الخادم ← فحص المزود ← تحديث النماذج", color = Color(0xFFFFB800), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                enabled = !proxyBusy,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                                onClick = {
                                    proxyBusy = true
                                    proxyReport = "⏳ أفحص الخادم المحلي…"
                                    settingsScope.launch {
                                        proxyReport = withContext(Dispatchers.IO) { LocalAIProxy.healthCheck() }
                                        proxyBusy = false
                                    }
                                }
                            ) { Text("1) الخادم", color = Color(0xFFFFB800), fontSize = 11.sp, maxLines = 1) }
                            Button(
                                enabled = !proxyBusy,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                                onClick = {
                                    proxyBusy = true
                                    proxyReport = "⏳ أفحص المزود الخلفي مباشرة ثم عبر الخادم…"
                                    settingsScope.launch {
                                        proxyReport = withContext(Dispatchers.IO) { LocalAIProxy.providerCheck(prefs) }
                                        proxyBusy = false
                                    }
                                }
                            ) { Text("2) المزود", color = Color(0xFFFFB800), fontSize = 11.sp, maxLines = 1) }
                            Button(
                                enabled = !proxyBusy,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                                onClick = {
                                    proxyBusy = true
                                    proxyReport = "⏳ أستدعي قائمة النماذج من الإنترنت…"
                                    settingsScope.launch {
                                        proxyReport = withContext(Dispatchers.IO) { LocalAIProxy.refreshModels(prefs) }
                                        proxyBusy = false
                                    }
                                }
                            ) { Text("3) النماذج", color = Color(0xFFFFB800), fontSize = 11.sp, maxLines = 1) }
                        }
                        if (proxyReport.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(proxyReport.take(1500), color = Color.White, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        val proxyUrl = LocalAIProxy.getUrl()
                        if (proxyUrl.isNotEmpty()) {
                            Text(
                                "رابط الخادم المحلي (تلقائي — لا حاجة لنسخه):",
                                color = Color(0xFFB0B0B0),
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    proxyUrl,
                                    color = Color(0xFF64B5F6),
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("LionAI Proxy", proxyUrl))
                                    },
                                ) {
                                    Text("📋 نسخ", color = Color(0xFFFFB800), fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "كيف يعمل: الخادم المحلي يعمل تلقائياً على 127.0.0.1. اختر المزود من الشبكة أعلاه وأدخل مفتاحه مرة واحدة، فيوجّه الخادم كل طلبات التطبيق إليه تلقائياً ويعيد الرد. لا حاجة لأي إعداد يدوي آخر.",
                                color = Color(0xFF7A7A7A),
                                fontSize = 11.sp,
                                maxLines = 3
                            )
                        } else {
                            Text(
                                "لم يبدأ الخادم بعد. أعد فتح التطبيق لبدء الخادم المحلي تلقائياً.",
                                color = Color(0xFF7A7A7A),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            listOf(ProviderGroup.WEB, ProviderGroup.MEDIA).forEach { group ->
                val groupProviders = PROVIDERS.filter { it.group == group }
                if (groupProviders.isNotEmpty()) {
                    SettingsSection(
                        title = if (group == ProviderGroup.WEB) "🌐 مزودات صفحات الويب" else "🎨 مزودات الوسائط",
                        subtitle = "هذا مزود ويب/وسائط: يُفتح داخل التطبيق من هنا أو من زر 🌐، ولا يحتاج مفتاحاً.",
                        badge = "${groupProviders.size} موقع"
                    ) {
                        groupProviders.forEach { info ->
                            val openPage: () -> Unit = {
                                info.webviewUrl?.let { url ->
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(ctx, ArenaActivity::class.java).putExtra("ARENA_URL", url)
                                        )
                                    }
                                }
                                Unit
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { openPage() }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        info.label + "  " + (if (group == ProviderGroup.MEDIA) "🎨" else "🌐"),
                                        color = Color.White
                                    )
                                    Text(
                                        info.tagline + " — يُفتح داخل التطبيق",
                                        color = Color(0xFFB0B0B0),
                                        fontSize = 12.sp
                                    )
                                }
                                TextButton(onClick = { openPage() }) {
                                    Text("فتح ↗", color = Color(0xFFFFB800), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            SettingsSection(
                title = "⚙️ الإعدادات العامة والعقل المركزي",
                subtitle = "القراءة التلقائية، عناوين المحادثات، التأكيدات، واختيار النموذج تلقائياً"
            ) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = autoSpeak,
                        onCheckedChange = onAutoSpeakChange,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFB800))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("قراءة الردود تلقائياً", color = Color.White)
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = smartTitles,
                        onCheckedChange = { smartTitles = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFB800))
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("عناوين ذكية للمحادثات", color = Color.White)
                        Text("طلب قصير للنموذج بعد أول رد لتسمية المحادثة بموضوعها", color = Color(0xFF9A9A9A), fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = confirmActions,
                        onCheckedChange = { confirmActions = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFB800))
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("تأكيد قبل إرسال الرسائل والاتصال", color = Color.White)
                        Text("يُنصح بإبقائه مفعّلاً لتفادي إرسال لشخص خاطئ", color = Color(0xFF9A9A9A), fontSize = 11.sp)
                    }
                }

            }

            SettingsSection(
                title = "🎚️ جودة الإجابات والمصادر",
                subtitle = "وضع الإجابة وطولها والبحث بالويب وإجماع النماذج"
            ) {
                Text(
                    "اختر مقدار الدقة مقابل السرعة. «دقيق» يمنع النماذج السريعة الضعيفة من الإجابة عن الأسئلة المعرفية.",
                    color = Color(0xFFB0B0B0),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                Text("وضع الإجابة:", color = Color.White, fontSize = 13.sp)
                AnswerMode.values().forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { answerMode = mode.id }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = answerMode == mode.id,
                            onClick = { answerMode = mode.id },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFB800))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.label, color = Color.White, fontSize = 14.sp)
                            Text(mode.hint, color = Color(0xFF9A9A9A), fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("طول الرد:", color = Color.White, fontSize = 13.sp)
                listOf(
                    Triple("short", "مختصر", "أهم النقاط فقط"),
                    Triple("normal", "عادي", "الطول الافتراضي المتوازن"),
                    Triple("long", "مفصّل", "شرح أوسع وأمثلة")
                ).forEach { (id, label, hint) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { answerLength = id }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = answerLength == id,
                            onClick = { answerLength = id },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFB800))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(label, color = Color.White, fontSize = 14.sp)
                            Text(hint, color = Color(0xFF9A9A9A), fontSize = 11.sp)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { webGrounding = !webGrounding }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = webGrounding,
                        onCheckedChange = { webGrounding = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFB800))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🌐 بحث ويب تلقائي للمعلومات", color = Color.White, fontSize = 14.sp)
                        Text(
                            "للأسئلة المعرفية والبرمجية: يجلب مقتطفات من ويكيبيديا والبحث العام، ومن GitHub وStack Overflow وXDA عند الحاجة، ثم يعرض المصادر أسفل الرد. يرسل نص سؤالك إلى خدمات البحث، ولا يعمل مع المرفقات.",
                            color = Color(0xFF9A9A9A),
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { consensus = !consensus }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = consensus,
                        onCheckedChange = { consensus = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFB800))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🧩 إجماع النماذج للأسئلة المعرفية", color = Color.White, fontSize = 14.sp)
                        Text(
                            "يسأل نموذجين قويين، يقارن إجابتيهما محلياً، ثم يدمجهما في إجابة واحدة مع نسبة الاتفاق. يضاعف الطلبات والزمن، ويتطلب توفر مزودين قويين.",
                            color = Color(0xFF9A9A9A),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            SettingsSection(
                title = "🧰 أدوات الذكاء والتطوير",
                subtitle = "GitHub والملفات والموصلات (Drive/Gmail/Calendar...)"
            ) {
                Text(
                    "تتيح للنموذج استخدام أدوات GitHub والويب والملفات وقواعد البيانات ومحلل الكود والموصلات (Drive/Gmail/Calendar/Notion/Slack) عند الحاجة، مع الحفاظ على كل ميزات المحادثة والوسائط.",
                    color = Color(0xFFB0B0B0),
                    fontSize = 12.sp
                )
                Text(
                    "أفضل إعداد: اترك «تشغيل الأدوات» و«السماح بالكتابة» مفعّلين. المستودع الافتراضي اختياري ويسهّل أوامر مثل «عدّل README». " +
                        "أزرار الفتح روابط اختصار فقط. لبناء Actions أضف صلاحية Actions (أو workflow) للتوكن.",
                    color = Color(0xFF8F8F8F),
                    fontSize = 11.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { githubAgent = !githubAgent }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = githubAgent,
                        onCheckedChange = { githubAgent = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFB800))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("تشغيل أدوات الوكيل تلقائياً", color = Color.White, fontSize = 14.sp)
                        Text("يجعل النموذج يفحص ويعدّل ويبحث بدلاً من الإجابة النظرية فقط.", color = Color(0xFF9A9A9A), fontSize = 11.sp)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { githubWrite = !githubWrite }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = githubWrite,
                        onCheckedChange = { githubWrite = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFB800))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("السماح للذكاء بالكتابة والحذف والدفع", color = Color.White, fontSize = 14.sp)
                        Text("يشمل ملفات مساحة العمل وتعديل GitHub. أوقفه إن أردت وضع القراءة فقط.", color = Color(0xFF9A9A9A), fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("توكن GitHub (Personal Access Token)", color = Color.White, fontSize = 13.sp)
                OutlinedTextField(
                    value = githubToken,
                    onValueChange = { githubToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("github_pat_... أو ghp_...", color = Color(0xFF707070)) },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFFB800),
                        unfocusedBorderColor = Color(0xFF2A2A2A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFFFFB800)
                    )
                )
                Text(
                    "الصلاحيات المقترحة: repo + workflow (أو Contents + Issues + Pull requests + Actions) وread:user. يُخزَّن التوكن مشفّراً على الجهاز فقط.",
                    color = Color(0xFF9A9A9A),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                Text("المستودع الافتراضي (owner/repo) — اختياري", color = Color.White, fontSize = 13.sp)
                OutlinedTextField(
                    value = githubRepo,
                    onValueChange = { githubRepo = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("starmaen/LionAI", color = Color(0xFF707070)) },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFFB800),
                        unfocusedBorderColor = Color(0xFF2A2A2A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFFFFB800)
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !githubTesting,
                        onClick = {
                            githubTesting = true
                            githubStatus = "⏳ أختبر الاتصال..."
                            val tokenNow = githubToken
                            settingsScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { GitHubClient.whoAmI(tokenNow) }
                                }
                                val user = result.getOrNull()
                                githubStatus = if (user != null) {
                                    "✅ متصل بحساب ${user.optString("login")} (${user.optInt("public_repos")} مستودع عام)"
                                } else {
                                    "⚠️ " + (result.exceptionOrNull()?.message ?: "فشل الاتصال")
                                }
                                githubTesting = false
                            }
                        }
                    ) { Text("🧪 اختبار اتصال GitHub", color = Color(0xFFFFB800), fontSize = 12.sp) }
                    TextButton(onClick = { persist(); onOpenGitHub() }) {
                        Text("🐙 فتح GitHub", color = Color(0xFFFFB800), fontSize = 12.sp)
                    }
                }
                if (githubStatus.isNotBlank()) {
                    Text(githubStatus, color = Color(0xFFB0B0B0), fontSize = 12.sp)
                }
                TextButton(onClick = { persist(); onOpenDevTools() }) {
                    Text("🧰 فتح أدوات المطوّر ومساحة العمل", color = Color(0xFFFFB800), fontSize = 12.sp)
                }
                TextButton(onClick = { persist(); onOpenConnectors() }) {
                    Text("🔌 ربط الحسابات: Drive · Gmail · Calendar · Notion · Slack", color = Color(0xFFFFB800), fontSize = 12.sp)
                }
            }

            SettingsSection(
                title = "🔔 التنبيهات والتذكيرات",
                subtitle = "اختبار الإشعارات والمنبه والأذونات"
            ) {
                var alarmStatus by remember { mutableStateOf("") }
                Text(
                    if (Notifier.enabled(ctx)) "الإشعارات: مفعّلة في النظام ✅" else "الإشعارات: معطّلة في النظام ❌ — فعّلها من الزر أدناه",
                    color = Color(0xFFB0B0B0),
                    fontSize = 12.sp
                )
                TextButton(onClick = {
                    if (!notificationPermissionGranted()) {
                        ensureNotificationPermission()
                        alarmStatus = "اسمح بالإشعارات من النافذة الظاهرة ثم اضغط «اختبار إشعار الآن» مرة أخرى."
                    } else {
                        val ok = Notifier.show(ctx, 7001, "🔔 اختبار", "إذا ظهر لك هذا الإشعار فالتنبيهات تعمل بشكل سليم.")
                        alarmStatus = if (ok) "تم إرسال إشعار تجريبي. هل ظهر؟" else "⚠️ تعذر إرسال الإشعار (تحقق من صلاحية الإشعارات)."
                    }
                }) { Text("🔔 اختبار إشعار الآن", color = Color(0xFFFFB800), fontSize = 13.sp) }
                TextButton(onClick = {
                    ensureNotificationPermission()
                    val err = Scheduler.add(
                        ctx, prefs,
                        ScheduledItem(Scheduler.newId(), ActionKind.REMINDER, System.currentTimeMillis() + 15_000L, "", "", "حان موعد اختبار المنبه ✅")
                    )
                    alarmStatus = if (err == null) {
                        "سيصلك تذكير تجريبي بعد 15 ثانية. أغلق التطبيق أو اقفل الشاشة لاختبار الخلفية."
                    } else {
                        "⚠️ تعذرت الجدولة: $err"
                    }
                }) { Text("⏱ اختبار المنبه بعد 15 ثانية (صوت + شاشة)", color = Color(0xFFFFB800), fontSize = 13.sp) }
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        )
                    }
                }) { Text("⚙️ إعدادات إشعارات التطبيق", color = Color(0xFFFFB800), fontSize = 13.sp) }
                if (Build.VERSION.SDK_INT >= 34) {
                    TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                    Uri.parse("package:" + ctx.packageName)
                                )
                            )
                        }
                    }) { Text("📲 السماح بالمنبه بملء الشاشة", color = Color(0xFFFFB800), fontSize = 13.sp) }
                }
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + ctx.packageName)
                            )
                        )
                    }
                }) { Text("🔋 استثناء التطبيق من توفير البطارية", color = Color(0xFFFFB800), fontSize = 13.sp) }
                if (alarmStatus.isNotBlank()) {
                    Text(alarmStatus, color = Color(0xFFB0B0B0), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Color(0xFF2A2A2A))
            Spacer(Modifier.height(12.dp))
            Text(
                "الإصدار $versionName",
                color = Color(0xFFB0B0B0),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "تطوير star syria",
                color = Color(0xFFFFB800),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "starsyria2500@gmail.com",
                color = Color(0xFFFFB800),
                fontSize = 13.sp,
                modifier = Modifier.clickable {
                    runCatching { uriHandler.openUri("mailto:starsyria2500@gmail.com") }
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
