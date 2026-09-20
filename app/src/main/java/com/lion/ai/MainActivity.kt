package com.lion.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
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
import java.util.Locale

data class Message(val text: String, val isUser: Boolean)
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

    var showSettings by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var currentChat by remember { mutableStateOf("default") }
    var chats by remember { mutableStateOf(listOf<Chat>()) }

    // TTS
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    LaunchedEffect(Unit) {
        tts = TextToSpeech(context) { }
    }

    // STT
    val sttLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrEmpty()) {
                input = spoken
            }
        }
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
    }

    fun loadChatsList() {
        val ids = prefs.getString("chat_ids", "") ?: ""
        chats = if (ids.isEmpty()) emptyList() else ids.split(",").filter { it.isNotEmpty() }.map {
            Chat(it, prefs.getString("chat_title_$it", "محادثة") ?: "محادثة")
        }
    }

    LaunchedEffect(Unit) {
        loadChatsList()
        loadChat(currentChat)
    }

    // كشف التذكير
    fun checkReminder(text: String): Boolean {
        val regex = Regex("ذكرني\\s+بعد\\s+(\\d+)\\s*(دقيقة|دقائق|ثانية|ثواني|ساعة|ساعات)")
        val match = regex.find(text) ?: return false
        val amount = match.groupValues[1].toLongOrNull() ?: return false
        val unit = match.groupValues[2]
        val ms = when {
            unit.contains("ثانية") || unit.contains("ثواني") -> amount * 1000
            unit.contains("دقيقة") || unit.contains("دقائق") -> amount * 60 * 1000
            unit.contains("ساعة") || unit.contains("ساعات") -> amount * 60 * 60 * 1000
            else -> return false
        }
        val reminderText = text.replace(match.value, "").trim().ifEmpty { "تذكير" }
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("text", reminderText)
        }
        val pending = PendingIntent.getBroadcast(
            context, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerTime = System.currentTimeMillis() + ms
        try {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending)
        } catch (e: SecurityException) {
            alarm.set(AlarmManager.RTC_WAKEUP, triggerTime, pending)
        }
        return true
    }

    if (showSettings) {
        SettingsScreen(prefs) { showSettings = false }
        return
    }

    ModalNavigationDrawer(
        drawerState = rememberDrawerState(if (drawerOpen) DrawerValue.Open else DrawerValue.Closed),
        gesturesEnabled = drawerOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF1A1A1A), modifier = Modifier.width(280.dp)) {
                Text("💬 المحادثات", color = Color(0xFFFFB800), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp))
                Button(
                    onClick = {
                        val newId = "chat_" + System.currentTimeMillis()
                        val ids = (prefs.getString("chat_ids", "") ?: "") + "," + newId
                        prefs.edit().putString("chat_ids", ids).putString("chat_title_$newId", "محادثة جديدة").apply()
                        currentChat = newId; messages = emptyList(); loadChatsList(); drawerOpen = false
                    },
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
                ) { Text("+ محادثة جديدة", color = Color(0xFF0D0D0D)) }
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(chats) { chat ->
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { loadChat(chat.id); drawerOpen = false }, modifier = Modifier.weight(1f)) {
                                Text(chat.title, color = Color.White, maxLines = 1)
                            }
                            IconButton(onClick = {
                                val newIds = chats.filter { it.id != chat.id }.joinToString(",") { it.id }
                                prefs.edit().putString("chat_ids", newIds).remove("chat_${chat.id}").apply()
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
                        IconButton(onClick = { drawerOpen = true }) {
                            Icon(Icons.Filled.Menu, "Menu", tint = Color(0xFFFFB800))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, "Settings", tint = Color(0xFFFFB800))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF0D0D0D))) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { msg -> Bubble(msg, context, tts) }
                    if (loading) item { Text("... يفكر", color = Color(0xFFB0B0B0), modifier = Modifier.padding(8.dp)) }
                }

                Surface(color = Color(0xFF1A1A1A), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
                            }
                            sttLauncher.launch(intent)
                        }) { Icon(Icons.Filled.Mic, "Mic", tint = Color(0xFFFFB800)) }

                        OutlinedTextField(
                            value = input, onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("اكتب رسالتك...", color = Color(0xFFB0B0B0)) },
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFFB800),
                                unfocusedBorderColor = Color(0xFF2A2A2A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (input.isNotBlank() && !loading) {
                                    val userMsg = input
                                    messages = messages + Message(userMsg, true)
                                    input = ""
                                    loading = true
                                    saveChat()

                                    // فحص التذكير
                                    if (checkReminder(userMsg)) {
                                        messages = messages + Message("✅ تم جدولة التذكير!", false)
                                        loading = false
                                        saveChat()
                                        return@Button
                                    }

                                    // فحص إرسال SMS
                                    val smsRegex = Regex("(?:أرسل|ارسل)\\s+(?:رسالة|رساله)\\s+(?:إلى|الى)\\s+(\\+?\\d{6,})[:\\s]+(.+)")
                                    val smsMatch = smsRegex.find(userMsg)
                                    if (smsMatch != null) {
                                        val number = smsMatch.groupValues[1]
                                        val body = smsMatch.groupValues[2]
                                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                                            putExtra("sms_body", body)
                                        }
                                        context.startActivity(intent)
                                        messages = messages + Message("📱 فتح تطبيق الرسائل لإرسال: $body\nإلى: $number\n(اضغط إرسال في التطبيق)", false)
                                        loading = false
                                        saveChat()
                                        return@Button
                                    }

                                    // فحص الترجمة
                                    val transRegex = Regex("(?:ترجم|translate)\\s+(?:إلى|الى)\\s+(\\S+)[:\\s]+(.+)")
                                    val transMatch = transRegex.find(userMsg)
                                    val realQuery = if (transMatch != null) {
                                        "Translate the following to ${transMatch.groupValues[1]}: ${transMatch.groupValues[2]}"
                                    } else userMsg

                                    scope.launch {
                                        val reply = withContext(Dispatchers.IO) {
                                            try {
                                                val apiKey = prefs.getString("api_key", "") ?: ""
                                                val provider = prefs.getString("provider", "groq") ?: "groq"
                                                val py = Python.getInstance()
                                                val module = py.getModule("assistant")
                                                module.callAttr("ask", realQuery, apiKey, provider).toString()
                                            } catch (e: Exception) { "خطأ: ${e.message}" }
                                        }
                                        messages = messages + Message(reply, false)
                                        loading = false
                                        saveChat()
                                        // قراءة الرد صوتياً
                                        tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, null)
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
fun SettingsScreen(prefs: android.content.SharedPreferences, onSave: () -> Unit) {
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var provider by remember { mutableStateOf(prefs.getString("provider", "groq") ?: "groq") }
    val providers = listOf("groq", "gemini", "openai", "claude")

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("⚙️ الإعدادات", color = Color(0xFFFFB800), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text("المزود:", color = Color.White)
        providers.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                RadioButton(selected = provider == p, onClick = { provider = p },
                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFB800)))
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
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(msg.text, color = if (msg.isUser) Color(0xFF0D0D0D) else Color.White, fontSize = 15.sp)
                if (!msg.isUser) {
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Lion AI", msg.text))
                        }) { Text("📋 نسخ", color = Color(0xFFFFB800), fontSize = 12.sp) }
                        TextButton(onClick = {
                            tts?.speak(msg.text, TextToSpeech.QUEUE_FLUSH, null, null)
                        }) { Text("🔊 استماع", color = Color(0xFFFFB800), fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}
