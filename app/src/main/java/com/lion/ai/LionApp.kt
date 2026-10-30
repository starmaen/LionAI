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
import androidx.compose.material.icons.filled.VolumeUp
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

/** نافذة سياق ذكية: آخر الرسائل ضمن ميزانية أحرف، فتقلّ مدة الطلب وحجمه بلا فقدان سياق مهم. */
private fun windowedHistory(messages: List<Message>, maxChars: Int = 12_000): List<ChatTurn> {
    val picked = ArrayDeque<ChatTurn>()
    var used = 0
    for (m in messages.asReversed()) {
        if (!m.isUser && LlmClient.isErrorReply(m.text)) continue
        val turn = ChatTurn(if (m.isUser) "user" else "assistant", m.text)
        if (picked.isNotEmpty() && used + turn.content.length > maxChars) break
        used += turn.content.length
        picked.addFirst(turn)
    }
    return picked.toList()
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LionApp() {
    val context = LocalContext.current
    ContextHolder.install(context)
    val prefs = remember { SecurePrefs.get(context) }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()

    var showSettings by remember { mutableStateOf(false) }
    var showGitHub by remember { mutableStateOf(false) }
    var showDevTools by remember { mutableStateOf(false) }
    var showConnectors by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var showScheduled by remember { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var input by remember { mutableStateOf("") }
    /** الرد الذي يستكمل المستخدم سياقه عبر زر "رد على هذا الرد". */
    var replyingTo by remember { mutableStateOf<Message?>(null) }
    val loadingChats = remember { mutableStateMapOf<String, Boolean>() }
    val toolStatus = remember { mutableStateMapOf<String, String>() }
    var currentChat by remember { mutableStateOf(prefs.getString("last_chat", "default") ?: "default") }
    var currentMode by remember { mutableStateOf(ChatRepository.modeOf(prefs, currentChat)) }
    var showModePicker by remember { mutableStateOf(false) }
    var promotionRequest by remember { mutableStateOf<String?>(null) }
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
    /** خادم الذكاء الاصطناعي المحلي (Proxy) يعمل على 127.0.0.1 داخل التطبيق. */
    var localProxyUrl by remember { mutableStateOf("") }
    var localProxyStarted by remember { mutableStateOf(false) }
    var attachment by remember { mutableStateOf<Attachment?>(null) }
    var imageAttachment by remember { mutableStateOf<ImageAttachment?>(null) }

    // تشغيل خادم الذكاء الاصطناعي المحلي (Proxy) عند بدء التطبيق
    LaunchedEffect(Unit) {
        val p = LocalAIProxy.start(context, prefs)
        if (p > 0) {
            localProxyUrl = "http://127.0.0.1:$p/v1/chat/completions"
            localProxyStarted = true
        }
    }
    var contacts by remember { mutableStateOf(listOf<Contact>()) }
    var selectedProvider by remember {
        mutableStateOf(prefs.getString("provider", "gemini") ?: "gemini")
    }
    var tabs by remember {
        mutableStateOf(
            TabsStore.load(prefs).map { tab ->
                if (providerInfo(tab.provider).group == ProviderGroup.API) tab
                else tab.copy(provider = "gemini", autoRoute = true)
            }
        )
    }
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
                provider = if (providerInfo(selectedProvider).group == ProviderGroup.API) selectedProvider else "gemini",
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
        val provider = if (providerInfo(selectedProvider).group == ProviderGroup.API) selectedProvider else tab.provider
        val updated = tab.copy(provider = provider, autoRoute = autoRoute)
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
        val provider = if (providerInfo(tab.provider).group == ProviderGroup.API) tab.provider else "gemini"
        selectedProvider = provider
        autoRoute = tab.autoRoute
        prefs.edit().putString("provider", provider).putBoolean("auto_route", tab.autoRoute).apply()
        messages = ChatRepository.load(prefs, tab.chatId)
        currentChat = tab.chatId
        currentMode = ChatRepository.modeOf(prefs, tab.chatId)
        restoreStateFor(tabId)
        prefs.edit().putString("last_chat", tab.chatId).apply()
    }

    fun addTab() {
        if (tabs.size >= TabsStore.MAX_TABS) return
        val chatId = ChatRepository.newId()
        val provider = if (providerInfo(selectedProvider).group == ProviderGroup.API) selectedProvider else "gemini"
        val tab = ChatTab(
            id = TabsStore.newId(),
            name = if (autoRoute) "🧠 تلقائي" else providerInfo(provider).shortName,
            chatId = chatId,
            provider = provider,
            autoRoute = autoRoute
        )
        stashStateFor(activeTabId)
        tabs = tabs + tab
        activeTabId = tab.id
        TabsStore.save(prefs, tabs)
        TabsStore.saveActive(prefs, tab.id)
        messages = emptyList()
        currentChat = chatId
        currentMode = ContextStore.defaultMode(prefs)
        ContextStore.write(prefs, chatId, currentMode)
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
            val provider = if (providerInfo(target.provider).group == ProviderGroup.API) target.provider else "gemini"
            selectedProvider = provider
            autoRoute = target.autoRoute
            prefs.edit().putString("provider", provider).putBoolean("auto_route", target.autoRoute).apply()
            messages = ChatRepository.load(prefs, target.chatId)
            currentChat = target.chatId
            currentMode = ChatRepository.modeOf(prefs, target.chatId)
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
        currentMode = ChatRepository.modeOf(prefs, id)
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

    fun startNewChat(mode: ChatMode = ContextStore.defaultMode(prefs)) {
        val id = ChatRepository.newId()
        messages = emptyList()
        currentChat = id
        currentMode = mode
        ContextStore.write(prefs, id, mode)
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

    /** يستبدل رداً قديماً بنتيجة إعادة التوليد في نفس الموضع. */
    fun replaceInChat(chatId: String, index: Int, message: Message): Boolean {
        if (chatId == currentChat) {
            if (index !in messages.indices || messages[index].isUser) return false
            messages = messages.toMutableList().also { it[index] = message }
            saveCurrent()
            return true
        }
        val list = ChatRepository.load(prefs, chatId).toMutableList()
        if (index !in list.indices || list[index].isUser) return false
        list[index] = message
        ChatRepository.save(prefs, chatId, list)
        refreshChats()
        return true
    }

    fun keyOf(provider: String): String = prefs.getString("api_key_$provider", "") ?: ""

    fun accountId(): String = prefs.getString("cf_account_id", "") ?: ""

    fun manualRoute(): Route {
        val selected = providerInfo(selectedProvider)
        // لا نبدّل مزود المستخدم بصمت أبداً. مزودات الويب لا تصلح للدردشة داخل المحادثة،
        // فنرجع لأول مزود API فقط في هذه الحالة النادرة، ونترك رسالة الخطأ الواضحة تظهر.
        val providerId = if (selected.group == ProviderGroup.API) {
            selectedProvider
        } else {
            PROVIDERS.firstOrNull { it.group == ProviderGroup.API }?.id ?: selectedProvider
        }
        return Route(providerId, prefs.getString("model_$providerId", "") ?: "")
    }

    /**
     * الوضع التلقائي: العقل المركزي يرتّب المسارات المتاحة.
     * الوضع اليدوي: مزود المستخدم أولاً ودون تبديل تلقائي، إلا إذا فعّل «مساعدة العقل المركزي».
     */
    fun planFor(kind: TaskKind, mode: AnswerMode? = null): List<Route> {
        if (autoRoute) {
            return SmartRouter.plan(prefs, kind, mode ?: AnswerMode.read(prefs)).ifEmpty { listOf(manualRoute()) }
        }
        val chosen = manualRoute()
        if (!prefs.getBoolean("manual_assist", false)) return listOf(chosen)
        val extras = SmartRouter.plan(prefs, kind, mode ?: AnswerMode.read(prefs))
            .filter { it.provider != chosen.provider }
            .take(2)
        return listOf(chosen) + extras
    }

    /** طول الرد المختار من الإعدادات، يُضاف إلى تعليمات النظام. */
    fun answerLengthSuffix(): String = when (prefs.getString("answer_length", "normal")) {
        "short" -> LLM_LENGTH_SHORT
        "long" -> LLM_LENGTH_LONG
        else -> ""
    }

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

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = pendingPermissionAction
        pendingPermissionAction = null
        val contactsAllowed = result[Manifest.permission.READ_CONTACTS] == true ||
            hasPermission(Manifest.permission.READ_CONTACTS)
        if (contactsAllowed && action == null) requestContacts()
        action?.invoke()
    }

    /** يطلب الصلاحيات الناقصة فقط، وينفّذ onDone بعد رد النظام (سواء مُنحت أو رُفضت). */
    fun requestPermissions(permissions: List<String>, onDone: (() -> Unit)? = null) {
        val missing = permissions.filterNot { hasPermission(it) }
        if (missing.isEmpty()) {
            onDone?.invoke()
            return
        }
        pendingPermissionAction = onDone
        permLauncher.launch(missing.toTypedArray())
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    /** جهات الاتصال تُطلب عند فتحها فقط، لا عند إقلاع التطبيق. */
    fun openContacts() {
        if (hasPermission(Manifest.permission.READ_CONTACTS)) {
            requestContacts()
            showContacts = true
            return
        }
        requestPermissions(listOf(Manifest.permission.READ_CONTACTS)) {
            if (hasPermission(Manifest.permission.READ_CONTACTS)) {
                requestContacts()
                showContacts = true
            }
        }
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

        // لا نطلب كل الصلاحيات عند الإقلاع. كل صلاحية تُطلب عند أول استخدام فعلي لها،
        // إلا إذا كان المستخدم منحها سابقاً فنجهّز جهات الاتصال مباشرة.
        if (hasPermission(Manifest.permission.READ_CONTACTS)) requestContacts()
    }

    fun startVoice() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) {
                if (hasPermission(Manifest.permission.RECORD_AUDIO)) startVoice()
            }
            return
        }
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
                requestPermissions(listOf(Manifest.permission.SEND_SMS))
                return "⚠️ اسمح بإرسال الرسائل من النافذة الظاهرة ثم أعد المحاولة."
            }
            requestNotificationPermission()
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
                requestNotificationPermission()
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

    fun completePendingChoice(index: Int, pending: PendingAction, recipient: Recipient?, channel: String) {
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

    fun onPendingChoice(index: Int, pending: PendingAction, recipient: Recipient?, channel: String) {
        // نطلب صلاحية الرسائل عند التأكيد فقط، ثم نكمل الإرسال أو الجدولة تلقائياً.
        if (recipient != null && pending.kind == ActionKind.SMS && channel != "whatsapp" &&
            !hasPermission(Manifest.permission.SEND_SMS)
        ) {
            requestPermissions(listOf(Manifest.permission.SEND_SMS)) {
                completePendingChoice(index, pending, recipient, channel)
            }
            return
        }
        completePendingChoice(index, pending, recipient, channel)
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
        kind: TaskKind,
        replaceIndex: Int? = null,
        forcePrecise: Boolean = false
    ) {
        val mode = if (forcePrecise) AnswerMode.PRECISE else AnswerMode.read(prefs)
        val chatMode = ChatRepository.modeOf(prefs, chatId)
        val assist = !autoRoute && prefs.getBoolean("manual_assist", false)
        // «المركزي» يعمل في الوضع التلقائي، أو في الوضع اليدوي فقط إذا فعّل المستخدم المساعدة.
        val central = autoRoute || assist
        val strict = central &&
            (SmartRouter.isStrict(kind) || (mode == AnswerMode.PRECISE && kind == TaskKind.GENERAL))
        val careful = central && (SmartRouter.isStrict(kind) || kind == TaskKind.GENERAL)
        val mission = CentralBrain.policy(kind, chatMode, central)
        val hasTrusted = SmartRouter.hasTrusted(prefs, kind)
        val plan = planFor(kind, mode)
        val auto = autoRoute
        val historyContext = history.takeLast(8).joinToString("\n") { it.content.take(2000) }
        val toolText = if (historyContext.isBlank()) prompt else prompt + "\n" + historyContext
        val modeProfile = ContextProfiles.of(chatMode, prefs, toolText)
        val basePrompt = when {
            chatMode == ChatMode.CODE -> LLM_DEV_PROMPT
            chatMode == ChatMode.LEARN -> LLM_SYSTEM_PROMPT
            kind == TaskKind.DEV -> LLM_DEV_PROMPT
            kind == TaskKind.FAST -> LLM_FAST_PROMPT
            SmartRouter.isStrict(kind) -> LLM_FACT_PROMPT
            careful -> LLM_CAREFUL_PROMPT
            else -> LLM_SYSTEM_PROMPT
        }
        val toolGroups = if (image == null && kind != TaskKind.FAST && modeProfile.allowTools) {
            modeProfile.groups
        } else {
            emptySet<ToolGroup>()
        }
        loadingChats[chatId] = true
        toolStatus[chatId] = "🧠 أجهّز المهمة…"
        val session = AgentSessions.of(prefs, chatId)
        var agentComplete = true
        var agentAsked = false
        scope.launch {
            // المصادر والروابط (يوتيوب/أنغامي/يوتيوب ميوزيك) ميزة مستقلة يحكمها إعدادها،
            // وتبقى تعمل في الوضع اليدوي أيضاً ولا يلغيها العقل المركزي.
            val sources = if (CentralBrain.shouldSearch(prefs, kind, chatMode, prompt, central)) {
                withContext(Dispatchers.IO) {
                    runCatching { WebGrounding.search(prompt) }.getOrDefault(emptyList<WebSource>())
                }
            } else {
                emptyList<WebSource>()
            }
            val driveContext = if (chatMode == ChatMode.CODE && image == null && kind != TaskKind.FAST &&
                ConnectorAuth.isConnected(prefs, "google_drive")
            ) {
                val driveId = Connectors.extractDriveId(prompt)
                if (driveId == null) null else withContext(Dispatchers.IO) {
                    runCatching {
                        Connectors.driveOpen(
                            ToolContext(context, prefs, AiTools.allowWrite(prefs)), driveId, 8000
                        )
                    }.getOrNull()
                }
            } else {
                null
            }
            val githubContext = if (chatMode == ChatMode.CODE && image == null && kind != TaskKind.FAST &&
                AiTools.githubReady(prefs) && AiTools.detectGitHubUrl(toolText) != null
            ) {
                runCatching { AiTools.githubContext(prefs, toolText, 6000) }.getOrDefault("")
            } else {
                ""
            }
            val githubNote = if (chatMode == ChatMode.CODE && kind != TaskKind.FAST &&
                AiTools.githubMentioned(toolText) && !AiTools.githubReady(prefs)
            ) {
                "\n\nملاحظة: GitHub غير مربوط حالياً. اطلب من المستخدم إضافة توكن GitHub من «الإعدادات ← أدوات الذكاء والتطوير»، ولا تدّعِ الوصول إلى GitHub."
            } else {
                ""
            }
            val toolsNote = if (chatMode == ChatMode.CODE && kind == TaskKind.DEV &&
                toolGroups.isEmpty() && !AiTools.agentEnabled(prefs)
            ) {
                "\n\nملاحظة: أدوات التطوير معطّلة من الإعدادات. لا تدّعِ أنك فحصت المستودع أو عدّلته؛ " +
                    "قدّم خطة دقيقة واطلب تفعيل «أدوات الذكاء والتطوير» لتنفيذها فعلياً."
            } else {
                ""
            }
            val projectBlock = if (modeProfile.injectProjectContext) {
                ProjectContext.block(context, prefs, chatId)
            } else {
                ""
            }
            val sessionBlock = session.contextBlock()
            val systemPrompt = basePrompt + modeProfile.promptSuffix + projectBlock + sessionBlock +
                answerLengthSuffix() +
                (if (sources.isNotEmpty()) "\n\n" + LLM_GROUNDED_RULES else "") + githubNote + toolsNote
            val baseLlmPrompt = if (sources.isEmpty()) {
                prompt
            } else {
                prompt + "\n\n" + WebGrounding.contextBlock(sources)
            }
            val withDrive = if (driveContext.isNullOrBlank()) baseLlmPrompt else
                baseLlmPrompt + "\n\n=== بيانات Google Drive من الرابط في رسالة المستخدم ===\n" + driveContext
            val llmPrompt = if (githubContext.isBlank()) withDrive else
                withDrive + "\n\n" + githubContext
            // العقل المركزي يضمن أن لكل مجال مزوداً بديلاً جاهزاً عند فشل المسار الأساسي.
            val rescueRoutes = if (central && mission.expandFallbacks) {
                CentralBrain.rescueRoutes(prefs, kind, plan, image != null)
            } else {
                emptyList()
            }
            val routesToTry = plan + rescueRoutes
            var reply = ""
            var used: Route? = null
            var fallback: Pair<Route, String>? = null
            var toolNames: List<String> = emptyList()
            var lastErrorReply = ""
            var sawFalseClaim = false
            val failed = mutableListOf<String>()
            val deadline = System.currentTimeMillis() + SmartRouter.timeoutMs(kind)
            // التحية لا تحتاج سجل الحوار كاملاً؛ نرسل آخر دورين فقط لتقليل الزمن.
            val requestHistory = if (kind == TaskKind.FAST) history.takeLast(2) else history
            for (route in routesToTry) {
                if (System.currentTimeMillis() > deadline) break
                val started = System.currentTimeMillis()
                val attemptCap = minOf(
                    (deadline - System.currentTimeMillis()).coerceAtLeast(5_000L),
                    LlmClient.attemptTimeoutMs(kind).toLong()
                ).toInt()
                var agentResult = if (toolGroups.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ToolAgent.run(
                                context = context,
                                prefs = prefs,
                                prompt = llmPrompt,
                                apiKey = keyOf(route.provider),
                                provider = route.provider,
                                model = route.model,
                                accountId = accountId(),
                                history = requestHistory,
                                baseSystemPrompt = systemPrompt,
                                groups = toolGroups,
                                contextText = toolText,
                                timeoutMs = (deadline - System.currentTimeMillis()).coerceAtLeast(30_000L),
                                session = session,
                                chatMode = chatMode,
                                forceExecution = kind == TaskKind.DEV || session.hasWork(),
                                providerLabel = SmartRouter.label(route, kind),
                                onProgress = { status -> toolStatus[chatId] = status }
                            )
                        }.getOrElse { ToolAgent.Result("⚠️ تعذر تشغيل الأدوات: ${it.message ?: it.javaClass.simpleName}", emptyList(), complete = false) }
                    }
                } else {
                    null
                }
                // إن فشل مسار الأدوات (خطأ مزود) نجرّب رداً عادياً بنفس المزود حتى لا تتوقف المحادثة.
                val failedAgent = agentResult
                if (failedAgent != null && !failedAgent.complete && failedAgent.tools.isEmpty() &&
                    LlmClient.isErrorReply(failedAgent.answer)
                ) {
                    val plain = withContext(Dispatchers.IO) {
                        runCatching {
                            LlmClient.ask(
                                message = llmPrompt,
                                apiKey = keyOf(route.provider),
                                provider = route.provider,
                                history = requestHistory,
                                modelOverride = route.model,
                                accountId = accountId(),
                                image = image,
                                systemPrompt = systemPrompt,
                                readTimeoutMs = attemptCap
                            )
                        }.getOrNull()
                    }
                    if (plain != null && !LlmClient.isErrorReply(plain)) {
                        agentResult = ToolAgent.Result(plain, emptyList())
                        toolStatus[chatId] = "↩️ تم تجاهل الأدوات مؤقتاً (فشل مسار الأدوات)"
                    }
                }
                val r = agentResult?.answer ?: withContext(Dispatchers.IO) {
                    LlmClient.ask(
                        message = llmPrompt,
                        apiKey = keyOf(route.provider),
                        provider = route.provider,
                        history = requestHistory,
                        modelOverride = route.model,
                        accountId = accountId(),
                        image = image,
                        systemPrompt = systemPrompt,
                        readTimeoutMs = attemptCap
                    )
                }
                val error = LlmClient.isErrorReply(r)
                if (agentResult != null) {
                    agentComplete = agentResult.complete
                    agentAsked = agentAsked || session.pendingQuestion != null
                }
                if (error && lastErrorReply.isBlank()) lastErrorReply = r
                val noTools = agentResult?.usedTools != true
                // ادّعاء تنفيذ تعديل/رفع/PR دون استدعاء أي أداة = هلوسة خطيرة تُرفض دائماً حتى في الوضع اليدوي.
                val falseClaim = !error && kind == TaskKind.DEV && noTools &&
                    AnswerGuard.claimsCompletedAction(r)
                if (falseClaim) sawFalseClaim = true
                val low = !error && noTools &&
                    (falseClaim || (central && AnswerGuard.isLowQuality(r, kind, prompt)))
                SmartRouter.record(route, !error && !low, System.currentTimeMillis() - started)
                if (!error && !low) {
                    reply = r
                    used = route
                    toolNames = agentResult?.tools.orEmpty()
                    LlmClient.recordSuccess(route.provider, route.model)
                    break
                }
                if (!error && !falseClaim && fallback == null) fallback = route to r
                failed.add(providerInfo(route.provider).shortName + if (low) " (رد غير موثوق)" else "")
            }
            if (used == null) {
                val f = fallback
                val canUseFallback = !sawFalseClaim && central &&
                    (sources.isNotEmpty() || !strict || !hasTrusted)
                if (sawFalseClaim) {
                    val toolsOff = !AiTools.agentEnabled(prefs)
                    val githubOff = !AiTools.githubReady(prefs)
                    reply = buildString {
                        append("⚠️ لم أنفّذ أي تعديل فعلي، ولم أرفع شيئاً إلى GitHub. ")
                        append("رفضت رداً ادّعى إتمام التعديل/الرفع دون استدعاء أي أداة (هذا ادّعاء غير صحيح).\n\n")
                        append("ما تحتاجه لتنفيذ المهمة فعلياً:\n")
                        if (toolsOff) {
                            append("• فعّل «أدوات الذكاء والتطوير» من الإعدادات (agent_tools).\n")
                        }
                        if (githubOff) {
                            append("• أضف توكن GitHub من «الإعدادات ← أدوات الذكاء والتطوير» وحدّد المستودع الافتراضي.\n")
                        }
                        if (!toolsOff && !githubOff) {
                            append("• المزود الحالي لم يستدعِ الأدوات رغم توفرها؛ جرّب مزوداً يدعم الأدوات (Claude/Gemini/DeepSeek/OpenAI) أو فعّل «مساعدة العقل المركزي».\n")
                        }
                        append("• أو أرفق مجلد المشروع/الملف المطلوب، وسأنفّذ التعديل وأتحقق منه أمامك خطوة بخطوة.")
                    }
                } else if (f != null && f.second.isNotBlank() && canUseFallback) {
                    reply = f.second
                    used = f.first
                    reply += if (sources.isNotEmpty() && auto && strict) {
                        "\n\n✅ الإجابة مستندة إلى مصادر الويب أعلاه، لكنها من مزود أقل قوة من المعتاد؛ راجع التفاصيل الحساسة."
                    } else if (strict) {
                        "\n\n⚠️ لم يتوفر مزود قوي لهذا السؤال؛ هذه إجابة من مزود مجاني. تحقق من التواريخ والأسماء قبل الاعتماد عليها."
                    } else {
                        "\n\n⚠️ قد تكون الإجابة غير موثوقة؛ أعد المحاولة أو اختر مزوداً أقوى."
                    }
                } else if (strict && hasTrusted) {
                    reply = "⚠️ لم أجد إجابة موثوقة من المزودات القوية المتاحة، ولم أعرض إجابة من مزود ضعيف لتجنب معلومات خاطئة.\nجرّب لاحقاً أو أضف مفتاح Gemini أو Groq أو OpenAI أو Claude."
                } else {
                    reply = lastErrorReply.ifBlank {
                        "⚠️ تعذر الوصول إلى مزود يرد حالياً. تحقق من الاتصال أو جرّب مزوداً آخر."
                    }
                    if (!central) {
                        reply += "\n\n🔒 الوضع اليدوي: بقيت على المزود الذي اخترته ولم أبدّل تلقائياً. " +
                            "تحقق من المفتاح/الموديل أو اختر مزوداً آخر، أو فعّل «مساعدة العقل المركزي في الوضع اليدوي» من الإعدادات."
                    }
                }
            }
            // العقل المركزي يدقّق الإجابات غير الموثوقة أو المعرفية غير المدعومة بمصادر.
            val answeredBy = used
            if (answeredBy != null && image == null && !prompt.contains("محتوى الملف (") &&
                System.currentTimeMillis() < deadline - 6_000
            ) {
                val judge = CentralBrain.judgeRoute(prefs, kind, answeredBy, routesToTry)
                if (judge != null && CentralBrain.needsVerification(
                        answeredBy, kind, mission,
                        judgeAvailable = true,
                        grounded = sources.isNotEmpty()
                    )
                ) {
                    val judgeStarted = System.currentTimeMillis()
                    val checkReply = withContext(Dispatchers.IO) {
                        LlmClient.ask(
                            message = CentralBrain.verificationPrompt(prompt, reply, sources, chatMode),
                            apiKey = keyOf(judge.provider),
                            provider = judge.provider,
                            history = emptyList(),
                            modelOverride = judge.model,
                            accountId = accountId(),
                            image = null,
                            systemPrompt = LLM_VERIFY_PROMPT,
                            readTimeoutMs = minOf(
                                25_000L,
                                (deadline - System.currentTimeMillis()).coerceAtLeast(5_000L)
                            ).toInt()
                        )
                    }
                    val verdict = CentralBrain.parseVerdict(checkReply)
                    val judgeOk = !LlmClient.isErrorReply(checkReply) && verdict.ok
                    SmartRouter.record(judge, judgeOk, System.currentTimeMillis() - judgeStarted)
                    if (!judgeOk) {
                        val note = verdict.reason.ifBlank { "لم تتأكد صحة الإجابة" }
                        val attempts = listOf(
                            AnswerAttempt(answeredBy.provider, true, "الإجابة الأولى"),
                            AnswerAttempt(judge.provider, false, note)
                        )
                        val fixPrompt = prompt + "\n\n" + CentralBrain.continuationBlock(attempts) +
                            "\n\nملاحظات المدقق:\n" + note +
                            if (verdict.corrected.isNotBlank()) {
                                "\n\nنسخة مصححة مقترحة (راجعها ولا تنسخها حرفياً إن كانت خاطئة):\n" +
                                    verdict.corrected.take(4000)
                            } else {
                                ""
                            }
                        val fixed = withContext(Dispatchers.IO) {
                            LlmClient.ask(
                                message = fixPrompt,
                                apiKey = keyOf(answeredBy.provider),
                                provider = answeredBy.provider,
                                history = requestHistory,
                                modelOverride = answeredBy.model,
                                accountId = accountId(),
                                image = null,
                                systemPrompt = systemPrompt +
                                    if (sources.isNotEmpty()) "\n\n" + LLM_GROUNDED_RULES else "",
                                readTimeoutMs = minOf(
                                    30_000L,
                                    (deadline - System.currentTimeMillis()).coerceAtLeast(5_000L)
                                ).toInt()
                            )
                        }
                        val fixedOk = !LlmClient.isErrorReply(fixed) &&
                            !AnswerGuard.isLowQuality(fixed, kind, prompt) &&
                            !CentralBrain.repeatsQuestion(fixed, mutableSetOf(), session.pendingQuestion)
                        if (fixedOk) {
                            reply = fixed
                            used = answeredBy
                        } else if (verdict.corrected.isNotBlank() &&
                            !verdict.corrected.equals(reply, ignoreCase = true) &&
                            !AnswerGuard.isLowQuality(verdict.corrected, kind, prompt)
                        ) {
                            reply = verdict.corrected
                            used = judge
                            reply += "\n\n🧭 دقّق العقل المركزي الإجابة واعتمد النسخة المصححة."
                        } else {
                            reply += "\n\n🧭 تحقق العقل المركزي: " + note.take(240)
                        }
                    }
                }
            }
            if (used == null && failed.size > 1) {
                reply += "\n\n(جرّبت بالترتيب: " + failed.joinToString("، ") + ")"
            }
            var consensusPercent = -1
            if (central && used != null && kind == TaskKind.FACTUAL &&
                prefs.getBoolean("consensus", false) &&
                image == null && !prompt.contains("محتوى الملف (")
            ) {
                val usedRoute = used
                if (usedRoute != null) {
                    val secondRoute = plan.firstOrNull {
                        it != usedRoute && SmartRouter.isReliableModel(it.provider, it.model)
                    } ?: plan.firstOrNull { it != usedRoute }
                    if (secondRoute != null) {
                        val started = System.currentTimeMillis()
                        val secondReply = withContext(Dispatchers.IO) {
                            LlmClient.ask(
                                message = llmPrompt,
                                apiKey = keyOf(secondRoute.provider),
                                provider = secondRoute.provider,
                                history = history,
                                modelOverride = secondRoute.model,
                                accountId = accountId(),
                                image = null,
                                systemPrompt = systemPrompt,
                                readTimeoutMs = minOf(
                                    30_000L,
                                    (deadline - System.currentTimeMillis()).coerceAtLeast(5_000L)
                                ).toInt()
                            )
                        }
                        val secondError = LlmClient.isErrorReply(secondReply)
                        val secondLow = !secondError &&
                            AnswerGuard.isLowQuality(secondReply, kind, prompt)
                        SmartRouter.record(
                            secondRoute,
                            !secondError && !secondLow,
                            System.currentTimeMillis() - started
                        )
                        if (!secondError && !secondLow && secondReply.isNotBlank()) {
                            val percent = Consensus.compare(reply, secondReply)
                            val synthesisMessage = buildString {
                                append("السؤال:\n").append(prompt)
                                if (sources.isNotEmpty()) {
                                    append("\n\n").append(WebGrounding.contextBlock(sources))
                                }
                                append("\n\nالإجابة أ:\n").append(reply)
                                append("\n\nالإجابة ب:\n").append(secondReply)
                                append("\n\nأعد الإجابة النهائية الموحدة.")
                            }
                            val synthesized = withContext(Dispatchers.IO) {
                                LlmClient.ask(
                                    message = synthesisMessage,
                                    apiKey = keyOf(usedRoute.provider),
                                    provider = usedRoute.provider,
                                    history = emptyList(),
                                    modelOverride = usedRoute.model,
                                    accountId = accountId(),
                                    image = null,
                                    systemPrompt = LLM_CONSENSUS_PROMPT + answerLengthSuffix() +
                                        if (sources.isNotEmpty()) "\n\n" + LLM_GROUNDED_RULES else "",
                                    readTimeoutMs = minOf(
                                        30_000L,
                                        (deadline - System.currentTimeMillis()).coerceAtLeast(5_000L)
                                    ).toInt()
                                )
                            }
                            consensusPercent = percent
                            if (!LlmClient.isErrorReply(synthesized) &&
                                !AnswerGuard.isLowQuality(synthesized, kind, prompt)
                            ) {
                                reply = synthesized
                            } else {
                                reply += "\n\n🧩 " + Consensus.label(percent) +
                                    " (" + percent + "%) بين إجابتين؛ عُرضت الإجابة الأقوى."
                            }
                        }
                    }
                }
            }
            val via = used?.let {
                SmartRouter.label(it, if (auto) kind else null) +
                    (if (sources.isNotEmpty()) " · 🌐 مصادر ويب" else "") +
                    (if (toolNames.isNotEmpty()) " · 🧰 " + toolNames.distinct().take(4).joinToString("، ") else "") +
                    (if (agentAsked) " · 💬 بانتظار ردك" else "") +
                    (if (consensusPercent >= 0) " · 🧩 " + consensusPercent + "%" else "")
            }
            toolStatus.remove(chatId)
            // الجلسة تبقى فقط إذا لم تُقبل إجابة مكتملة أو كان هناك سؤال معلّق؛ اكتمال المهمة يصفّرها.
            if (used != null && agentComplete && session.pendingQuestion == null) {
                AgentSessions.clear(prefs, chatId)
            } else if (!session.hasWork()) {
                AgentSessions.clear(prefs, chatId)
            }
            val newMessage = Message(
                reply,
                false,
                ytUrl,
                ytmUrl,
                anghamiUrl,
                browserUrl,
                via = via,
                sources = if (used != null) sources else emptyList()
            )
            val replaced = replaceIndex != null && replaceInChat(chatId, replaceIndex, newMessage)
            if (!replaced) appendToChat(chatId, newMessage)
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

        // تحية أو «هل أنت هنا؟» لا تستحق انتظار مزود؛ نرد فوراً من دون شبكة.
        if (file == null && image == null) {
            QuickReply.forText(text)?.let { quick ->
                val reply = replyingTo
                replyingTo = null
                messages = messages + Message(text, true, replyTo = reply?.text) + Message(quick, false, via = "⚡ رد فوري")
                input = ""
                saveCurrent()
                return
            }
        }

        val kind = SmartRouter.classify(text, image != null, file?.text?.length ?: 0, file?.name)
        // عزل وقت التشغيل: لا تنفيذ برمجي صامت في وضع عام/تعلّم. نطلب ترقية صريحة أولاً.
        if (currentMode != ChatMode.CODE && file == null && image == null &&
            !prefs.getBoolean("tools_everywhere", true) &&
            ModeGuard.looksLikeCodingRequest(text, kind)
        ) {
            promotionRequest = text
            return
        }
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
        val fileText = file?.text.orEmpty()
        val selectedFileText = if (file != null && fileText.length > 18_000) {
            LocalRag.select(fileText, text.ifBlank { file.name })
        } else {
            fileText
        }
        var prompt = if (file != null) {
            buildString {
                if (text.isNotEmpty()) append(text).append("\n\n")
                append("محتوى الملف (").append(file.name).append("):\n").append(selectedFileText)
                if (selectedFileText.length < fileText.length) {
                    append("\n\n[ملاحظة: الملف طويل، وأُرسلت المقاطع الأكثر صلة بالسؤال فقط.]")
                }
            }
        } else {
            text
        }
        if (prompt.isBlank() && image != null) {
            prompt = "اشرح محتوى هذه الصورة بالتفصيل."
        }

        // نستبعد رسائل الخطأ حتى لا تربك النموذج التالي، ونرسل آخر 12000 حرف فقط للسرعة
        // مع الحفاظ على بداية الحوار الصحيحة (الرسالة الأولى من المستخدم).
        val history = windowedHistory(messages)

        val chatId = currentChat
        val reply = replyingTo
        replyingTo = null
        messages = messages + Message(displayText, true, replyTo = reply?.text)
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

    /** زر «إعادة» في الفقاعة: يجرّب أقوى المزودات ويستبدل الرد القديم في مكانه. */
    fun regenerate(index: Int) {
        if (loadingChats[currentChat] == true) return
        val chatId = currentChat
        val old = messages.getOrNull(index) ?: return
        if (old.isUser) return
        val userIndex = (index - 1 downTo 0).firstOrNull { messages[it].isUser } ?: return
        val userText = messages[userIndex].text.trim()
        if (userText.isBlank() || userText.contains("📎") || userText.contains("🖼️")) {
            appendToChat(
                chatId,
                Message("⚠️ لا يمكن إعادة هذه الإجابة تلقائياً لأنها تعتمد على مرفق. أعد إرسال المرفق مع طلبك.", false)
            )
            return
        }
        val history = windowedHistory(messages.take(userIndex))
        val kind = SmartRouter.classify(userText, false, 0, null)
        runChat(
            chatId = chatId,
            prompt = userText,
            history = history,
            image = null,
            ytUrl = old.ytUrl,
            ytmUrl = old.ytmUrl,
            anghamiUrl = old.anghamiUrl,
            browserUrl = old.browserUrl,
            kind = kind,
            replaceIndex = index,
            forcePrecise = true
        )
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

    if (showConnectors) {
        ConnectorsScreen(
            prefs = prefs,
            onBack = { showConnectors = false },
            onOpenSettings = {
                showConnectors = false
                showSettings = true
            }
        )
        return
    }

    if (showGitHub) {
        GitHubScreen(
            prefs = prefs,
            onBack = { showGitHub = false },
            onOpenSettings = {
                showGitHub = false
                showSettings = true
            }
        )
        return
    }

    if (showDevTools) {
        DevToolsScreen(
            prefs = prefs,
            onBack = { showDevTools = false },
            onOpenGitHub = {
                showDevTools = false
                showGitHub = true
            },
            onOpenSettings = {
                showDevTools = false
                showSettings = true
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
            },
            onOpenGitHub = {
                showSettings = false
                showGitHub = true
            },
            onOpenDevTools = {
                showSettings = false
                showDevTools = true
            },
            onOpenConnectors = {
                showSettings = false
                showConnectors = true
            }
        )
        return
    }

    
    if (showContacts) {
        ContactsScreen(
            contacts = contacts,
            onBack = { showContacts = false },
            onRequestPermission = { openContacts() },
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
        ModelPickerSheet(
            sheetState = modelSheetState,
            autoRoute = autoRoute,
            selectedProvider = selectedProvider,
            modelFor = { info ->
                (prefs.getString("model_${info.id}", "") ?: "").ifBlank { info.defaultModel }
            },
            onDismiss = { showModelSheet = false },
            onChooseAuto = {
                prefs.edit().putBoolean("auto_route", true).apply()
                autoRoute = true
                showModelSheet = false
                syncActiveTab()
            },
            onChooseProvider = { info ->
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
            onOpenSettings = {
                showModelSheet = false
                showSettings = true
            }
        )
    }

    if (showWebSheet) {
        WebPickerSheet(
            sheetState = webSheetState,
            onDismiss = { showWebSheet = false },
            onOpen = { url ->
                context.startActivity(Intent(context, ArenaActivity::class.java).putExtra("ARENA_URL", url))
            }
        )
    }

    if (showMediaSheet) {
        MediaPickerSheet(
            sheetState = mediaSheetState,
            onDismiss = { showMediaSheet = false },
            onOpen = { url ->
                context.startActivity(Intent(context, ArenaActivity::class.java).putExtra("ARENA_URL", url))
            }
        )
    }

    LaunchedEffect(messages.size, loadingChats[currentChat]) {
        val count = messages.size + if (loadingChats[currentChat] == true) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    if (showModePicker) {
        ModePickerDialog(
            current = currentMode,
            onDismiss = { showModePicker = false },
            onSelect = { selected ->
                showModePicker = false
                if (selected != currentMode) {
                    if (messages.isEmpty()) {
                        ChatRepository.setMode(prefs, currentChat, selected)
                        currentMode = selected
                        refreshChats()
                    } else {
                        // منع تلوث السياق: وضع جديد = محادثة جديدة معزولة.
                        startNewChat(selected)
                        refreshChats()
                    }
                }
            }
        )
    }

    val promotion = promotionRequest
    if (promotion != null) {
        PromotionDialog(
            mode = currentMode,
            onDismiss = { promotionRequest = null },
            onPromote = {
                if (promotion.isNotBlank()) {
                    val savedAttachment = attachment
                    val savedImage = imageAttachment
                    startNewChat(ChatMode.CODE)
                    input = promotion
                    attachment = savedAttachment
                    imageAttachment = savedImage
                    promotionRequest = null
                    autoSendTrigger += 1
                }
            }
        )
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
                LionDrawerContent(
                    activeChats = activeChats,
                    archivedChats = archivedChats,
                    currentChat = currentChat,
                    contactsCount = contacts.size,
                    scheduledCount = scheduledItems.size,
                    showArchive = showArchive,
                    onToggleArchive = { showArchive = !showArchive },
                    onNewChat = {
                        startNewChat()
                        scope.launch { drawerState.close() }
                    },
                    onContacts = {
                        openContacts()
                        scope.launch { drawerState.close() }
                    },
                    onScheduled = {
                        scheduledItems = Scheduler.list(prefs)
                        showScheduled = true
                        scope.launch { drawerState.close() }
                    },
                    onImageEditor = {
                        editAfterPick = true
                        imageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                        scope.launch { drawerState.close() }
                    },
                    onGitHub = {
                        showGitHub = true
                        scope.launch { drawerState.close() }
                    },
                    onDevTools = {
                        showDevTools = true
                        scope.launch { drawerState.close() }
                    },
                    onConnectors = {
                        showConnectors = true
                        scope.launch { drawerState.close() }
                    },
                    onOpenChat = { id ->
                        openChat(id)
                        scope.launch { drawerState.close() }
                    },
                    onRename = { meta ->
                        renameText = meta.title
                        renameTarget = meta
                    },
                    onAutoTitle = { id -> generateTitle(id, true) },
                    onArchive = { meta ->
                        ChatRepository.setArchived(prefs, meta.id, true)
                        if (currentChat == meta.id) startNewChat()
                        refreshChats()
                    },
                    onUnarchive = { meta ->
                        ChatRepository.setArchived(prefs, meta.id, false)
                        refreshChats()
                    },
                    onDelete = { meta -> deleteTarget = meta },
                    onShare = { meta -> shareChat(meta) }
                )
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ChatModeBadge(currentMode) { showModePicker = true }
                                    Spacer(Modifier.width(5.dp))
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
                        IconButton(onClick = {
                            val newValue = !autoSpeak
                            autoSpeak = newValue
                            prefs.edit().putBoolean("auto_speak", newValue).apply()
                            if (!newValue) tts?.stop()
                        }) {
                            Icon(
                                imageVector = if (autoSpeak) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                contentDescription = if (autoSpeak) "إيقاف القراءة التلقائية" else "تفعيل القراءة التلقائية",
                                tint = if (autoSpeak) Color(0xFFFFB800) else Color(0xFF555555)
                            )
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
                LionMessageList(
                    messages = messages,
                    currentChat = currentChat,
                    loading = loadingChats[currentChat] == true,
                    progress = toolStatus[currentChat],
                    listState = listState,
                    context = context,
                    tts = tts,
                    onPendingChoice = { i, p, r, c -> onPendingChoice(i, p, r, c) },
                    onRegenerate = { i -> regenerate(i) },
                    onReply = { i ->
                        messages.getOrNull(i)?.let { target ->
                            replyingTo = target
                            val quoted = target.text.take(700).replace("\n", " ")
                            input = "↩ رد على ردك السابق:\n> $quoted\n\n"
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp)
                )

                LionInputBar(
                    attachment = attachment,
                    imageAttachment = imageAttachment,
                    input = input,
                    onInputChange = { input = it },
                    autoRoute = autoRoute,
                    providerLabel = providerInfo(selectedProvider).shortName,
                    sending = loadingChats[currentChat] == true,
                    canSend = input.isNotBlank() || attachment != null || imageAttachment != null,
                    onEditImage = { img ->
                        editorImage = img
                        showEditor = true
                    },
                    onClearAttachment = {
                        attachment = null
                        imageAttachment = null
                    },
                    onQuickPrompt = { input = it },
                    onAttachFile = { fileLauncher.launch("*/*") },
                    onAttachImage = {
                        imageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onVoice = { startVoice() },
                    onChooseModel = { showModelSheet = true },
                    onOpenWeb = { showWebSheet = true },
                    onOpenMedia = { showMediaSheet = true },
                    onSend = { sendMessage() }
                )
            }
        }
    }
}
