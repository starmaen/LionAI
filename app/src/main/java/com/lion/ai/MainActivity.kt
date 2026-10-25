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

data class Message(
    val text: String,
    val isUser: Boolean,
    val ytUrl: String? = null,
    val ytmUrl: String? = null,
    val anghamiUrl: String? = null,
    val browserUrl: String? = null,
    val pending: PendingAction? = null,
    val via: String? = null
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
    val loadingChats = remember { mutableStateMapOf<String, Boolean>() }
    var currentChat by remember { mutableStateOf(prefs.getString("last_chat", "default") ?: "default") }
    var chatMetas by remember { mutableStateOf(listOf<ChatMeta>()) }
    var scheduledItems by remember { mutableStateOf(listOf<ScheduledItem>()) }
    var renameTarget by remember { mutableStateOf<ChatMeta?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatMeta?>(null) }
    var autoSendTrigger by remember { mutableStateOf(0) }
    var autoRoute by remember { mutableStateOf(prefs.getBoolean("auto_route", true)) }
    var editorInstruction by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editorImage by remember { mutableStateOf<ImageAttachment?>(null) }
    var editAfterPick by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var autoSpeak by remember { mutableStateOf(prefs.getBoolean("auto_speak", false)) }
    var attachment by remember { mutableStateOf<Attachment?>(null) }
    var imageAttachment by remember { mutableStateOf<ImageAttachment?>(null) }
    var contacts by remember { mutableStateOf(listOf<Contact>()) }
    var selectedProvider by remember {
        mutableStateOf(prefs.getString("provider", "gemini") ?: "gemini")
    }
    var tabs by remember { mutableStateOf(TabsStore.load(prefs)) }
    var activeTabId by remember { mutableStateOf(TabsStore.loadActive(prefs)) }
    val tabDrafts = remember { mutableStateMapOf<String, String>() }
    val tabAttachments = remember { mutableStateMapOf<String, Attachment?>() }
    val tabImages = remember { mutableStateMapOf<String, ImageAttachment?>() }
    var showModelSheet by remember { mutableStateOf(false) }
    val modelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showWebSheet by remember { mutableStateOf(false) }
    val webSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showMediaSheet by remember { mutableStateOf(false) }
    val mediaSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // علامات التبويب: نضمن وجود علامة نشطة واحدة على الأقل عند الإقلاع
    if (tabs.isEmpty()) {
        tabs = listOf(
            ChatTab(
                id = TabsStore.newId(),
                name = "محادثة 1",
                chatId = currentChat,
                provider = selectedProvider,
                autoRoute = autoRoute
            )
        )
        activeTabId = tabs.first().id
        TabsStore.save(prefs, tabs)
        TabsStore.saveActive(prefs, activeTabId)
    }
    if (tabs.none { it.id == activeTabId }) {
        activeTabId = tabs.first().id
        TabsStore.saveActive(prefs, activeTabId)
    }
    val activeTab = tabs.first { it.id == activeTabId }

    fun refreshChats() {
        chatMetas = ChatRepository.all(prefs)
    }

    fun saveCurrent() {
        ChatRepository.save(prefs, currentChat, messages)
        refreshChats()
    }

    fun syncActiveTab() {
        val tab = tabs.firstOrNull { it.id == activeTabId } ?: return
        val updated = tab.copy(provider = selectedProvider, autoRoute = autoRoute)
        tabs = tabs.map { if (it.id == updated.id) updated else it }
        TabsStore.save(prefs, tabs)
    }

    fun stashStateFor(tabId: String) {
        tabDrafts[tabId] = input
        tabAttachments[tabId] = attachment
        tabImages[tabId] = imageAttachment
    }

    fun restoreStateFor(tabId: String) {
        input = tabDrafts[tabId] ?: ""
        attachment = tabAttachments[tabId]
        imageAttachment = tabImages[tabId]
    }

    fun switchTab(tabId: String) {
        if (tabId == activeTabId) return
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        stashStateFor(activeTabId)
        activeTabId = tabId
        TabsStore.saveActive(prefs, tabId)
        selectedProvider = tab.provider
        autoRoute = tab.autoRoute
        prefs.edit().putString("provider", tab.provider).putBoolean("auto_route", tab.autoRoute).apply()
        messages = ChatRepository.load(prefs, tab.chatId)
        currentChat = tab.chatId
        restoreStateFor(tabId)
        prefs.edit().putString("last_chat", tab.chatId).apply()
    }

    fun addTab() {
        if (tabs.size >= TabsStore.MAX_TABS) return
        val chatId = ChatRepository.newId()
        val tab = ChatTab(
            id = TabsStore.newId(),
            name = if (autoRoute) "🧠 تلقائي" else providerInfo(selectedProvider).shortName,
            chatId = chatId,
            provider = selectedProvider,
            autoRoute = autoRoute
        )
        stashStateFor(activeTabId)
        tabs = tabs + tab
        activeTabId = tab.id
        TabsStore.save(prefs, tabs)
        TabsStore.saveActive(prefs, tab.id)
        messages = emptyList()
        currentChat = chatId
        input = ""
        attachment = null
        imageAttachment = null
        prefs.edit().putString("last_chat", chatId).apply()
    }

    fun closeTab(tabId: String) {
        if (tabs.size <= 1) return
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        tabs = tabs.filterNot { it.id == tabId }
        tabDrafts.remove(tabId)
        tabAttachments.remove(tabId)
        tabImages.remove(tabId)
        TabsStore.save(prefs, tabs)
        if (activeTabId == tabId) {
            val target = tabs.getOrNull(index.coerceAtMost(tabs.size - 1)) ?: tabs.first()
            activeTabId = target.id
            TabsStore.saveActive(prefs, target.id)
            selectedProvider = target.provider
            autoRoute = target.autoRoute
            prefs.edit().putString("provider", target.provider).putBoolean("auto_route", target.autoRoute).apply()
            messages = ChatRepository.load(prefs, target.chatId)
            currentChat = target.chatId
            restoreStateFor(target.id)
            prefs.edit().putString("last_chat", target.chatId).apply()
        }
    }

    fun detachChatFromTabs(chatId: String) {
        if (tabs.none { it.chatId == chatId }) return
        if (tabs.all { it.chatId == chatId }) {
            // كل العلامات كانت على هذه المحادثة: نربطها بالمحادثة الحالية الجديدة
            tabs = tabs.map { it.copy(chatId = currentChat) }
            TabsStore.save(prefs, tabs)
        } else {
            tabs.filter { it.chatId == chatId }.forEach { closeTab(it.id) }
        }
    }

    fun openChat(id: String) {
        messages = ChatRepository.load(prefs, id)
        currentChat = id
        input = ""
        attachment = null
        imageAttachment = null
        prefs.edit().putString("last_chat", id).apply()
        val tab = tabs.firstOrNull { it.id == activeTabId }
        if (tab != null && tab.chatId != id) {
            tabs = tabs.map { if (it.id == tab.id) it.copy(chatId = id) else it }
            TabsStore.save(prefs, tabs)
        }
    }

    fun startNewChat() {
        val id = ChatRepository.newId()
        messages = emptyList()
        currentChat = id
        input = ""
        attachment = null
        imageAttachment = null
        prefs.edit().putString("last_chat", id).apply()
        val tab = tabs.firstOrNull { it.id == activeTabId }
        if (tab != null && tab.chatId != id) {
            tabs = tabs.map { if (it.id == tab.id) it.copy(chatId = id) else it }
            TabsStore.save(prefs, tabs)
        }
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

    fun keyOf(provider: String): String = prefs.getString("api_key_$provider", "") ?: ""

    fun accountId(): String = prefs.getString("cf_account_id", "") ?: ""

    fun manualRoute(): Route =
        Route(selectedProvider, prefs.getString("model_$selectedProvider", "") ?: "")

    /** الوضع التلقائي: العقل المركزي يرتّب المسارات المتاحة. اليدوي: المزود الذي اخترته فقط. */
    fun planFor(kind: TaskKind): List<Route> =
        if (autoRoute) SmartRouter.plan(prefs, kind).ifEmpty { listOf(manualRoute()) } else listOf(manualRoute())

    fun generateTitle(chatId: String, force: Boolean) {
        if (!force && !prefs.getBoolean("smart_titles", true)) return
        val meta = ChatRepository.get(prefs, chatId) ?: return
        if (meta.titleSource == "user") return
        if (!force && meta.titleSource == "ai") return
        val list = ChatRepository.load(prefs, chatId)
        val firstUser = list.firstOrNull { it.isUser }?.text ?: return
        val firstReply = list.firstOrNull { !it.isUser && !LlmClient.isErrorReply(it.text) }?.text ?: return
        val route = planFor(TaskKind.FAST).first()
        val apiKey = keyOf(route.provider)
        val account = accountId()
        scope.launch {
            val title = withContext(Dispatchers.IO) {
                LlmClient.generateTitle(firstUser, firstReply, apiKey, route.provider, route.model, account)
            }
            if (!title.isNullOrBlank()) {
                ChatRepository.setAiTitle(prefs, chatId, title)
                refreshChats()
            }
        }
    }

    fun shareChat(meta: ChatMeta) {
        val text = buildString {
            append(meta.title).append("\n\n")
            ChatRepository.load(prefs, meta.id).forEach {
                append(if (it.isUser) "👤 " else "🦁 ").append(it.text).append("\n\n")
            }
        }
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, meta.title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, "مشاركة المحادثة"))
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
                    imageAttachment = null
                } else if (result.image != null) {
                    imageAttachment = result.image
                    attachment = null
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
                if (result.image != null && editAfterPick) {
                    editAfterPick = false
                    editorImage = result.image
                    showEditor = true
                } else if (result.image != null) {
                    imageAttachment = result.image
                    attachment = null
                } else {
                    editAfterPick = false
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
        LlmClient.reloadOverrides(prefs)
        refreshChats()
        scheduledItems = Scheduler.list(prefs)
        openChat(activeTab.chatId)

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
            val scheduleError = Scheduler.add(
                context, prefs,
                ScheduledItem(Scheduler.newId(), kind, triggerAt, r.name, r.number, text)
            )
            scheduledItems = Scheduler.list(prefs)
            if (scheduleError != null) return "⚠️ تعذرت جدولة المنبه: $scheduleError"
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
                val scheduleError = Scheduler.add(
                    context, prefs,
                    ScheduledItem(Scheduler.newId(), ActionKind.REMINDER, at, "", "", text, a.repeatMs)
                )
                scheduledItems = Scheduler.list(prefs)
                if (scheduleError != null) {
                    reply("⚠️ تعذرت جدولة التذكير: $scheduleError")
                    return
                }
                val notices = NotificationManagerCompat.from(context).areNotificationsEnabled()
                val firstTime = !prefs.getBoolean("reminder_hint_shown", false)
                if (firstTime) prefs.edit().putBoolean("reminder_hint_shown", true).apply()
                reply(
                    "✅ سأذكّرك ${TimeFmt.whenText(at)}:\n$text" +
                        (if (a.repeatMs > 0) "\n🔁 ${RepeatFmt.label(a.repeatMs)}" else "") +
                        (if (notices) "" else "\n⚠️ الإشعارات معطلة من إعدادات النظام.") +
                        (if (firstTime) "\n\n💡 إن لم يصلك التنبيه في موعده: الإعدادات ← 🔔 التنبيهات ← اختبار." else "")
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
        ytmUrl: String?,
        anghamiUrl: String?,
        browserUrl: String?,
        kind: TaskKind
    ) {
        val plan = planFor(kind)
        val auto = autoRoute
        loadingChats[chatId] = true
        scope.launch {
            var reply = ""
            var used: Route? = null
            val failed = mutableListOf<String>()
            for (route in plan) {
                val r = withContext(Dispatchers.IO) {
                    LlmClient.ask(prompt, keyOf(route.provider), route.provider, history, route.model, accountId(), image)
                }
                reply = r
                if (!LlmClient.isErrorReply(r)) {
                    used = route
                    break
                }
                failed.add(providerInfo(route.provider).shortName)
            }
            if (used == null && failed.size > 1) {
                reply += "\n\n(جرّبت بالترتيب: " + failed.joinToString("، ") + ")"
            }
            val via = used?.let { SmartRouter.label(it, if (auto) kind else null) }
            appendToChat(chatId, Message(reply, false, ytUrl, ytmUrl, anghamiUrl, browserUrl, via = via))
            loadingChats[chatId] = false
            if (autoSpeak && chatId == currentChat) {
                tts?.speak(reply.take(500), TextToSpeech.QUEUE_FLUSH, null, null)
            }
            if (used != null) {
                val replies = ChatRepository.load(prefs, chatId)
                    .count { !it.isUser && !LlmClient.isErrorReply(it.text) }
                if (replies == 1) generateTitle(chatId, false)
            }
        }
    }

    fun sendMessage() {
        if (loadingChats[currentChat] == true) return
        val text = input.trim()
        val file = attachment
        val image = imageAttachment
        if (text.isEmpty() && file == null && image == null) return

        val kind = SmartRouter.classify(text, image != null, file?.text?.length ?: 0, file?.name)
        if (!autoRoute && image != null && kind != TaskKind.IMAGE_EDIT &&
            !LlmClient.supportsVision(selectedProvider, prefs.getString("model_$selectedProvider", "") ?: "")
        ) {
            messages = messages + Message(
                "⚠️ الموديل الحالي لا يقرأ الصور. فعّل «🧠 تلقائي» من زر المزود أسفل مربع الكتابة (يختار موديلاً يقرأ الصور)، أو اختر Gemini أو Claude أو OpenAI أو Mistral. (محرر الصور في القائمة الجانبية يعمل مع أي مزود.)",
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

        // نستبعد رسائل الخطأ حتى لا تربك النموذج التالي، ويستمر الحوار كما هو حتى لو تبدّل النموذج
        val history = messages
            .filterNot { !it.isUser && LlmClient.isErrorReply(it.text) }
            .map { ChatTurn(if (it.isUser) "user" else "assistant", it.text) }

        val chatId = currentChat
        messages = messages + Message(displayText, true)
        input = ""
        attachment = null
        imageAttachment = null
        saveCurrent()

        if (image != null && kind == TaskKind.IMAGE_EDIT) {
            appendToChat(
                chatId,
                Message(
                    "🖼️ فتحت محرر الصور وسأنفّذ طلبك: «$text»\nيمكنك تعديل النتيجة يدوياً ثم حفظها أو إرسالها للتحليل.",
                    false,
                    via = "العقل المركزي · " + kind.label
                )
            )
            editorImage = image
            editorInstruction = text
            showEditor = true
            return
        }

        val musicKeywords = listOf("أغنية", "اغنية", "أغني", "اغني", "شغل", "موسيقى", "مغني", "مطرب", "فنان", "song", "music", "singer")
        val videoKeywords = listOf("فيلم", "مسلسل", "فلم", "movie", "film", "series", "trailer", "اعلان فيلم")
        val wantsMusic = musicKeywords.any { text.contains(it, ignoreCase = true) }
        val wantsVideo = videoKeywords.any { text.contains(it, ignoreCase = true) }
        val wantsMedia = wantsMusic || wantsVideo
        val query = text
            .replace(Regex("(أريد|اريد|شغل|أغنية|اغنية|أغني|اغني|لي|موسيقى|فيلم|مسلسل|فلم)"), "")
            .trim()
        val ytUrl = if (wantsMedia && query.isNotEmpty()) {
            "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        } else {
            null
        }
        val ytmUrl = if (wantsMusic && query.isNotEmpty()) {
            "https://music.youtube.com/search?q=" + Uri.encode(query)
        } else {
            null
        }
        val anghamiUrl = if (wantsMusic && query.isNotEmpty()) {
            "https://play.anghami.com/search/" + Uri.encode(query)
        } else {
            null
        }
        val browserUrl = if (wantsMedia && query.isNotEmpty()) {
            "https://www.google.com/search?q=" + Uri.encode(query)
        } else {
            null
        }

        if (file == null && image == null && ActionDetector.looksLikeCommand(text)) {
            loadingChats[chatId] = true
            scope.launch {
                val route = planFor(TaskKind.GENERAL).first()
                if (contacts.isEmpty() && hasPermission(Manifest.permission.READ_CONTACTS)) {
                    contacts = withContext(Dispatchers.IO) { loadContactsFromDevice(context) }
                }
                val parsed = withContext(Dispatchers.IO) {
                    ActionResolver.resolve(text, keyOf(route.provider), route.provider, route.model, accountId())
                }
                loadingChats[chatId] = false
                if (parsed != null) {
                    handleAction(chatId, parsed)
                } else {
                    runChat(chatId, prompt, history, image, ytUrl, ytmUrl, anghamiUrl, browserUrl, kind)
                }
            }
            return
        }

        runChat(chatId, prompt, history, image, ytUrl, ytmUrl, anghamiUrl, browserUrl, kind)
    }

    LaunchedEffect(autoSendTrigger) {
        if (autoSendTrigger > 0) sendMessage()
    }

    // احتياط: أثناء فتح التطبيق نفحص المواعيد بأنفسنا (إشعار + قراءة صوتية) دون انتظار المنبه
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            val now = System.currentTimeMillis()
            val due = Scheduler.list(prefs).filter { it.whenMillis <= now }
            if (due.isNotEmpty()) {
                due.forEach { item ->
                    if (FiredTracker.mark(item.id, item.whenMillis)) {
                        if (now - item.whenMillis > 10 * 60_000L) {
                            ActionRunner.missed(context, item)
                        } else {
                            ActionRunner.fire(context, item)
                            if (item.kind == ActionKind.REMINDER) {
                                runCatching {
                                    context.startActivity(
                                        AlarmIntents.forReminder(
                                            context, item.id, item.text.ifBlank { "حان موعد التذكير" }, item.whenMillis
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (item.repeatMs > 0) {
                        Scheduler.afterFire(context, prefs, item)
                    } else {
                        Scheduler.cancel(context, prefs, item.id)
                    }
                }
                scheduledItems = Scheduler.list(prefs)
            }
        }
    }

    val editing = editorImage
    if (showEditor && editing != null) {
        ImageEditorScreen(
            initial = editing,
            autoInstruction = editorInstruction,
            planEdit = { request ->
                var result: String? = null
                for (route in planFor(TaskKind.IMAGE_EDIT).take(3)) {
                    result = withContext(Dispatchers.IO) {
                        LlmClient.planImageEdit(request, keyOf(route.provider), route.provider, route.model, accountId())
                    }
                    if (result != null) break
                }
                result
            },
            onBack = {
                showEditor = false
                editorInstruction = null
            },
            onUse = { edited ->
                imageAttachment = edited
                attachment = null
                showEditor = false
                editorInstruction = null
            }
        )
        return
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
                LlmClient.reloadOverrides(prefs)
                autoRoute = prefs.getBoolean("auto_route", true)
                selectedProvider = prefs.getString("provider", "gemini") ?: "gemini"
                syncActiveTab()
            },
            onSave = {
                showSettings = false
                LlmClient.reloadOverrides(prefs)
                autoRoute = prefs.getBoolean("auto_route", true)
                selectedProvider = prefs.getString("provider", "gemini") ?: "gemini"
                syncActiveTab()
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
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    "اختر الموديل",
                    color = Color(0xFFFFB800),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            prefs.edit().putBoolean("auto_route", true).apply()
                            autoRoute = true
                            showModelSheet = false
                            syncActiveTab()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = autoRoute,
                        onClick = {
                            prefs.edit().putBoolean("auto_route", true).apply()
                            autoRoute = true
                            showModelSheet = false
                            syncActiveTab()
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFB800))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🧠 تلقائي (العقل المركزي)", color = Color(0xFFFFB800), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "يختار الأنسب لكل سؤال (سريع، برمجة، صور...) ويجرّب بديلاً إذا فشل، والحوار يبقى واحداً",
                            color = Color(0xFF9A9A9A),
                            fontSize = 11.sp
                        )
                    }
                }
                PROVIDERS.filter { it.webviewUrl == null }.forEach { info ->
                    val configured = (prefs.getString("model_${info.id}", "") ?: "")
                        .ifBlank { info.defaultModel }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (info.webviewUrl != null) {
                                    showModelSheet = false
                                    context.startActivity(
                                        Intent(context, ArenaActivity::class.java).putExtra("ARENA_URL", info.webviewUrl)
                                    )
                                } else {
                                    prefs.edit().putString("provider", info.id).apply()
                                    selectedProvider = info.id
                                    prefs.edit().putBoolean("auto_route", false).apply()
                                    autoRoute = false
                                    showModelSheet = false
                                    syncActiveTab()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = !autoRoute && selectedProvider == info.id,
                            onClick = {
                                if (info.webviewUrl != null) {
                                    showModelSheet = false
                                    context.startActivity(
                                        Intent(context, ArenaActivity::class.java).putExtra("ARENA_URL", info.webviewUrl)
                                    )
                                } else {
                                    prefs.edit().putString("provider", info.id).apply()
                                    selectedProvider = info.id
                                    prefs.edit().putBoolean("auto_route", false).apply()
                                    autoRoute = false
                                    showModelSheet = false
                                    syncActiveTab()
                                }
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFB800))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                info.label + (if (info.webviewUrl != null) "  🌐" else if (info.free) "  🟢" else "  🔵"),
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

    if (showWebSheet) {
        ModalBottomSheet(
            onDismissRequest = { showWebSheet = false },
            sheetState = webSheetState,
            containerColor = Color(0xFF1A1A1A)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    "دردشات الويب المجانية (بدون مفتاح)",
                    color = Color(0xFFFFB800),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                PROVIDERS.filter { it.webviewUrl != null && it.category == "chat" }.forEach { info ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showWebSheet = false
                                context.startActivity(
                                    Intent(context, ArenaActivity::class.java).putExtra("ARENA_URL", info.webviewUrl)
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🌐", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(info.label, color = Color.White, fontSize = 14.sp)
                            Text(info.tagline, color = Color(0xFF9A9A9A), fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    if (showMediaSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMediaSheet = false },
            sheetState = mediaSheetState,
            containerColor = Color(0xFF1A1A1A)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    "استوديو الوسائط المجاني (بدون مفتاح)",
                    color = Color(0xFFFFB800),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                val mediaSections = listOf(
                    "🖼️ توليد وتعديل الصور" to "image",
                    "🎬 فيديو وأنيميشن" to "video",
                    "🎵 صوت وموسيقى" to "audio"
                )
                mediaSections.forEach { (sectionTitle, cat) ->
                    val items = PROVIDERS.filter { it.category == cat }
                    if (items.isNotEmpty()) {
                        Text(
                            sectionTitle,
                            color = Color(0xFF7CC47C),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        items.forEach { info ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showMediaSheet = false
                                        context.startActivity(
                                            Intent(context, ArenaActivity::class.java).putExtra("ARENA_URL", info.webviewUrl)
                                        )
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("▫️", fontSize = 16.sp)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(info.label, color = Color.White, fontSize = 14.sp)
                                    Text(info.tagline, color = Color(0xFF9A9A9A), fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                        HorizontalDivider(color = Color(0xFF2A2A2A))
                    }
                }
            }
        }
    }

    LaunchedEffect(messages.size, loadingChats[currentChat]) {
        val count = messages.size + if (loadingChats[currentChat] == true) 1 else 0
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
                    detachChatFromTabs(deleting.id)
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
                    Button(
                        onClick = {
                            editAfterPick = true
                            imageLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A2E4A))
                    ) {
                        Text("🖼️ محرر الصور", color = Color.White)
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
                                onDelete = { deleteTarget = meta },
                                onShare = { shareChat(meta) }
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
                                        onDelete = { deleteTarget = meta },
                                        onShare = { shareChat(meta) }
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
                        IconButton(onClick = { addTab() }) {
                            Icon(Icons.Filled.Add, "علامة تبويب جديدة", tint = Color(0xFFFFB800))
                        }
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
                if (tabs.size > 1) {
                    TabsBar(
                        tabs = tabs,
                        activeTabId = activeTabId,
                        onSelect = { switchTab(it) },
                        onClose = { closeTab(it) },
                        onAdd = { addTab() }
                    )
                }
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
                    if (loadingChats[currentChat] == true) {
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
                        if (image != null) {
                            TextButton(onClick = {
                                editorImage = image
                                showEditor = true
                            }) {
                                Text("✏️ تعديل", color = Color(0xFFFFB800), fontSize = 12.sp)
                            }
                        }
                        IconButton(onClick = {
                            attachment = null
                            imageAttachment = null
                        }) {
                            Icon(Icons.Filled.Close, "إزالة المرفق", tint = Color(0xFFFF8C00))
                        }
                    }
                    val quickPrompts = if (image != null) {
                        listOf(
                            "📝 صف الصورة" to "صف هذه الصورة بالتفصيل.",
                            "🔤 استخرج النص" to "استخرج كل النص الظاهر في هذه الصورة كما هو.",
                            "🌐 ترجم النص" to "استخرج النص الظاهر في الصورة ثم ترجمه إلى العربية.",
                            "🔍 حلّل" to "حلّل هذه الصورة: العناصر والألوان والسياق وأي تفاصيل مهمة.",
                            "💡 اقترح تحسيناً" to "كيف يمكن تحسين هذه الصورة أو التصميم؟ اقترح تعديلات عملية."
                        )
                    } else {
                        listOf(
                            "📄 لخّص" to "لخّص هذا الملف في نقاط واضحة.",
                            "🔎 استخرج الأهم" to "استخرج أهم المعلومات والأرقام من هذا الملف.",
                            "🌐 ترجم" to "ترجم محتوى هذا الملف إلى العربية.",
                            "🛠 راجع" to "راجع هذا الملف وأخبرني بالأخطاء والتحسينات الممكنة."
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        quickPrompts.forEach { (label, prompt) ->
                            TextButton(
                                onClick = { input = prompt },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Text(label, color = Color(0xFFB0B0B0), fontSize = 12.sp)
                            }
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
                                    if (autoRoute) "🧠 تلقائي" else providerInfo(selectedProvider).shortName,
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
                            IconButton(
                                onClick = { showWebSheet = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("🌐", fontSize = 16.sp)
                            }
                            IconButton(
                                onClick = { showMediaSheet = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("🎬", fontSize = 16.sp)
                            }
                            Spacer(Modifier.width(2.dp))
                            FilledIconButton(
                                onClick = { sendMessage() },
                                enabled = loadingChats[currentChat] != true && (
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
    val ctx = LocalContext.current
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
    var autoRouteSetting by remember { mutableStateOf(prefs.getBoolean("auto_route", true)) }
    val settingsScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    val endpoints = remember {
        mutableStateMapOf<String, String>().apply {
            PROVIDERS.forEach { info -> put(info.id, prefs.getString("endpoint_" + info.id, "") ?: "") }
        }
    }
    val modelCache = remember {
        mutableStateMapOf<String, List<String>>().apply {
            PROVIDERS.forEach { info -> put(info.id, ModelCatalog.cached(prefs, info.id)) }
        }
    }
    var confirmActions by remember { mutableStateOf(prefs.getBoolean("confirm_actions", true)) }

    val current = providerInfo(provider)
    val versionName = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "1.4.0"
    }

    fun persist() {
        val editor = prefs.edit()
        editor.putString("provider", provider)
        keys.forEach { (id, value) -> editor.putString("api_key_$id", value.filterNot { it.isWhitespace() }) }
        endpoints.forEach { (id, value) -> editor.putString("endpoint_$id", value.trim()) }
        models.forEach { (id, value) -> editor.putString("model_$id", value) }
        editor.putString("cf_account_id", accountId)
        editor.putBoolean("auto_speak", autoSpeak)
        editor.putBoolean("smart_titles", smartTitles)
        editor.putBoolean("auto_route", autoRouteSetting)
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
            Text(
                if (current.free) "الموديل — اختر من القائمة أو اكتب اسماً" else "الموديل (اختياري)",
                color = Color.White,
                fontSize = 14.sp
            )
            val modelOptions = ModelCatalog.options(current, modelCache[provider].orEmpty())
            val activeModel = models[provider].orEmpty().ifBlank { current.defaultModel }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = models[provider].orEmpty(),
                    onValueChange = { models[provider] = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(current.defaultModel, color = Color(0xFF707070)) },
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { modelMenuOpen = true }) {
                            Icon(Icons.Filled.ArrowDropDown, "قائمة الموديلات", tint = Color(0xFFFFB800))
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFFB800),
                        unfocusedBorderColor = Color(0xFF2A2A2A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFFFFB800)
                    )
                )
                DropdownMenu(
                    expanded = modelMenuOpen,
                    onDismissRequest = { modelMenuOpen = false },
                    modifier = Modifier.heightIn(max = 380.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("↺ الافتراضي (${current.defaultModel})", color = Color(0xFFFFB800)) },
                        onClick = {
                            models[provider] = ""
                            modelMenuOpen = false
                        }
                    )
                    modelOptions.forEach { id ->
                        DropdownMenuItem(
                            text = { Text(if (id == activeModel) "$id  ✓" else id) },
                            onClick = {
                                models[provider] = id
                                modelMenuOpen = false
                            }
                        )
                    }
                }
            }

            var modelStatus by remember(provider) { mutableStateOf("") }
            TextButton(
                enabled = !refreshing,
                onClick = {
                    refreshing = true
                    modelStatus = "⏳ جارٍ جلب القائمة..."
                    val pid = provider
                    val key = keys[pid].orEmpty().trim()
                    val account = accountId.trim()
                    settingsScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { LlmClient.listModels(pid, key, account) }
                        }
                        val list = result.getOrNull()
                        if (!list.isNullOrEmpty()) {
                            modelCache[pid] = list
                            ModelCatalog.saveCache(prefs, pid, list)
                            modelStatus = "✅ تم جلب ${list.size} موديل. افتح القائمة ▼ للاختيار."
                        } else {
                            modelStatus = "⚠️ " + (result.exceptionOrNull()?.message ?: "لم يتم العثور على موديلات")
                        }
                        refreshing = false
                    }
                }
            ) {
                Text("🔄 تحديث قائمة الموديلات من الإنترنت", color = Color(0xFFFFB800), fontSize = 12.sp)
            }
            if (modelStatus.isNotBlank()) {
                Text(modelStatus, color = Color(0xFFB0B0B0), fontSize = 12.sp)
            }

            var testStatus by remember(provider) { mutableStateOf("") }
            var testing by remember { mutableStateOf(false) }
            TextButton(
                enabled = !testing,
                onClick = {
                    testing = true
                    testStatus = "⏳ أختبر المفتاح..."
                    val pid = provider
                    val key = keys[pid].orEmpty()
                    val model = models[pid].orEmpty()
                    val account = accountId
                    // نحفظ العنوان الوسيط الحالي مؤقتاً ليشمله الاختبار
                    LlmClient.endpointOverrides = LlmClient.endpointOverrides + (pid to endpoints[pid].orEmpty().trim())
                    settingsScope.launch {
                        val reply = withContext(Dispatchers.IO) {
                            LlmClient.ask("أجب بكلمة واحدة فقط: تم", key, pid, emptyList(), model, account)
                        }
                        testStatus = if (LlmClient.isErrorReply(reply)) reply else "✅ المفتاح والموديل يعملان. الرد: " + reply.take(80)
                        testing = false
                    }
                }
            ) {
                Text("🧪 اختبار المفتاح والموديل", color = Color(0xFFFFB800), fontSize = 12.sp)
            }
            if (testStatus.isNotBlank()) {
                Text(testStatus, color = Color(0xFFB0B0B0), fontSize = 12.sp)
            }

            if (current.endpoint.isNotBlank() || provider in setOf("groq", "openrouter", "opencode", "deepseek", "openai")) {
                Spacer(Modifier.height(8.dp))
                Text("عنوان وسيط (اختياري)", color = Color.White, fontSize = 13.sp)
                OutlinedTextField(
                    value = endpoints[provider].orEmpty(),
                    onValueChange = { endpoints[provider] = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text("https://اسم-الوسيط.workers.dev/openai/v1/chat/completions", color = Color(0xFF707070), fontSize = 11.sp)
                    },
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFFB800),
                        unfocusedBorderColor = Color(0xFF2A2A2A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFFFFB800)
                    )
                )
                Text(
                    "للشبكات التي يحجبها المزود: ضع رابط وسيط يمرّر الطلبات إليه (مثل Cloudflare Worker). اتركه فارغاً للاتصال المباشر.",
                    color = Color(0xFF9A9A9A),
                    fontSize = 11.sp
                )
            }

            if (current.keyless) {
                Text(
                    "✅ هذا المزود يعمل بدون مفتاح. المفتاح اختياري لرفع الحدود فقط.",
                    color = Color(0xFF7CC47C),
                    fontSize = 12.sp
                )
            }
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

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = autoRouteSetting,
                    onCheckedChange = { autoRouteSetting = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFB800))
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("🧠 العقل المركزي (اختيار النموذج تلقائياً)", color = Color.White)
                    Text(
                        "يصنّف سؤالك ويختار الأنسب من المزودات المتاحة لديك، ويجرّب بديلاً عند الفشل. أطفئه لاستعمال المزود المحدد يدوياً.",
                        color = Color(0xFF9A9A9A),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF2A2A2A))
            Spacer(Modifier.height(8.dp))
            Text("🔔 التنبيهات والتذكيرات", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold)
            var alarmStatus by remember { mutableStateOf("") }
            Text(
                if (Notifier.enabled(ctx)) "الإشعارات: مفعّلة في النظام ✅" else "الإشعارات: معطّلة في النظام ❌ — فعّلها من الزر أدناه",
                color = Color(0xFFB0B0B0),
                fontSize = 12.sp
            )
            TextButton(onClick = {
                val ok = Notifier.show(ctx, 7001, "🔔 اختبار", "إذا ظهر لك هذا الإشعار فالتنبيهات تعمل بشكل سليم.")
                alarmStatus = if (ok) "تم إرسال إشعار تجريبي. هل ظهر؟" else "⚠️ تعذر إرسال الإشعار (تحقق من صلاحية الإشعارات)."
            }) { Text("🔔 اختبار إشعار الآن", color = Color(0xFFFFB800), fontSize = 13.sp) }
            TextButton(onClick = {
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

// استخراج كتل الكود من النص (Markdown style: ```lang ... ```)
private fun extractCodeBlocks(text: String): List<Pair<String, String>> {
    val blocks = mutableListOf<Pair<String, String>>()
    val pattern = Regex("```([\\w]*)\\n([\\s\\S]*?)```")
    pattern.findAll(text).forEach { match ->
        val lang = match.groupValues[1].ifEmpty { "code" }
        val code = match.groupValues[2].trim()
        blocks.add(lang to code)
    }
    return blocks
}

// استخراج الروابط من النص
private fun extractLinks(text: String): List<String> {
    val links = mutableListOf<String>()
    val pattern = Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
    pattern.findAll(text).forEach { match ->
        val url = match.value.trimEnd(')', ']', '}', ',', '.', ';', ':', '!')
        if (url !in links) links.add(url)
    }
    return links
}

// إزالة كتل الكود من النص للعرض العادي
private fun removeCodeBlocks(text: String): String {
    return Regex("```[\\w]*\\n[\\s\\S]*?```").replace(text, "")
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
                // عرض الأكواد في صناديق منفصلة
                val codeBlocks = if (!message.isUser) extractCodeBlocks(message.text) else emptyList()
                val links = if (!message.isUser) extractLinks(message.text) else emptyList()
                val cleanText = if (!message.isUser) removeCodeBlocks(message.text).trim() else message.text
                
                if (cleanText.isNotEmpty()) {
                    Text(
                        cleanText,
                        color = if (message.isUser) Color(0xFF0D0D0D) else Color.White,
                        fontSize = 15.sp
                    )
                }
                
                // عرض كل كتلة كود في صندوق منفصل مع زر نسخ
                codeBlocks.forEach { (lang, code) ->
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("📝 $lang", color = Color(0xFFFFB800), fontSize = 11.sp)
                                TextButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                                    },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("📋 نسخ", color = Color(0xFFFFB800), fontSize = 10.sp)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                code,
                                color = Color(0xFFB0B0B0),
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            )
                        }
                    }
                }
                
                // عرض الروابط في بطاقات منفصلة
                if (links.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    links.forEach { url ->
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🔗 $url", color = Color(0xFF64B5F6), fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                if (message.ytUrl != null || message.ytmUrl != null || message.anghamiUrl != null || message.browserUrl != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
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
                            Spacer(Modifier.width(6.dp))
                        }
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
                            Spacer(Modifier.width(6.dp))
                        }
                        if (message.anghamiUrl != null) {
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(message.anghamiUrl))
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF8B5CF6)
                                ),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("🎵 أنغامي", color = Color.White, fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        if (message.browserUrl != null) {
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(message.browserUrl))
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4285F4)
                                ),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("🌐 المتصفح", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (!message.isUser && message.via != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("🧠 " + message.via, color = Color(0xFF7A7A7A), fontSize = 10.sp)
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
    onDelete: () -> Unit,
    onShare: () -> Unit = {}
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
                    text = { Text("📤 مشاركة المحادثة") },
                    onClick = {
                        menu = false
                        onShare()
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
                            Text(
                                TimeFmt.whenText(item.whenMillis) +
                                    if (item.repeatMs > 0) "  🔁 ${RepeatFmt.label(item.repeatMs)}" else "",
                                color = Color(0xFFFFB800),
                                fontSize = 12.sp
                            )
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

