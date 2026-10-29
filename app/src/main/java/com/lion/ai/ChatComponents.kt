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
    onRequestPermission: () -> Unit,
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("لا توجد جهات اتصال.", color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRequestPermission) {
                        Text("منح صلاحية جهات الاتصال", color = Color(0xFFFFB800))
                    }
                }
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
    onPendingChoice: (Int, PendingAction, Recipient?, String) -> Unit,
    canRegenerate: Boolean = false,
    onRegenerate: () -> Unit = {},
    onReply: () -> Unit = {}
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
                val displayText = if (!message.isUser) AnswerGuard.sanitizeForDisplay(cleanText) else cleanText
                
                if (displayText.isNotEmpty()) {
                    Text(
                        displayText,
                        color = if (message.isUser) Color(0xFF0D0D0D) else Color.White,
                        fontSize = 15.sp
                    )
                }

                if (message.replyTo?.isNotBlank() == true) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "↩ رد على رد سابق",
                        color = Color(0xFF64B5F6),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (message.isUser && message.text.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("سؤال المستخدم", message.text))
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("📋 نسخ السؤال", color = Color(0xFF0D0D0D), fontSize = 10.sp)
                        }
                    }
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
                if (!message.isUser && message.sources.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("🌐 المصادر", color = Color(0xFFFFB800), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    message.sources.forEachIndexed { sourceIndex, source ->
                        val host = runCatching { Uri.parse(source.url).host }.getOrNull()
                            ?.removePrefix("www.") ?: ""
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
                                    }
                                }
                                .padding(vertical = 3.dp)
                        ) {
                            Text(
                                "${sourceIndex + 1}. ${source.title}",
                                color = Color(0xFF64B5F6),
                                fontSize = 11.sp,
                                maxLines = 2
                            )
                            Text(
                                if (host.isBlank()) source.url else host,
                                color = Color(0xFF7A7A7A),
                                fontSize = 9.sp,
                                maxLines = 1
                            )
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
                        if (canRegenerate) {
                            TextButton(onClick = onRegenerate) {
                                Text("🔄 إعادة", color = Color(0xFFFFB800), fontSize = 12.sp)
                            }
                        }
                        TextButton(onClick = onReply) {
                            Text("↩ رد على هذا الرد", color = Color(0xFFFFB800), fontSize = 12.sp)
                        }
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
        ChatModeBadge(ChatMode.fromId(meta.mode))
        Spacer(Modifier.width(4.dp))
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

