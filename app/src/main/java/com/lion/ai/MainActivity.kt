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
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Message(val text: String, val isUser: Boolean, val ytUrl: String? = null, val ytmUrl: String? = null)
data class Chat(val id: String, val title: String)
data class Contact(val name: String, val number: String)

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

fun loadContactsFromDevice(ctx: Context): List<Contact> {
    val list = mutableListOf<Contact>()
    try {
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: ""
                val num = it.getString(1) ?: ""
                if (name.isNotBlank() && num.isNotBlank()) list.add(Contact(name, num))
            }
        }
    } catch (e: Exception) { }
    return list.distinctBy { it.number }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LionApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("lion_prefs", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var showSettings by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var currentChat by remember { mutableStateOf("default") }
    var chats by remember { mutableStateOf(listOf<Chat>()) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var autoSpeak by remember { mutableStateOf(false) }
    var chosenFileName by remember { mutableStateOf<String?>(null) }
    var contacts by remember { mutableStateOf(listOf<Contact>()) }
    var permGranted by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permGranted = result[Manifest.permission.READ_CONTACTS] == true
        if (permGranted) contacts = loadContactsFromDevice(context)
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) perms.add(Manifest.permission.SCHEDULE_EXACT_ALARM)
        val allGranted = perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            permGranted = true
            contacts = loadContactsFromDevice(context)
        } else {
            permLauncher.launch(perms.toTypedArray())
        }
        tts = TextToSpeech(context) { }
    }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!t.isNullOrEmpty()) input = t
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) chosenFileName = uri.lastPathSegment?.substringAfterLast("/") ?: "ملف"
    }

    fun startVoice() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            }
            voiceLauncher.launch(i)
        } catch (e: Exception) { }
    }

    // ===== التذكيرات =====
    fun detectReminder(msg: String): Pair<String, Int>? {
        val hasWord = msg.contains("ذكرني") || msg.contains("نبهني")
        if (!hasWord) return null
        val afterIdx = msg.indexOf("بعد")
        if (afterIdx < 0) return null
        val after = msg.substring(afterIdx + 3)
        val numMatch = Regex("(\\d+)").find(after) ?: return null
        val n = numMatch.groupValues[1].toIntOrNull() ?: return null
        val s = when {
            after.contains("ثانية") || after.contains("ثواني") || after.contains("ثانيه") -> n
            after.contains("دقيقة") || after.contains("دقائق") || after.contains("دقيقه") -> n * 60
            after.contains("ساعة") || after.contains("ساعات") || after.contains("ساعه") -> n * 3600
            after.contains("يوم") || after.contains("أيام") || after.contains("ايام") -> n * 86400
            else -> return null
        }
        var text = msg.replace("ذكرني", "").replace("نبهني", "").trim()
        text = text.replace(Regex("بعد\\s*\\d+\\s*(ثانية|ثواني|ثانيه|دقيقة|دقائق|دقيقه|ساعة|ساعات|ساعه|يوم|أيام|ايام)"), "").trim()
        text = text.replace(Regex("^(أن|ان|بأن|بان|ب)\\s+"), "").trim()
        return Pair(text.ifEmpty { "تذكير" }, s)
    }

    // ===== الرسائل =====
    fun detectSms(msg: String): Triple<String, String, String>? {
        val hasSend = msg.contains("أرسل") || msg.contains("ارسل") || msg.contains("ابعث")
        val hasMsg = msg.contains("رسالة") || msg.contains("رساله")
        if (!hasSend || !hasMsg) return null
        val colonIdx = msg.indexOfFirst { it == ':' || it == '：' }
        if (colonIdx < 0) return null
        val body = msg.substring(colonIdx + 1).trim()
        if (body.isEmpty()) return null
        val beforeColon = msg.substring(0, colonIdx)
        val numMatch = Regex("(\\+?\\d{6,})").find(beforeColon)
        if (numMatch != null) return Triple("number", numMatch.groupValues[1], body)
        val nameMatch = Regex("(?:لـ|ل|إلى|الى)\\s*(\\S+?)\\s*$").find(beforeColon)
        if (nameMatch != null) {
            val name = nameMatch.groupValues[1].trim().replace(":", "").trim()
            if (name.isNotEmpty()) return Triple("name", name, body)
        }
        return null
    }

    fun findNumberByName(name: String): String? {
        return contacts.firstOrNull { it.name.contains(name, ignoreCase = true) }?.number
    }

    fun sendSms(phone: String, text: String) {
        try {
            @Suppress("DEPRECATION")
            SmsManager.getDefault().sendTextMessage(phone, null, text, null, null)
        } catch (e: Exception) { }
    }

    fun scheduleReminder(text: String, seconds: Int) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val i = Intent(context, ReminderReceiver::class.java).putExtra("text", text)
            val pi = PendingIntent.getBroadcast(
                context, System.currentTimeMillis().toInt(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + seconds * 1000L, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + seconds * 1000L, pi)
            }
        } catch (e: Exception) { }
    }

    fun loadChat(id: String) {
        val saved = prefs.getString("chat_$id", "") ?: ""
        messages = if (saved.isEmpty()) emptyList() else {
            saved.split("\n---\n").filter { it.isNotEmpty() }.map { line ->
                val p = line.split("|", limit = 2)
                Message(p.getOrElse(1) { "" }, p.getOrElse(0) { "u" } == "u")
            }
        }
        currentChat = id
    }

    fun saveChat() {
        val s = messages.joinToString("\n---\n") { (if (it.isUser) "u" else "a") + "|" + it.text.replace("\n", " ") }
        prefs.edit().putString("chat_$currentChat", s).apply()
        val f = messages.firstOrNull { it.isUser }?.text
        if (f != null) prefs.edit().putString("chat_title_$currentChat", f.take(30)).apply()
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

    fun buildHistoryJson(): String {
        val sb = StringBuilder("[")
        val recent = messages.takeLast(10)
        recent.forEachIndexed { i, m ->
            if (i > 0) sb.append(",")
            val role = if (m.isUser) "user" else "assistant"
            val safeText = m.text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
            sb.append("{\"role\":\"$role\",\"content\":\"$safeText\"}")
        }
        sb.append("]")
        return sb.toString()
    }

    if (showSettings) {
        SettingsScreen(prefs, autoSpeak, { autoSpeak = it }) { showSettings = false }
        return
    }

    if (showContacts) {
        ContactsScreen(contacts, onBack = { showContacts = false }, onSelect = { c ->
            input = "أرسل رسالة لـ ${c.name}: "
            showContacts = false
        })
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
                        currentChat = newId; messages = emptyList(); loadChatsList()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
                ) { Text("+ محادثة جديدة", color = Color(0xFF0D0D0D)) }

                Button(
                    onClick = {
                        contacts = loadContactsFromDevice(context)
                        showContacts = true
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F))
                ) { Text("📇 جهات الاتصال (${contacts.size})", color = Color.White) }

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(chats) { chat ->
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { loadChat(chat.id); scope.launch { drawerState.close() } },
                                modifier = Modifier.weight(1f)
                            ) { Text(chat.title, color = Color.White, maxLines = 1) }
                            IconButton(onClick = {
                                val n = chats.filter { it.id != chat.id }.joinToString(",") { it.id }
                                prefs.edit().putString("chat_ids", n).remove("chat_${chat.id}").remove("chat_title_${chat.id}").apply()
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
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, "Menu", tint = Color(0xFFFFB800))
                        }
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
                        IconButton(onClick = { chosenFileName = null }) { Icon(Icons.Filled.Close, "Close", tint = Color(0xFFFF8C00)) }
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
                                if ((input.isNotBlank() || chosenFileName != null) && !loading) {
                                    val userMsg = if (input.isNotBlank()) input else "📎 $chosenFileName"
                                    messages = messages + Message(userMsg, true)
                                    input = ""
                                    saveChat()

                                    // التذكير
                                    val r = detectReminder(userMsg)
                                    if (r != null) {
                                        scheduleReminder(r.first, r.second)
                                        messages = messages + Message("✅ تم جدولة التذكير: ${r.first}", false)
                                        saveChat()
                                        return@Button
                                    }

                                    // SMS
                                    val sms = detectSms(userMsg)
                                    if (sms != null) {
                                        val (type, target, body) = sms
                                        val phone = if (type == "name") findNumberByName(target) else target
                                        if (phone != null) {
                                            sendSms(phone, body)
                                            messages = messages + Message("📱 تم إرسال الرسالة إلى $target ($phone)", false)
                                        } else {
                                            messages = messages + Message("❌ لم أجد جهة اتصال باسم $target", false)
                                        }
                                        saveChat()
                                        return@Button
                                    }

                                    loading = true
                                    scope.launch {
                                        val historyJson = buildHistoryJson()
                                        val reply = withContext(Dispatchers.IO) {
                                            try {
                                                val provider = prefs.getString("provider", "groq") ?: "groq"
                                                val apiKey = prefs.getString("api_key_" + provider, "") ?: ""
                                                val py = Python.getInstance()
                                                val module = py.getModule("assistant")
                                                module.callAttr("ask", userMsg, apiKey, provider, historyJson).toString()
                                            } catch (e: Exception) { "خطأ: ${e.message}" }
                                        }

                                        var ytUrl: String? = null
                                        var ytmUrl: String? = null
                                        val kw = listOf("أغنية", "اغنية", "أغني", "اغني", "شغل", "موسيقى", "song", "music")
                                        if (kw.any { userMsg.contains(it) }) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(contacts: List<Contact>, onBack: () -> Unit, onSelect: (Contact) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📇 جهات الاتصال (${contacts.size})", color = Color(0xFFFFB800)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color(0xFFFFB800)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
        }
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لا توجد جهات اتصال", color = Color.White)
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF0D0D0D))) {
                items(contacts) { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(c) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("👤", fontSize = 24.sp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(c.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(c.number, color = Color(0xFFB0B0B0), fontSize = 13.sp)
                        }
                    }
                    Divider(color = Color(0xFF2A2A2A))
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(prefs: android.content.SharedPreferences, autoSpeak: Boolean, onAutoSpeakChange: (Boolean) -> Unit, onSave: () -> Unit) {
    var provider by remember { mutableStateOf(prefs.getString("provider", "groq") ?: "groq") }
    var groqKey by remember { mutableStateOf(prefs.getString("api_key_groq", "") ?: "") }
    var geminiKey by remember { mutableStateOf(prefs.getString("api_key_gemini", "") ?: "") }
    var openaiKey by remember { mutableStateOf(prefs.getString("api_key_openai", "") ?: "") }
    var claudeKey by remember { mutableStateOf(prefs.getString("api_key_claude", "") ?: "") }
    val providers = listOf("groq", "gemini", "openai", "claude")

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("⚙️ الإعدادات", color = Color(0xFFFFB800), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("المزود الحالي:", color = Color.White)
        providers.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                RadioButton(selected = provider == p, onClick = { provider = p }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFB800)))
                Text(p.replaceFirstChar { it.uppercase() }, color = Color.White)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("🔑 مفاتيح API:", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        Text("Groq (gsk_...)", color = Color.White, fontSize = 14.sp)
        OutlinedTextField(value = groqKey, onValueChange = { groqKey = it }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFFB800), unfocusedBorderColor = Color(0xFF2A2A2A), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        Spacer(Modifier.height(8.dp))

        Text("Gemini (AIza...)", color = Color.White, fontSize = 14.sp)
        OutlinedTextField(value = geminiKey, onValueChange = { geminiKey = it }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFFB800), unfocusedBorderColor = Color(0xFF2A2A2A), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        Spacer(Modifier.height(8.dp))

        Text("OpenAI (sk-...)", color = Color.White, fontSize = 14.sp)
        OutlinedTextField(value = openaiKey, onValueChange = { openaiKey = it }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFFB800), unfocusedBorderColor = Color(0xFF2A2A2A), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        Spacer(Modifier.height(8.dp))

        Text("Claude (sk-ant-...)", color = Color.White, fontSize = 14.sp)
        OutlinedTextField(value = claudeKey, onValueChange = { claudeKey = it }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFFB800), unfocusedBorderColor = Color(0xFF2A2A2A), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = autoSpeak, onCheckedChange = onAutoSpeakChange, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFB800)))
            Spacer(Modifier.width(8.dp))
            Text("قراءة تلقائية", color = Color.White)
        }
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                prefs.edit()
                    .putString("provider", provider)
                    .putString("api_key_groq", groqKey)
                    .putString("api_key_gemini", geminiKey)
                    .putString("api_key_openai", openaiKey)
                    .putString("api_key_claude", claudeKey)
                    .apply()
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
                            Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(msg.ytUrl))) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) { Text("▶ يوتيوب", color = Color.White, fontSize = 12.sp) }
                        }
                        Spacer(Modifier.width(6.dp))
                        if (msg.ytmUrl != null) {
                            Button(onClick = { context.startActivity(Intent.ACTION_VIEW, Uri.parse(msg.ytmUrl)) },
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
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Lion AI", msg.text))
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
