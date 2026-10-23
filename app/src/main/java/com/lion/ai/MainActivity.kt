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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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

data class Message(
    val text: String,
    val isUser: Boolean,
    val ytUrl: String? = null,
    val ytmUrl: String? = null,
    val pending: PendingAction? = null
)

data class Contact(val name: String, val number: String, val normalized: String? = null)

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
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: ""
                val number = it.getString(1) ?: ""
                val normalized = it.getString(2)
                if (name.isNotBlank() && number.isNotBlank()) {
                    list.add(Contact(name, number, normalized?.takeIf { n -> n.isNotBlank() }))
                }
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
    var showScheduled by remember { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var currentChat by remember { mutableStateOf(prefs.getString("last_chat", "default") ?: "default") }
    var chatMetas by remember { mutableStateOf(listOf<ChatMeta>()) }
    var scheduledItems by remember { mutableStateOf(listOf<ScheduledItem>()) }
    var renameTarget by remember { mutableStateOf<ChatMeta?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatMeta?>(null) }
    var autoSendTrigger by remember { mutableStateOf(0) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var autoSpeak by remember { mutableStateOf(prefs.getBoolean("auto_speak", false)) }
    var attachment by remember { mutableStateOf<Attachment?>(null) }
    var imageAttachment by remember { mutableStateOf<ImageAttachment?>(null) }
    var contacts by remember { mutableStateOf(listOf<Contact>()) }
    var selectedProvider by remember {
        mutableStateOf(prefs.getString("provider", "gemini") ?: "gemini")
    }
    var showModelSheet by remember { mutableStateOf(false) }
    val modelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun refreshChats() {
        chatMetas = ChatRepository.all(prefs)
    }

    fun saveCurrent() {
        ChatRepository.save(prefs, currentChat, messages)
        refreshChats()
    }

    fun openChat(id: String) {
        messages = ChatRepository.load(prefs, id)
        currentChat = id
        input = ""
        attachment = null
        imageAttachment = null
        prefs.edit().putString("last_chat", id).apply()
    }

    fun startNewChat() {
        val id = ChatRepository.newId()
        messages = emptyList()
        currentChat = id
        input = ""
        attachment = null
        imageAttachment = null
        prefs.edit().putString("last_chat", id).apply()
    }

    fun appendToChat(chatId: String, message: Message) {
        if (chatId == currentChat) {
            messages = messages + message
            saveCurrent()
        } else {
            ChatRepository.save(prefs, chatId, ChatRepository.load(prefs, chatId) + message)
            refreshChats()
        }
    }

    fun providerCredentials(): Triple<String, String, String> {
        val provider = selectedProvider
        return Triple(
            prefs.getString("api_key_$provider", "") ?: "",
            prefs.getString("model_$provider", "") ?: "",
            prefs.getString("cf_account_id", "") ?: ""
        )
    }

    fun generateTitle(chatId: String, force: Boolean) {
        if (!force && !prefs.getBoolean("smart_titles", true)) return
        val meta = ChatRepository.get(prefs, chatId) ?: return
        if (meta.titleSource == "user") return
        if (!force && meta.titleSource == "ai") return
        val list = ChatRepository.load(prefs, chatId)
        val firstUser = list.firstOrNull { it.isUser }?.text ?: return
        val firstReply = list.firstOrNull { !it.isUser && !LlmClient.isErrorReply(it.text) }?.text ?: return
        val provider = selectedProvider
        val (apiKey, model, accountId) = providerCredentials()
        scope.launch {
            val title = withContext(Dispatchers.IO) {
                LlmClient.generateTitle(firstUser, firstReply, apiKey, provider, model, accountId)
            }
            if (!title.isNullOrBlank()) {
                ChatRepository.setAiTitle(prefs, chatId, title)
                refreshChats()
            }
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
        if (!text.isNullOrBlank()) {
            input = text
            // الأمر الصوتي (رسالة/اتصال/تذكير) يُنفّذ مباشرة بعد التعرف عليه
            if (ActionDetector.looksLikeCommand(text)) autoSendTrigger += 1
        }
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
                    saveCurrent()
                }
            }
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    Attachments.readImage(context, uri)
                }
                if (result.image != null) {
                    imageAttachment = result.image
                    attachment = null
                } else {
                    messages = messages + Message(
                        result.error ?: "⚠️ تعذر قراءة الصورة.",
                        false
                    )
                    saveCurrent()
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
        refreshChats()
        scheduledItems = Scheduler.list(prefs)
        openChat(currentChat)

        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
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

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun resolveRecipients(query: String): List<Recipient> {
        val q = TextNorm.digits(query).trim()
        if (Regex("^\\+?[0-9][0-9\\s\\-]{4,}$").matches(q)) {
            val n = q.replace(Regex("[\\s\\-]"), "")
            return listOf(Recipient(n, n, if (n.startsWith("+")) n.drop(1) else null))
        }
        val tries = listOfNotNull(
            q,
            if (q.length > 3 && (q.startsWith("ب") || q.startsWith("ل"))) q.substring(1) else null
        )
        for (t in tries) {
            val found = ContactMatcher.find(contacts, t)
            if (found.isNotEmpty()) return found.map { it.toRecipient() }
        }
        return emptyList()
    }

    fun openSmsComposer(phone: String, text: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null))
                    .putExtra("sms_body", text)
            )
        }
    }

    fun openWhatsApp(waNumber: String, text: String): Boolean = runCatching {
        val uri = Uri.parse(
            "https://wa.me/" + waNumber.filter { it.isDigit() } + "?text=" + Uri.encode(text)
        )
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    }.getOrDefault(false)

    fun startCall(number: String) {
        runCatching {
            val action = if (hasPermission(Manifest.permission.CALL_PHONE)) {
                Intent.ACTION_CALL
            } else {
                Intent.ACTION_DIAL
            }
            context.startActivity(Intent(action, Uri.fromParts("tel", number, null)))
        }
    }

    /** ينفّذ الأمر (فوراً أو بجدولته) ويعيد نص النتيجة للمستخدم. */
    fun runAction(
        kind: ActionKind,
        r: Recipient,
        text: String,
        triggerAt: Long?,
        channel: String
    ): String {
        if (triggerAt != null) {
            if (kind == ActionKind.SMS && !hasPermission(Manifest.permission.SEND_SMS)) {
                return "⚠️ صلاحية إرسال الرسائل غير ممنوحة. فعّلها من إعدادات التطبيق في النظام ثم أعد الطلب."
            }
            Scheduler.add(
                context, prefs,
                ScheduledItem(Scheduler.newId(), kind, triggerAt, r.name, r.number, text)
            )
            scheduledItems = Scheduler.list(prefs)
            return if (kind == ActionKind.SMS) {
                "✅ سأرسل الرسالة إلى ${r.name} ${TimeFmt.whenText(triggerAt)}."
            } else {
                "✅ سيصلك إشعار ${TimeFmt.whenText(triggerAt)}؛ اضغط عليه للاتصال بـ ${r.name}."
            }
        }
        return when (kind) {
            ActionKind.SMS -> {
                if (channel == "whatsapp" && r.waNumber != null) {
                    if (openWhatsApp(r.waNumber, text)) {
                        "💬 فتحت واتساب للمحادثة مع ${r.name}. اضغط إرسال هناك."
                    } else {
                        "⚠️ تعذر فتح واتساب."
                    }
                } else if (SmsSender.send(context, r.number, text)) {
                    "📱 تم إرسال الرسالة إلى ${r.name} (${r.number})"
                } else {
                    openSmsComposer(r.number, text)
                    "📱 فتحت تطبيق الرسائل لإرسالها إلى ${r.name} (${r.number}). اضغط إرسال للتأكيد."
                }
            }

            ActionKind.CALL -> {
                startCall(r.number)
                "📞 جارٍ الاتصال بـ ${r.name} (${r.number})"
            }

            ActionKind.REMINDER -> ""
        }
    }

    fun handleAction(chatId: String, a: ParsedAction) {
        fun reply(text: String, pending: PendingAction? = null) {
            appendToChat(chatId, Message(text, false, pending = pending))
        }
        when (a.kind) {
            ActionKind.REMINDER -> {
                val at = a.triggerAt
                if (at == null) {
                    reply("⏰ متى تريد التذكير؟ مثال: ذكرني بعد نصف ساعة أن أتصل بأحمد، أو: ذكرني الساعة 7 صباحاً بالاجتماع.")
                    return
                }
                val text = a.message.ifBlank { "تذكير" }
                Scheduler.add(
                    context, prefs,
                    ScheduledItem(Scheduler.newId(), ActionKind.REMINDER, at, "", "", text)
                )
                scheduledItems = Scheduler.list(prefs)
                val notices = NotificationManagerCompat.from(context).areNotificationsEnabled()
                reply(
                    "✅ سأذكّرك ${TimeFmt.whenText(at)}:\n$text" +
                        if (notices) "" else "\n⚠️ الإشعارات معطلة من إعدادات النظام."
                )
            }

            ActionKind.SMS, ActionKind.CALL -> {
                if (a.recipient.isBlank()) {
                    reply(
                        if (a.kind == ActionKind.SMS) {
                            "📱 لمن تريد إرسال الرسالة؟ اكتب الاسم أو الرقم."
                        } else {
                            "📞 بمن تريد الاتصال؟ اكتب الاسم أو الرقم."
                        }
                    )
                    return
                }
                if (a.kind == ActionKind.SMS && a.message.isBlank()) {
                    reply("📱 ما نص الرسالة؟")
                    return
                }
                val candidates = resolveRecipients(a.recipient)
                if (candidates.isEmpty()) {
                    reply("❌ لم أجد جهة اتصال باسم «${a.recipient}». جرّب كتابة الرقم مباشرة.")
                    return
                }
                val confirm = prefs.getBoolean("confirm_actions", true)
                if (!confirm && candidates.size == 1) {
                    reply(runAction(a.kind, candidates[0], a.message, a.triggerAt, "sms"))
                } else {
                    val whenLine = a.triggerAt?.let { "\n⏱ ${TimeFmt.whenText(it)}" } ?: ""
                    val head = if (a.kind == ActionKind.SMS) {
                        "📱 رسالة جاهزة:\n«${a.message}»$whenLine\n\nاختر المستلم للتأكيد:"
                    } else {
                        "📞 اتصال$whenLine\n\nاختر الرقم للتأكيد:"
                    }
                    reply(head, PendingAction(a.kind, a.message, a.triggerAt, candidates.take(5)))
                }
            }
        }
    }

    fun onPendingChoice(index: Int, pending: PendingAction, recipient: Recipient?, channel: String) {
        val result = if (recipient == null) {
            "🚫 تم الإلغاء."
        } else {
            runAction(pending.kind, recipient, pending.text, pending.triggerAt, channel)
        }
        messages = messages.mapIndexed { i, m ->
            if (i == index) {
                m.copy(text = m.text.substringBefore("\n\nاختر") + "\n\n" + result, pending = null)
            } else {
                m
            }
        }
        saveCurrent()
    }

    fun runChat(
        chatId: String,
        prompt: String,
        history: List<ChatTurn>,
        image: ImageAttachment?,
        ytUrl: String?,
        ytmUrl: String?
    ) {
        val provider = selectedProvider
        val (apiKey, model, accountId) = providerCredentials()
        loading = true
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                LlmClient.ask(prompt, apiKey, provider, history, model, accountId, image)
            }
            appendToChat(chatId, Message(reply, false, ytUrl, ytmUrl))
            loading = false
            if (autoSpeak && chatId == currentChat) {
                tts?.speak(reply.take(500), TextToSpeech.QUEUE_FLUSH, null, null)
            }
            if (!LlmClient.isErrorReply(reply)) {
                val replies = ChatRepository.load(prefs, chatId)
                    .count { !it.isUser && !LlmClient.isErrorReply(it.text) }
                if (replies == 1) generateTitle(chatId, false)
            }
        }
    }

    fun sendMessage() {
        if (loading) return
        val text = input.trim()
        val file = attachment
        val image = imageAttachment
        if (text.isEmpty() && file == null && image == null) return

        val provider = selectedProvider
        val visionProviders = setOf("gemini", "deepseek", "openai", "claude")
        if (image != null && provider !in visionProviders) {
            messages = messages + Message(
                "⚠️ المزود الحالي لا يقرأ الصور. اختر Gemini أو DeepSeek أو OpenAI أو Claude من زر الموديل أسفل مربع الكتابة.",
                false
            )
            saveCurrent()
            return
        }

        val displayText = buildString {
            if (text.isNotEmpty()) append(text)
            if (file != null) {
                if (isNotEmpty()) append('\n')
                append("📎 ").append(file.name)
            }
            if (image != null) {
                if (isNotEmpty()) append('\n')
                append("🖼️ ").append(image.name)
            }
        }
        var prompt = if (file != null) {
            buildString {
                if (text.isNotEmpty()) append(text).append("\n\n")
                append("محتوى الملف (").append(file.name).append("):\n").append(file.text)
            }
        } else {
            text
        }
        if (prompt.isBlank() && image != null) {
            prompt = "اشرح محتوى هذه الصورة بالتفصيل."
        }

        val history = messages.map {
            ChatTurn(if (it.isUser) "user" else "assistant", it.text)
        }

        val chatId = currentChat
        messages = messages + Message(displayText, true)
        input = ""
        attachment = null
        imageAttachment = null
        saveCurrent()

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

        if (file == null && image == null && ActionDetector.looksLikeCommand(text)) {
            loading = true
            scope.launch {
                val (apiKey, model, accountId) = providerCredentials()
                if (contacts.isEmpty() && hasPermission(Manifest.permission.READ_CONTACTS)) {
                    contacts = withContext(Dispatchers.IO) { loadContactsFromDevice(context) }
                }
                val parsed = withContext(Dispatchers.IO) {
                    ActionResolver.resolve(text, apiKey, provider, model, accountId)
                }
                loading = false
                if (parsed != null) {
                    handleAction(chatId, parsed)
                } else {
                    runChat(chatId, prompt, history, image, ytUrl, ytmUrl)
                }
            }
            return
        }

        runChat(chatId, prompt, history, image, ytUrl, ytmUrl)
    }

    LaunchedEffect(autoSendTrigger) {
        if (autoSendTrigger > 0) sendMessage()
    }

    if (showSettings) {
        SettingsScreen(
            prefs = prefs,
            autoSpeak = autoSpeak,
            onAutoSpeakChange = {
                autoSpeak = it
                prefs.edit().putBoolean("auto_speak", it).apply()
            },
            onBack = {
                showSettings = false
                selectedProvider = prefs.getString("provider", "gemini") ?: "gemini"
            },
            onSave = {
                showSettings = false
                selectedProvider = prefs.getString("provider", "gemini") ?: "gemini"
            }
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

    if (showScheduled) {
        ScheduledScreen(
            items = scheduledItems,
            onBack = { showScheduled = false },
            onDelete = { id ->
                Scheduler.cancel(context, prefs, id)
                scheduledItems = Scheduler.list(prefs)
            }
        )
        return
    }

    if (showModelSheet) {
        ModalBottomSheet(
            onDismissRequest = { showModelSheet = false },
            sheetState = modelSheetState,
            containerColor = Color(0xFF1A1A1A)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    "اختر الموديل",
                    color = Color(0xFFFFB800),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                PROVIDERS.forEach { info ->
                    val configured = (prefs.getString("model_${info.id}", "") ?: "")
                        .ifBlank { info.defaultModel }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                prefs.edit().putString("provider", info.id).apply()
                                selectedProvider = info.id
                                showModelSheet = false
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedProvider == info.id,
                            onClick = {
                                prefs.edit().putString("provider", info.id).apply()
                                selectedProvider = info.id
                                showModelSheet = false
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFB800))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                info.label + (if (info.free) "  🟢" else "  🔵"),
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Text(
                                configured,
                                color = Color(0xFF9A9A9A),
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF2A2A2A))
                TextButton(
                    onClick = {
                        showModelSheet = false
                        showSettings = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        "⚙️ الإعدادات والمفاتيح وحدود الاستخدام",
                        color = Color(0xFFFFB800),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }

    LaunchedEffect(messages.size, loading) {
        val count = messages.size + if (loading) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    val renaming = renameTarget
    if (renaming != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = Color(0xFF1A1A1A),
            title = { Text("✏️ تغيير اسم المحادثة", color = Color(0xFFFFB800)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFFB800),
                        unfocusedBorderColor = Color(0xFF2A2A2A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFFFFB800)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ChatRepository.rename(prefs, renaming.id, renameText)
                    refreshChats()
                    renameTarget = null
                }) { Text("حفظ", color = Color(0xFFFFB800)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("إلغاء", color = Color.White) }
            }
        )
    }

    val deleting = deleteTarget
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = Color(0xFF1A1A1A),
            title = { Text("🗑️ حذف المحادثة", color = Color(0xFFE94560)) },
            text = { Text("سيتم حذف «${deleting.title}» نهائياً.", color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    ChatRepository.delete(prefs, deleting.id)
                    if (currentChat == deleting.id) startNewChat()
                    refreshChats()
                    deleteTarget = null
                }) { Text("حذف", color = Color(0xFFE94560)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("إلغاء", color = Color.White) }
            }
        )
    }

    val activeChats = chatMetas.filter { !it.archived && (it.count > 0 || it.id == currentChat) }
    val archivedChats = chatMetas.filter { it.archived }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF1A1A1A),
                modifier = Modifier.width(300.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
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
                            startNewChat()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F))
                    ) {
                        Text("📇 جهات الاتصال (${contacts.size})", color = Color.White)
                    }
                    Button(
                        onClick = {
                            scheduledItems = Scheduler.list(prefs)
                            showScheduled = true
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E4A2E))
                    ) {
                        Text("⏰ المجدولات (${scheduledItems.size})", color = Color.White)
                    }
                    Spacer(Modifier.height(6.dp))

                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(activeChats, key = { it.id }) { meta ->
                            ChatRow(
                                meta = meta,
                                selected = meta.id == currentChat,
                                onOpen = {
                                    openChat(meta.id)
                                    scope.launch { drawerState.close() }
                                },
                                onRename = {
                                    renameText = meta.title
                                    renameTarget = meta
                                },
                                onAutoTitle = { generateTitle(meta.id, true) },
                                onArchiveToggle = {
                                    ChatRepository.setArchived(prefs, meta.id, true)
                                    if (currentChat == meta.id) startNewChat()
                                    refreshChats()
                                },
                                onDelete = { deleteTarget = meta }
                            )
                        }
                        if (archivedChats.isNotEmpty()) {
                            item(key = "archive_header") {
                                TextButton(
                                    onClick = { showArchive = !showArchive },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "📦 الأرشيف (${archivedChats.size}) " + if (showArchive) "▲" else "▼",
                                        color = Color(0xFFB0B0B0)
                                    )
                                }
                            }
                            if (showArchive) {
                                items(archivedChats, key = { "arch_" + it.id }) { meta ->
                                    ChatRow(
                                        meta = meta,
                                        selected = false,
                                        onOpen = {
                                            openChat(meta.id)
                                            scope.launch { drawerState.close() }
                                        },
                                        onRename = {
                                            renameText = meta.title
                                            renameTarget = meta
                                        },
                                        onAutoTitle = { generateTitle(meta.id, true) },
                                        onArchiveToggle = {
                                            ChatRepository.setArchived(prefs, meta.id, false)
                                            refreshChats()
                                        },
                                        onDelete = { deleteTarget = meta }
                                    )
                                }
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
                            Column {
                                Text("Lion AI", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold)
                                Text(
                                    chatMetas.firstOrNull { it.id == currentChat }?.title
                                        ?: ChatRepository.DEFAULT_TITLE,
                                    color = Color(0xFF9A9A9A),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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
                    itemsIndexed(messages) { index, message ->
                        Bubble(
                            message, context, tts, index,
                            onPendingChoice = { i, p, r, c -> onPendingChoice(i, p, r, c) }
                        )
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
                val image = imageAttachment
                if (file != null || image != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (file != null) "📎 ${file.name}" else "🖼️ ${image?.name.orEmpty()}",
                            color = Color(0xFFFFB800),
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            attachment = null
                            imageAttachment = null
                        }) {
                            Icon(Icons.Filled.Close, "إزالة المرفق", tint = Color(0xFFFF8C00))
                        }
                    }
                }

                Surface(
                    color = Color(0xFF161616),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .navigationBarsPadding()
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    "اكتب رسالتك...",
                                    color = Color(0xFF7A7A7A),
                                    fontSize = 14.sp
                                )
                            },
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            maxLines = 4,
                            shape = RoundedCornerShape(18.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFFB800),
                                unfocusedBorderColor = Color(0xFF2A2A2A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFFFFB800)
                            )
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            InputAction(Icons.Filled.AttachFile, "ملف") {
                                fileLauncher.launch("*/*")
                            }
                            InputAction(Icons.Filled.Image, "صورة") {
                                imageLauncher.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                            InputAction(Icons.Filled.Mic, "صوت") { startVoice() }
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { showModelSheet = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    providerInfo(selectedProvider).shortName,
                                    color = Color(0xFFFFB800),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    Icons.Filled.ArrowDropDown,
                                    "اختيار الموديل",
                                    tint = Color(0xFFFFB800),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(2.dp))
                            FilledIconButton(
                                onClick = { sendMessage() },
                                enabled = !loading && (
                                    input.isNotBlank() || attachment != null || imageAttachment != null
                                ),
                                modifier = Modifier.size(42.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color(0xFFFFB800),
                                    contentColor = Color(0xFF0D0D0D),
                                    disabledContainerColor = Color(0xFF3A3A3A),
                                    disabledContentColor = Color(0xFF7A7A7A)
                                )
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    "إرسال",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InputAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            icon,
            description,
            tint = Color(0xFFFFB800),
            modifier = Modifier.size(22.dp)
        )
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
                    HorizontalDivider(color = Color(0xFF2A2A2A))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: android.content.SharedPreferences,
    autoSpeak: Boolean,
    onAutoSpeakChange: (Boolean) -> Unit,
    onBack: () -> Unit,
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
    var smartTitles by remember { mutableStateOf(prefs.getBoolean("smart_titles", true)) }
    var confirmActions by remember { mutableStateOf(prefs.getBoolean("confirm_actions", true)) }

    val current = providerInfo(provider)

    fun persist() {
        val editor = prefs.edit()
        editor.putString("provider", provider)
        keys.forEach { (id, value) -> editor.putString("api_key_$id", value) }
        models.forEach { (id, value) -> editor.putString("model_$id", value) }
        editor.putString("cf_account_id", accountId)
        editor.putBoolean("auto_speak", autoSpeak)
        editor.putBoolean("smart_titles", smartTitles)
        editor.putBoolean("confirm_actions", confirmActions)
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
                    IconButton(onClick = onBack) {
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
            Text(
                "🔑 مفتاح ${current.label}",
                color = Color(0xFFFFB800),
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = keys[provider].orEmpty(),
                onValueChange = { keys[provider] = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("الصق المفتاح هنا", color = Color(0xFF707070)) },
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
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

            if (current.needsAccountId) {
                Spacer(Modifier.height(12.dp))
                Text("Cloudflare Account ID", color = Color.White, fontSize = 14.sp)
                OutlinedTextField(
                    value = accountId,
                    onValueChange = { accountId = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("من لوحة Cloudflare", color = Color(0xFF707070)) },
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFFB800),
                        unfocusedBorderColor = Color(0xFF2A2A2A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFFFFB800)
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
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFFB800),
                    unfocusedBorderColor = Color(0xFF2A2A2A),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color(0xFFFFB800)
                )
            )

            if (current.modelHint.isNotBlank()) {
                Text(current.modelHint, color = Color(0xFF9A9A9A), fontSize = 11.sp)
            }

            TextButton(onClick = {
                runCatching { uriHandler.openUri(current.docsUrl) }
            }) {
                Text(
                    "📖 التوثيق الرسمي وحدود الاستخدام",
                    color = Color(0xFFFFB800),
                    fontSize = 12.sp
                )
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

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun Bubble(
    message: Message,
    context: Context,
    tts: TextToSpeech?,
    index: Int,
    onPendingChoice: (Int, PendingAction, Recipient?, String) -> Unit
) {
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
                val pending = message.pending
                if (pending != null) {
                    Spacer(Modifier.height(8.dp))
                    pending.candidates.forEach { r ->
                        Column(modifier = Modifier.padding(vertical = 3.dp)) {
                            Text("${r.name} • ${r.number}", color = Color(0xFFB0B0B0), fontSize = 12.sp)
                            Row {
                                Button(
                                    onClick = { onPendingChoice(index, pending, r, "sms") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800)),
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        if (pending.kind == ActionKind.CALL) "📞 اتصال" else "📱 SMS",
                                        color = Color(0xFF0D0D0D),
                                        fontSize = 12.sp
                                    )
                                }
                                if (pending.kind == ActionKind.SMS && pending.triggerAt == null && r.waNumber != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Button(
                                        onClick = { onPendingChoice(index, pending, r, "whatsapp") },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("💬 واتساب", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = { onPendingChoice(index, pending, null, "") }) {
                        Text("✖ إلغاء", color = Color(0xFFE94560), fontSize = 12.sp)
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

@Composable
fun ChatRow(
    meta: ChatMeta,
    selected: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onAutoTitle: () -> Unit,
    onArchiveToggle: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .background(
                if (selected) Color(0xFF2A2310) else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onOpen)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            meta.title,
            color = if (selected) Color(0xFFFFB800) else Color.White,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, "خيارات", tint = Color(0xFF9A9A9A))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("✏️ تغيير الاسم") },
                    onClick = {
                        menu = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text("✨ عنوان تلقائي") },
                    onClick = {
                        menu = false
                        onAutoTitle()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (meta.archived) "📤 إلغاء الأرشفة" else "📦 أرشفة") },
                    onClick = {
                        menu = false
                        onArchiveToggle()
                    }
                )
                DropdownMenuItem(
                    text = { Text("🗑️ حذف", color = Color(0xFFE94560)) },
                    onClick = {
                        menu = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(
    items: List<ScheduledItem>,
    onBack: () -> Unit,
    onDelete: (Int) -> Unit
) {
    Scaffold(
        containerColor = Color(0xFF0D0D0D),
        topBar = {
            TopAppBar(
                title = { Text("⏰ المجدولات (${items.size})", color = Color(0xFFFFB800)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "رجوع", tint = Color(0xFFFFB800))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "لا توجد تذكيرات أو رسائل مجدولة.\nجرّب: ذكرني بعد نصف ساعة أن أتصل بأحمد",
                    color = Color(0xFFB0B0B0)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val label = when (item.kind) {
                                ActionKind.REMINDER -> "⏰ تذكير"
                                ActionKind.SMS -> "📱 رسالة إلى ${item.name}"
                                ActionKind.CALL -> "📞 اتصال بـ ${item.name}"
                            }
                            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(TimeFmt.whenText(item.whenMillis), color = Color(0xFFFFB800), fontSize = 12.sp)
                            if (item.text.isNotBlank()) {
                                Text(item.text, color = Color(0xFFB0B0B0), fontSize = 13.sp, maxLines = 3)
                            }
                        }
                        IconButton(onClick = { onDelete(item.id) }) {
                            Icon(Icons.Filled.Delete, "حذف", tint = Color(0xFFE94560))
                        }
                    }
                    HorizontalDivider(color = Color(0xFF2A2A2A))
                }
            }
        }
    }
}

