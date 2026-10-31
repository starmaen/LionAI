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

            // قسم الخوادم الجديد قبل قائمة المزودين
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "🌐 الخوادم",
                        color = Color(0xFFFFB800),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "خادم محلي",
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = prefs.getBoolean("enable_local_proxy", false),
                            onCheckedChange = { enabled ->
                                prefs.edit().putBoolean("enable_local_proxy", enabled).apply()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFFFB800),
                                checkedTrackColor = Color(0xFFFFB800).copy(alpha = 0.5f)
                            )
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "خادم ويب",
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = prefs.getBoolean("enable_web_gateway", false),
                            onCheckedChange = { enabled ->
                                prefs.edit().putBoolean("enable_web_gateway", enabled).apply()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFFFB800),
                                checkedTrackColor = Color(0xFFFFB800).copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }

            // قائمة المزودين
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "🤖 المزود",
                        color = Color(0xFFFFB800),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    // ... باقي محتوى قائمة المزودين
                }
            }
        }
    }
}
