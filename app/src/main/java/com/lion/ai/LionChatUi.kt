package com.lion.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun TypingDots() {
    // نقاط انتظار فقط: لا نص «تهيئة مسار التنفيذ» لأن أغلب الردود مباشرة ولا تُشغّل أي تنفيذ.
    val transition = rememberInfiniteTransition(label = "typingDots")
    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 600, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i"
            )
            Canvas(Modifier.size(8.dp)) {
                drawCircle(Color(0xFF83B8C6).copy(alpha = alpha))
            }
        }
    }
}

@Composable
private fun WorkLog(lines: List<String>, steps: List<AgentStep>) {
    val items = buildWorkVisuals(lines, steps)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF15191B),
        modifier = Modifier.padding(8.dp).fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("مسار التنفيذ", color = Color(0xFFD6E0E3), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("الحالة محفوظة وتنتقل مع الاستئناف والمزود البديل", color = Color(0xFF718086), fontSize = 10.sp)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = Color(0xFF83B8C6), trackColor = Color(0xFF20282B))
            Spacer(Modifier.height(8.dp))
            items.forEachIndexed { index, item -> WorkRow(item, index == items.lastIndex) }
        }
    }
}

@Composable
private fun WorkRow(item: WorkVisual, last: Boolean) {
    val color = when (item.state) {
        WorkVisualState.RUNNING -> Color(0xFF83B8C6)
        WorkVisualState.SUCCESS -> Color(0xFF78A992)
        WorkVisualState.ERROR -> Color(0xFFC77E82)
        WorkVisualState.PERMISSION -> Color(0xFFC3A66B)
        WorkVisualState.PROVIDER -> Color(0xFF9B91C3)
    }
    val transition = rememberInfiniteTransition(label = "work-node")
    val pulse by transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900)),
        label = "work-pulse"
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Canvas(Modifier.width(24.dp).height(if (last) 30.dp else 48.dp)) {
            val x = size.width / 2
            val y = 10.dp.toPx()
            if (!last) drawLine(Color(0xFF344146), Offset(x, y + 8.dp.toPx()), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            if (item.state == WorkVisualState.RUNNING) {
                drawCircle(color.copy(alpha = pulse), radius = 7.dp.toPx(), style = Stroke(1.2.dp.toPx()))
            }
            drawCircle(color, radius = 4.dp.toPx(), style = if (item.state == WorkVisualState.SUCCESS) Stroke(1.4.dp.toPx()) else Fill)
            if (item.state == WorkVisualState.SUCCESS) drawCircle(color, radius = 1.8.dp.toPx())
            if (item.state == WorkVisualState.ERROR) {
                drawLine(color, Offset(x - 2.dp.toPx(), y - 2.dp.toPx()), Offset(x + 2.dp.toPx(), y + 2.dp.toPx()), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
                drawLine(color, Offset(x + 2.dp.toPx(), y - 2.dp.toPx()), Offset(x - 2.dp.toPx(), y + 2.dp.toPx()), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 8.dp)) {
            Text(item.title, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (item.detail.isNotBlank()) Text(item.detail, color = Color(0xFF89969B), fontSize = 10.sp, lineHeight = 14.sp)
        }
        Text(
            when (item.state) {
                WorkVisualState.RUNNING -> "جارٍ"
                WorkVisualState.SUCCESS -> "تم"
                WorkVisualState.ERROR -> "فشل"
                WorkVisualState.PERMISSION -> "إذن"
                WorkVisualState.PROVIDER -> "تحويل"
            },
            color = color.copy(alpha = 0.8f), fontSize = 9.sp
        )
    }
}

@Composable
internal fun LionMessageList(
    messages: List<Message>,
    currentChat: String,
    loading: Boolean,
    progress: String? = null,
    /** نص الرد وهو يتكوّن داخل صفحة الويب (بث حي). */
    livePartial: String? = null,
    sessionSteps: List<AgentStep> = emptyList(),
    listState: LazyListState,
    context: Context,
    tts: TextToSpeech?,
    onPendingChoice: (Int, PendingAction, Recipient?, String) -> Unit,
    onRegenerate: (Int) -> Unit,
    onReply: (Int) -> Unit = {},
    onAskReply: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyColumn(state = listState, modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(messages, key = { index, _ -> "$currentChat:$index" }) { index, message ->
            val previousUser = (index - 1 downTo 0).firstOrNull { messages[it].isUser }?.let { messages[it] }
            val canRegenerate = !message.isUser && previousUser != null && index > 0 &&
                messages[index - 1].isUser && message.pending == null &&
                messages.drop(index + 1).none { it.isUser } && !ActionDetector.looksLikeCommand(previousUser.text)
            // بروتوكول الأدوات الخام لا يُعرض: يظهر مكانه مسار التنفيذ.
            val shown = if (!message.isUser && ToolMarkup.hasMarkup(message.text))
                message.copy(text = ToolMarkup.strip(message.text).ifBlank { "⏳ جارٍ تنفيذ الأدوات…" }) else message
            Bubble(
                shown, context, tts, index, onPendingChoice, canRegenerate,
                { onRegenerate(index) }, { onReply(index) },
                askEnabled = !loading && index == messages.lastIndex,
                onAskReply = onAskReply
            )
        }
        if (loading) {
            val live = ToolMarkup.strip(livePartial.orEmpty())
            if (live.isNotBlank()) {
                item(key = "$currentChat:live") {
                    Bubble(
                        Message(live, false, via = "🔴 مباشر من صفحة الموقع"),
                        context, tts, messages.size, onPendingChoice, false, {}, {},
                        askEnabled = false, onAskReply = onAskReply
                    )
                }
            }
            item(key = "$currentChat:progress") {
                val lines = (progress ?: "").split("\n").filter { it.isNotBlank() }
                if (lines.isEmpty() && sessionSteps.isEmpty()) {
                    if (live.isBlank()) TypingDots()
                } else WorkLog(lines, sessionSteps)
            }
        }
    }
}
