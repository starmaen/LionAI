package com.lion.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

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
