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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class Message(
    val text: String,
    val isUser: Boolean,
    val ytUrl: String? = null,
    val ytmUrl: String? = null
)

data class Chat(val id: String, val title: String)
data class Contact(val name: String, val number: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                val number = it.getString(1) ?: ""
                if (name.isNotBlank() && number.isNotBlank()) list.add(Contact(name, number))
            }
        }
    } catch (e: Exception) {
    }
    return list.distinctBy { it.number }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LionApp() {
    val context = LocalContext.current
    val prefs = remember { SecurePrefs.get(context) }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()

    var showSettings by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var currentChat by remember { mutableStateOf("default") }
    var chats by remember { mutableStateOf(listOf<Chat>()) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var autoSpeak by remember { mutableStateOf(prefs.getBoolean("auto_speak", false)) }
    var attachment by remember { mutableStateOf<Attachment?>(null) }
    var contacts by remember { mutableStateOf(listOf<Contact>()) }

    fun saveChat() {
        prefs.edit().putString("chat_$currentChat", ChatStore.encode(messages)).apply()
        val title = messages.firstOrNull { it.isUser }
            ?.text
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.take(40)
        if (!title.isNullOrBlank()) {
            prefs.edit().putString("chat_title_$currentChat", title).apply()
            chats = chats.map { if (it.id == currentChat) Chat(it.id, title) else it }
        }
        val ids = (prefs.getString("chat_ids", "") ?: "")
            .split(",")
            .filter { it.isNotBlank() }
            .toMutableList()
        if (currentChat !in ids) {
            ids.add(currentChat)
            prefs.edit().putString("chat_ids", ids.joinToString(",")).apply()
        }
    }

    fun loadChat(id: String) {
        messages = ChatStore.decode(prefs.getString("chat_$id", "") ?: "")
        currentChat = id
    }

    fun loadChatsList() {
        val ids = (prefs.getString("chat_ids", "") ?: "")
            .split(",")
            .filter { it.isNotBlank() && it != "default" }
        chats = ids.map { id ->
            Chat(id, prefs.getString("chat_title_$id", "محادثة") ?: "محادثة")
        }
    }

    fun requestContacts() {
        scope.launch {
            contacts = withContext(Dispatchers.IO) { loadContactsFromDevice(context) }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val contactsAllowed = result[Manifest.permission.READ_CONTACTS] == true ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        if (contactsAllowed) requestContacts()
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) input = text
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { Attachments.read(context, uri) }
                if (result.attachment != null) {
                    attachment = result.attachment
                } else {
                    attachment = null
                    messages = messages + Message(
                        result.error ?: "⚠️ تعذر قراءة الملف.",
                        false
                    )
                    saveChat()
                }
            }
        }
    }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { engine?.language = Locale.forLanguageTag("ar") }
            }
        }
        tts = engine
        onDispose {
            runCatching { engine?.stop() }
            runCatching { engine?.shutdown() }
            if (tts === engine) tts = null
        }
    }

    LaunchedEffect(Unit) {
        loadChatsList()
        loadChat(currentChat)

        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
        } else {
            requestContacts()
        }
    }

    fun startVoice() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            }
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
        }
    }

    fun detectReminder(message: String): Pair<String, Int>? {
        val hasWord = message.contains("ذكرني") || message.contains("نبهني")
        if (!hasWord) return null
        val afterIndex = message.indexOf("بعد")
        if (afterIndex < 0) return null
        val after = message.substring(afterIndex + 3)
        val numberMatch = Regex("(\\d+)").find(after) ?: return null
        val amount = numberMatch.groupValues[1].toIntOrNull() ?: return null
        val seconds = when {
            after.contains("ثانية") || after.contains("ثواني") || after.contains("ثانيه") -> amount
            after.contains("دقيقة") || after.contains("دقائق") || after.contains("دقيقه") -> amount * 60
            after.contains("ساعة") || after.contains("ساعات") || after.contains("ساعه") -> amount * 3600
            after.contains("يوم") || after.contains("أيام") || after.contains("ايام") -> amount * 86400
            else -> return null
        }
        var text = message.replace("ذكرني", "").replace("نبهني", "").trim()
        text = text.replace(
            Regex("بعد\\s*\\d+\\s*(ثانية|ثواني|ثانيه|دقيقة|دقائق|دقيقه|ساعة|ساعات|ساعه|يوم|أيام|ايام)"),
            ""
        ).trim()
        text = text.replace(Regex("^(أن|ان|بأن|بان|ب)\\s+"), "").trim()
        return Pair(text.ifEmpty { "تذكير" }, seconds)
    }

    fun detectSms(message: String): Triple<String, String, String>? {
        val hasSend = message.contains("أرسل") || message.contains("ارسل") || message.contains("ابعث")
        val hasMessage = message.contains("رسالة") || message.contains("رساله")
        if (!hasSend || !hasMessage) return null
        val colonIndex = message.indexOfFirst { it == ':' || it == '：' }
        if (colonIndex < 0) return null
        val body = message.substring(colonIndex + 1).trim()
        if (body.isEmpty()) return null
        val beforeColon = message.substring(0, colonIndex)
        val numberMatch = Regex("(\\+?\\d{6,})").find(beforeColon)
        if (numberMatch != null) {
            return Triple("number", numberMatch.groupValues[1], body)
        }
        val nameMatch = Regex("(?:^|\\s)(?:لـ|ل|إلى|الى)\\s+")
            .findAll(beforeColon)
            .lastOrNull()
        if (nameMatch != null) {
            val name = beforeColon.substring(nameMatch.range.last + 1).trim()
            if (name.isNotEmpty()) return Triple("name", name, body)
        }
        return null
    }

    fun findNumberByName(name: String): String? {
        return contacts.firstOrNull { it.name.contains(name, ignoreCase = true) }?.number
    }

    @Suppress("DEPRECATION")
    fun sendSms(phone: String, text: String): Boolean {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            SmsManager.getDefault().sendTextMessage(phone, null, text, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openSmsComposer(phone: String, text: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null))
                    .putExtra("sms_body", text)
            )
        }
    }

    fun scheduleReminder(text: String, seconds: Int) {
        try {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ReminderReceiver::class.java).putExtra("text", text)
            val pending = PendingIntent.getBroadcast(
                context,
                (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() +
                seconds.coerceIn(1, 30 * 24 * 3600) * 1000L
            val canExact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (e: Exception) {
        }
    }

    fun sendMessage() {
        if (loading) return
        val text = input.trim()
        val file = attachment
        if (text.isEmpty() && file == null) return

        val displayText = buildString {
            if (text.isNotEmpty()) append(text)
            if (file != null) {
                if (isNotEmpty()) append('\n')
                append("📎 ").append(file.name)
            }
        }
        val prompt = if (file != null) {
            buildString {
                if (text.isNotEmpty()) append(text).append("\n\n")
                append("محتوى الملف (").append(file.name).append("):\n").append(file.text)
            }
        } else {
            text
        }

        val history = messages.map {
            ChatTurn(if (it.isUser) "user" else "assistant", it.text)
        }

        messages = messages + Message(displayText, true)
        input = ""
        attachment = null
        saveChat()

        if (file == null) {
            val reminder = detectReminder(text)
            if (reminder != null) {
                scheduleReminder(reminder.first, reminder.second)
                val notices = NotificationManagerCompat.from(context).areNotificationsEnabled()
                messages = messages + Message(
                    if (notices) {
                        "✅ تم جدولة التذكير: ${reminder.first}"
                    } else {
                        "⚠️ تم جدولة التذكير، لكن الإشعارات معطلة من إعدادات النظام."
                    },
                    false
                )
                saveChat()
                return
            }

            val sms = detectSms(text)
            if (sms != null) {
                val (type, target, smsBody) = sms
                val phone = if (type == "name") findNumberByName(target) else target
                if (phone == null) {
                    messages = messages + Message("❌ لم أجد جهة اتصال باسم $target", false)
                } else if (sendSms(phone, smsBody)) {
                    messages = messages + Message("📱 تم إرسال الرسالة إلى $target ($phone)", false)
                } else {
                    openSmsComposer(phone, smsBody)
                    messages = messages + Message(
                        "📱 فتحت تطبيق الرسائل لإرسالها إلى $target ($phone). اضغط إرسال للتأكيد.",
                        false
                    )
                }
                saveChat()
                return
            }
        }

        val keywords = listOf("أغنية", "اغنية", "أغني", "اغني", "شغل", "موسيقى", "song", "music")
        val wantsMusic = keywords.any { text.contains(it, ignoreCase = true) }
        val query = text
            .replace(Regex("(أريد|اريد|شغل|أغنية|اغنية|أغني|اغني|لي|موسيقى)"), "")
            .trim()
        val ytUrl = if (wantsMusic && query.isNotEmpty()) {
            "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        } else {
            null
        }
        val ytmUrl = if (wantsMusic && query.isNotEmpty()) {
            "https://music.youtube.com/search?q=" + Uri.encode(query)
        } else {
            null
        }

        loading = true
        scope.launch {
            val provider = prefs.getString("provider", "gemini") ?: "gemini"
            val apiKey = prefs.getString("api_key_$provider", "") ?: ""
            val model = prefs.getString("model_$provider", "") ?: ""
            val accountId = prefs.getString("cf_account_id", "") ?: ""

            val reply = withContext(Dispatchers.IO) {
                LlmClient.ask(prompt, apiKey, provider, history, model, accountId)
            }
            messages = messages + Message(reply, false, ytUrl, ytmUrl)
            loading = false
            saveChat()
            if (autoSpeak) {
                tts?.speak(reply.take(500), TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    if (showSettings) {
        SettingsScreen(
            prefs = prefs,
            autoSpeak = autoSpeak,
            onAutoSpeakChange = {
                autoSpeak = it
                prefs.edit().putBoolean("auto_speak", it).apply()
            },
            onSave = { showSettings = false }
        )
        return
    }

    if (showContacts) {
        ContactsScreen(
            contacts = contacts,
            onBack = { showContacts = false },
            onSelect = { contact ->
                input = "أرسل رسالة لـ ${contact.name}: "
                showContacts = false
            }
        )
        return
    }

    LaunchedEffect(messages.size, loading) {
        val count = messages.size + if (loading) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF1A1A1A),
                modifier = Modifier.width(280.dp)
            ) {
                Text(
                    "💬 المحادثات",
                    color = Color(0xFFFFB800),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
                Button(
                    onClick = {
                        currentChat = "chat_" + System.currentTimeMillis()
                        val ids = (prefs.getString("chat_ids", "") ?: "")
                            .split(",")
                            .filter { it.isNotBlank() }
                            .toMutableList()
                        if (currentChat !in ids) {
                            ids.add(currentChat)
                            prefs.edit().putString("chat_ids", ids.joinToString(",")).apply()
                        }
                        messages = emptyList()
                        input = ""
                        attachment = null
                        loadChatsList()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
                ) {
                    Text("+ محادثة جديدة", color = Color(0xFF0D0D0D))
                }

                Button(
                    onClick = {
                        requestContacts()
                        showContacts = true
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F))
                ) {
                    Text("📇 جهات الاتصال (${contacts.size})", color = Color.White)
                }

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(chats) { chat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    loadChat(chat.id)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(chat.title, color = Color.White, maxLines = 1)
                            }
                            IconButton(onClick = {
                                val remaining = chats
                                    .filter { it.id != chat.id }
                                    .joinToString(",") { it.id }
                                prefs.edit()
                                    .putString("chat_ids", remaining)
                                    .remove("chat_${chat.id}")
                                    .remove("chat_title_${chat.id}")
                                    .apply()
                                if (currentChat == chat.id) {
                                    loadChat("default")
                                }
                                loadChatsList()
                            }) {
                                Icon(Icons.Filled.Delete, "حذف", tint = Color(0xFFE94560))
                            }
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
                            Icon(Icons.Filled.Menu, "القائمة", tint = Color(0xFFFFB800))
                        }
                    },
                    actions = {
                        IconButton(onClick = { tts?.stop() }) {
                            Icon(Icons.Filled.VolumeOff, "إيقاف القراءة", tint = Color(0xFFFFB800))
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, "الإعدادات", tint = Color(0xFFFFB800))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(Color(0xFF0D0D0D))
                    .imePadding()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { message ->
                        Bubble(message, context, tts)
                    }
                    if (loading) {
                        item {
                            Text(
                                "... يفكر",
                                color = Color(0xFFB0B0B0),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }

                val file = attachment
                if (file != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "📎 ${file.name}",
                            color = Color(0xFFFFB800),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { attachment = null }) {
                            Icon(Icons.Filled.Close, "إزالة المرفق", tint = Color(0xFFFF8C00))
                        }
                    }
                }

                Surface(color = Color(0xFF1A1A1A), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { fileLauncher.launch("*/*") }) {
                            Icon(Icons.Filled.AttachFile, "ملف", tint = Color(0xFFFFB800))
                        }
                        IconButton(onClick = { startVoice() }) {
                            Icon(Icons.Filled.Mic, "صوت", tint = Color(0xFFFFB800))
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
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
                            onClick = { sendMessage() },
                            enabled = !loading && (input.isNotBlank() || attachment != null),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
                        ) {
                            Text(
                                "➤",
                                color = Color(0xFF0D0D0D),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    contacts: List<Contact>,
    onBack: () -> Unit,
    onSelect: (Contact) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "📇 جهات الاتصال (${contacts.size})",
                        color = Color(0xFFFFB800)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "رجوع", tint = Color(0xFFFFB800))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
        }
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("لا توجد جهات اتصال. امنح صلاحية جهات الاتصال.", color = Color.White)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(Color(0xFF0D0D0D))
            ) {
                items(contacts) { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(contact) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("👤", fontSize = 24.sp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                contact.name,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(contact.number, color = Color(0xFFB0B0B0), fontSize = 13.sp)
                        }
                    }
                    Divider(color = Color(0xFF2A2A2A))
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    prefs: android.content.SharedPreferences,
    autoSpeak: Boolean,
    onAutoSpeakChange: (Boolean) -> Unit,
    onSave: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var provider by remember {
        mutableStateOf(prefs.getString("provider", "gemini") ?: "gemini")
    }
    val keys = remember {
        mutableStateMapOf<String, String>().apply {
            PROVIDERS.forEach { info ->
                put(info.id, prefs.getString("api_key_${info.id}", "") ?: "")
            }
        }
    }
    val models = remember {
        mutableStateMapOf<String, String>().apply {
            PROVIDERS.forEach { info ->
                put(info.id, prefs.getString("model_${info.id}", "") ?: "")
            }
        }
    }
    var accountId by remember {
        mutableStateOf(prefs.getString("cf_account_id", "") ?: "")
    }

    val current = providerInfo(provider)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "⚙️ الإعدادات",
            color = Color(0xFFFFB800),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "المفاتيح تُحفظ مشفّرة على الجهاز ولا تُرسل إلا إلى مزود الخدمة.",
            color = Color(0xFFB0B0B0),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))

        Text("المزود:", color = Color.White, fontWeight = FontWeight.Bold)
        PROVIDERS.forEach { info ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { provider = info.id }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = provider == info.id,
                    onClick = { provider = info.id },
                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFB800))
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        info.label + (if (info.free) "  🟢" else "  🔵"),
                        color = Color.White
                    )
                    Text(info.tagline, color = Color(0xFFB0B0B0), fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("🔑 مفتاح ${current.label}", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = keys[provider].orEmpty(),
            onValueChange = { keys[provider] = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("الصق المفتاح هنا", color = Color(0xFF707070)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFFFB800),
                unfocusedBorderColor = Color(0xFF2A2A2A),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        if (current.needsAccountId) {
            Spacer(Modifier.height(12.dp))
            Text("Cloudflare Account ID", color = Color.White, fontSize = 14.sp)
            OutlinedTextField(
                value = accountId,
                onValueChange = { accountId = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("من لوحة Cloudflare", color = Color(0xFF707070)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFFB800),
                    unfocusedBorderColor = Color(0xFF2A2A2A),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }

        Spacer(Modifier.height(12.dp))
        Text("الموديل (اختياري)", color = Color.White, fontSize = 14.sp)
        OutlinedTextField(
            value = models[provider].orEmpty(),
            onValueChange = { models[provider] = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(current.defaultModel, color = Color(0xFF707070)) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFFFB800),
                unfocusedBorderColor = Color(0xFF2A2A2A),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        TextButton(onClick = {
            runCatching { uriHandler.openUri(current.docsUrl) }
        }) {
            Text("📖 التوثيق الرسمي وحدود الاستخدام", color = Color(0xFFFFB800), fontSize = 12.sp)
        }

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

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                val editor = prefs.edit()
                editor.putString("provider", provider)
                keys.forEach { (id, value) -> editor.putString("api_key_$id", value) }
                models.forEach { (id, value) -> editor.putString("model_$id", value) }
                editor.putString("cf_account_id", accountId)
                editor.putBoolean("auto_speak", autoSpeak)
                editor.apply()
                onSave()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
        ) {
            Text("💾 حفظ", color = Color(0xFF0D0D0D), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun Bubble(message: Message, context: Context, tts: TextToSpeech?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.isUser) Color(0xFFFFB800) else Color(0xFF2A2A2A)
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    message.text,
                    color = if (message.isUser) Color(0xFF0D0D0D) else Color.White,
                    fontSize = 15.sp
                )
                if (message.ytUrl != null || message.ytmUrl != null) {
                    Spacer(Modifier.height(8.dp))
                    Row {
                        if (message.ytUrl != null) {
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(message.ytUrl))
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF0000)
                                ),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("▶ يوتيوب", color = Color.White, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        if (message.ytmUrl != null) {
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(message.ytmUrl))
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF8C00)
                                ),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("🎵 YT Music", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (!message.isUser) {
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("Lion AI", message.text)
                            )
                        }) {
                            Text("📋 نسخ", color = Color(0xFFFFB800), fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            tts?.speak(
                                message.text.take(500),
                                TextToSpeech.QUEUE_FLUSH,
                                null,
                                null
                            )
                        }) {
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
