package com.lion.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun TypingDots() {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(320)
            tick = (tick + 1) % 4
        }
    }
    val frames = listOf("●  ○  ○", "○  ●  ○", "○  ○  ●", "○  ●  ○")
    Text(frames[tick], color = Color(0xFFFFB800), fontSize = 14.sp, modifier = Modifier.padding(8.dp))
}

/** سجل عمل حي: الخطوات المنجزة (✅/❌) ثم الخطوة الجارية بنقاط متحركة. */
@Composable
private fun WorkLog(lines: List<String>) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            tick = (tick + 1) % 3
        }
    }
    val dots = listOf("·", "··", "···")[tick]
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E1E1E),
        modifier = Modifier.padding(8.dp).fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            lines.forEachIndexed { i, line ->
                val last = i == lines.lastIndex
                val done = line.startsWith("✅") || line.startsWith("❌")
                Text(
                    if (last && !done) "$line $dots" else line,
                    color = if (last) Color(0xFFFFB800) else Color(0xFFB0B0B0),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
internal fun LionMessageList(
    messages: List<Message>,
    currentChat: String,
    loading: Boolean,
    progress: String? = null,
    listState: LazyListState,
    context: Context,
    tts: TextToSpeech?,
    onPendingChoice: (Int, PendingAction, Recipient?, String) -> Unit,
    onRegenerate: (Int) -> Unit,
    onReply: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
                LazyColumn(
                    state = listState,
                    modifier = modifier,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(messages, key = { index, _ -> "$currentChat:$index" }) { index, message ->
                        val previousUser = (index - 1 downTo 0)
                            .firstOrNull { messages[it].isUser }
                            ?.let { messages[it] }
                        val canRegenerate = !message.isUser &&
                            previousUser != null &&
                            index > 0 &&
                            messages[index - 1].isUser &&
                            message.pending == null &&
                            messages.drop(index + 1).none { it.isUser } &&
                            !ActionDetector.looksLikeCommand(previousUser.text)
                        Bubble(
                            message, context, tts, index,
                            onPendingChoice = onPendingChoice,
                            canRegenerate = canRegenerate,
                            onRegenerate = { onRegenerate(index) },
                            onReply = { onReply(index) }
                        )
                    }
                    if (loading) {
                        item {
                            val lines = (progress ?: "").split("\n").filter { it.isNotBlank() }
                            if (lines.isEmpty()) TypingDots() else WorkLog(lines)
                        }
                    }
                }
}

@Composable
internal fun LionInputBar(
    attachment: Attachment?,
    imageAttachment: ImageAttachment?,
    input: String,
    onInputChange: (String) -> Unit,
    providerLabel: String,
    providerNote: String,
    modeLabel: String,
    sending: Boolean,
    canSend: Boolean,
    onEditImage: (ImageAttachment) -> Unit,
    onClearAttachment: () -> Unit,
    onQuickPrompt: (String) -> Unit,
    onAttachFile: () -> Unit,
    onAttachImage: () -> Unit,
    onVoice: () -> Unit,
    onChooseModel: () -> Unit,
    onOpenWeb: () -> Unit,
    onOpenMedia: () -> Unit,
    onSend: () -> Unit
) {
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
                            TextButton(onClick = { onEditImage(image) }) {
                                Text("✏️ تعديل", color = Color(0xFFFFB800), fontSize = 12.sp)
                            }
                        }
                        IconButton(onClick = onClearAttachment) {
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
                                onClick = { onQuickPrompt(prompt) },
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
                            onValueChange = onInputChange,
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
                        // اسم المزود/الوضع في سطر مستقل أسفل صندوق الكتابة،
                        // حتى لا يزاحم زر الإرسال ولا يغطي المؤشر.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val providerLine = providerLabel +
                                (if (providerNote.isNotBlank()) " · $providerNote" else "") +
                                (if (modeLabel.isNotBlank()) " · $modeLabel" else "")
                            TextButton(
                                onClick = onChooseModel,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    providerLine,
                                    color = Color(0xFFFFB800),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Filled.ArrowDropDown,
                                    "اختيار الموديل",
                                    tint = Color(0xFFFFB800),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            InputAction(Icons.Filled.AttachFile, "ملف") {
                                onAttachFile()
                            }
                            InputAction(Icons.Filled.Image, "صورة") {
                                onAttachImage()
                            }
                            InputAction(Icons.Filled.Mic, "صوت") { onVoice() }
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = onOpenWeb,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("🌐", fontSize = 16.sp)
                            }
                            IconButton(
                                onClick = onOpenMedia,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("🎬", fontSize = 16.sp)
                            }
                            Spacer(Modifier.width(2.dp))
                            FilledIconButton(
                                onClick = onSend,
                                enabled = !sending && canSend,
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
