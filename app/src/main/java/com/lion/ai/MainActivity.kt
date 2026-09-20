package com.lion.ai

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

data class Message(val text: String, val isUser: Boolean, val ytUrl: String? = null, val ytmUrl: String? = null)
data class Chat(val id: String, val title: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFFB800),
                    background = Color(0xFF0D0D0D),
                    surface = Color(0xFF1A1A1A)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D0D0D)) {
                    LionApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LionApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("lion_prefs", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var showSettings by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var currentChat by remember { mutableStateOf("default") }
    var chats by remember { mutableStateOf(listOf<Chat>()) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var autoSpeak by remember { mutableStateOf(false) }
    var chosenFileUri by remember { mutableStateOf<Uri?>(null) }
    var chosenFileName by remember { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS))
        tts = TextToSpeech(context) { }
    }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!t.isNullOrEmpty()) input = t
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            chosenFileUri = uri
            chosenFileName = uri.lastPathSegment?.substringAfterLast("/") ?: "ملف"
        }
    }

    fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SCREENSHOT).apply {
            action = RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
        }
        voiceLauncher.launch(intent)
    }

    fun scheduleReminder(text: String, seconds: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val i = Intent(context, ReminderReceiver::class.java).putExtra("text", text)
        val pi = PendingIntent.getBroadcast(context, System.currentTimeMillis().toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + seconds * 1000L, pi)
    }

    fun detectReminder(msg: String): Pair<String, Int>? {
        val regex = Regex("بعد\\s+(\\d+)\\s*(ثانية|ثواني|دقيقة|دقائق|ساعة|ساعات)")
        val m = regex.find(msg) ?: return null
        val num = m.groupValues[1].toIntOrNull() ?: return null
        val unit = m.groupValues[2]
        val s = when { unit.contains("ثان") -> num; unit.contains("دقيق") -> num * 60; unit.contains("ساع") -> num * 3600; else -> return null }
        val text = msg.replace(m.value, "").replace("ذكرني", "").replace("أن", "").replace("ان", "").trim()
        return Pair(text.ifEmpty { "تذكير" }, s)
    }

    fun detectSms(msg: String): Pair<String, String>? {
        val regex = Regex("(?:أرسل|ارسل)\\s+(?:رسالة|رساله)\\s+(?:للرقم|لرقم|إلى|الى)\\s+(\\S+)\\s*[:：]?\\s*(.+)")
        val m = regex.find(msg) ?: return null
        return Pair(m.groupValues[1].trim(), m.groupValues[2].trim())
    }

    fun sendSms(phone: String, text: String) {
        try { @Suppress("DEPRECATION") SmsManager.getDefault().sendTextMessage(phone, null, text, null, null) } catch (e: Exception) { }
    }

    fun loadChat(id: String) {
        val saved = prefs.getString("chat_$id", "") ?: ""
        messages = if (saved.isEmpty()) emptyList() else {
            saved.split("\n---\n").filter { it.isNotEmpty() }.map { line ->
                val parts = line.split("|", limit = 2)
                Message(parts.getOrElse(1) { "" }, parts.getOrElse(0) { "u" } == "u")
            }
        }
        currentChat = id
    }

    fun saveChat() {
        val serialized = messages.joinToString("\n---\n") { (if (it.isUser) "u" else "a") + "|" + it.text.replace("\n", " ") }
        prefs.edit().putString("chat_$currentChat", serialized).apply()
        // تحديث عنوان المحادثة
        val firstUser = messages.firstOrNull { it.isUser }?.text
        if (firstUser != null) prefs.edit().putString("chat_title_$currentChat", firstUser.take(30)).apply()
    }

    fun loadChatsList() {
        val ids = prefs.getString("chat_ids", "") ?: ""
        chats = ids.split(",").filter { it.isNotEmpty() }.map {
            Chat(it, prefs.getString("chat_title_$it", "محادثة") ?: "محادثة")
        }
    }

    LaunchedEffect(Unit) {
        loadChatsList()
        loadChat(currentChat)
    }

    if (showSettings) {
        SettingsScreen(prefs, autoSpeak, { autoSpeak = it }) { showSettings = false }
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF1A1A1A), modifier = Modifier.width(280.dp)) {
                Text("💬 المحادثات", color = Color(0xFFFFB800), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                Button(
                    onClick = {
                        val newId = "chat_" + System.currentTimeMillis()
                        val ids = (prefs.getString("chat_ids", "") ?: "") + "," + newId
                        prefs.edit().putString("chat_ids", ids).putString("chat_title_$newId", "محادثة جديدة").apply()
                        currentChat = newId
                        messages = emptyList()
                        loadChatsList()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
                ) { Text("+ محادثة جديدة", color = Color(0xFF0D0D0D)) }
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(chats) { chat ->
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    loadChat(chat.id)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(chat.title, color = Color.White, maxLines = 1) }
                            IconButton(onClick = {
                                val newIds = chats.filter { it.id != chat.id }.joinToString(",") { it.id }
                                prefs.edit().putString("chat_ids", newIds).remove("chat_${chat.id}").remove("chat_title_${chat.id}").apply()
                                if (currentChat == chat.id) { currentChat = "default"; messages = emptyList() }
                                loadChatsList()
                            }) { Icon(Icons.Filled.Delete, "Delete", tint = Color(0xFFE94560)) }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🦁", fontSize = 28.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("Lion AI", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, "Menu", tint = Color(0xFFFFB800)) }
                    },
                    actions = {
                        IconButton(onClick = { tts?.stop() }) { Icon(Icons.Filled.VolumeOff, "Stop", tint = Color(0xFFFFB800)) }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings", tint = Color(0xFFFFB800)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF0D0D0D))) {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages) { msg -> Bubble(msg, context, tts) }
                    if (loading) item { Text("... يفكر", color = Color(0xFFB0B0B0), modifier = Modifier.padding(8.dp)) }
                }

                if (chosenFileName != null) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📎 $chosenFileName", color = Color(0xFFFFB800), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { chosenFileUri = null; chosenFileName = null }) { Icon(Icons.Filled.Close, "Close", tint = Color(0xFFFF8C00)) }
                    }
                }

                Surface(color = Color(0xFF1A1A1A), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { fileLauncher.launch("*/*") }) { Icon(Icons.Filled.AttachFile, "File", tint = Color(0xFFFFB800)) }
                        IconButton(onClick = { startVoice() }) { Icon(Icons.Filled.Mic, "Voice", tint = Color(0xFFFFB800)) }
                        OutlinedTextField(
                            value = input, onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("اكتب...", color = Color(0xFFB0B0B0)) },
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFFB800),
                                unfocusedBorderColor = Color(0xFF2A2A2A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = {
                                if ((input.isNotBlank() || chosenFileUri != null) && !loading) {
                                    val userMsg = if (input.isNotBlank()) input else "📎 $chosenFileName"
                                    messages = messages + Message(userMsg, true)
                                    input = ""
                                    saveChat()

                                    val reminder = detectReminder(userMsg)
                                    if (reminder != null) {
                                        scheduleReminder(reminder.first, reminder.second)
                                        messages = messages + Message("✅ تم جدولة التذكير: ${reminder.first}", false)
                                        saveChat()
                                        return@Button
                                    }

                                    val sms = detectSms(userMsg)
                                    if (sms != null) {
                                        sendSms(sms.first, sms.second)
                                        messages = messages + Message("📱 تم إرسال الرسالة إلى ${sms.first}", false)
                                        saveChat()
                                        return@Button
                                    }

                                    loading = true
                                    scope.launch {
                                        val reply = withContext(Dispatchers.IO) {
                                            try {
                                                val apiKey = prefs.getString("api_key", "") ?: ""
                                                val provider = prefs.getString("provider", "groq") ?: "groq"
                                                val py = Python.getInstance()
                                                val module = py.getModule("assistant")
                                                module.callAttr("ask", userMsg, apiKey, provider).toString()
                                            } catch (e: Exception) { "خطأ: ${e.message}" }
                                        }

                                        // توليد روابط الأغاني إذا كان السؤال عن أغنية
                                        var ytUrl: String? = null
                                        var ytmUrl: String? = null
                                        val musicKeywords = listOf("أغنية", "اغنية", "أغني", "اغني", "شغل", "موسيقى", "song", "music")
                                        if (musicKeywords.any { userMsg.contains(it) }) {
                                            val q = userMsg.replace(Regex("(أريد|اريد|شغل|أغنية|اغنية|أغني|اغني|لي|موسيقى)"), "").trim()
                                            if (q.isNotEmpty()) {
                                                ytUrl = "https://www.youtube.com/results?search_query=" + Uri.encode(q)
                                                ytmUrl = "https://music.youtube.com/search?q=" + Uri.encode(q)
                                            }
                                        }

                                        messages = messages + Message(reply, false, ytUrl, ytmUrl)
                                        loading = false
                                        saveChat()
                                        if (autoSpeak) tts?.speak(reply.take(500), TextToSpeech.QUEUE_FLUSH, null, null)
                                    }
                                }
                            },
                            enabled = !loading,
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
                        ) { Text("➤", color = Color(0xFF0D0D0D), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(prefs: android.content.SharedPreferences, autoSpeak: Boolean, onAutoSpeakChange: (Boolean) -> Unit, onSave: () -> Unit) {
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var provider by remember { mutableStateOf(prefs.getString("provider", "groq") ?: "groq") }
    val providers = listOf("groq", "gemini", "openai", "claude")

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("⚙️ الإعدادات", color = Color(0xFFFFB800), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text("المزود:", color = Color.White)
        providers.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                RadioButton(selected = provider == p, onClick = { provider = p }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFB800)))
                Text(p.replaceFirstChar { it.uppercase() }, color = Color.White)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("مفتاح API:", color = Color.White)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("أدخل مفتاح API", color = Color.Gray) },
            maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFFFB800),
                unfocusedBorderColor = Color(0xFF2A2A2A),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = autoSpeak, onCheckedChange = onAutoSpeakChange, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFB800)))
            Spacer(Modifier.width(8.dp))
            Text("قراءة الردود تلقائياً", color = Color.White)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                prefs.edit().putString("api_key", apiKey).putString("provider", provider).apply()
                onSave()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
        ) { Text("💾 حفظ", color = Color(0xFF0D0D0D), fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun Bubble(msg: Message, context: Context, tts: TextToSpeech?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start) {
        Card(
            colors = CardDefaults.cardColors(containerColor = if (msg.isUser) Color(0xFFFFB800) else Color(0xFF2A2A2A)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(msg.text, color = if (msg.isUser) Color(0xFF0D0D0D) else Color.White, fontSize = 15.sp)

                if (msg.ytUrl != null || msg.ytmUrl != null) {
                    Spacer(Modifier.height(8.dp))
                    Row {
                        if (msg.ytUrl != null) {
                            Button(
                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(msg.ytUrl))) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) { Text("▶ يوتيوب", color = Color.White, fontSize = 12.sp) }
                        }
                        Spacer(Modifier.width(6.dp))
                        if (msg.ytmUrl != null) {
                            Button(
                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(msg.ytmUrl))) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8C00)),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) { Text("🎵 YT Music", color = Color.White, fontSize = 12.sp) }
                        }
                    }
                }

                if (!msg.isUser) {
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Lion AI", msg.text))
                        }) { Text("📋 نسخ", color = Color(0xFFFFB800), fontSize = 12.sp) }
                        TextButton(onClick = { tts?.speak(msg.text.take(500), TextToSpeech.QUEUE_FLUSH, null, null) }) {
                            Text("🔊 استماع", color = Color(0xFFFFB800), fontSize = 12.sp)
                        }
                        TextButton(onClick = { tts?.stop() }) {
                            Text("⏹ إيقاف", color = Color(0xFFFFB800), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
