package com.lion.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChatModeBadge(
    mode: ChatMode,
    onClick: (() -> Unit)? = null
) {
    Surface(
        color = Color(mode.color).copy(alpha = 0.16f),
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(mode.icon, fontSize = 10.sp)
            Spacer(Modifier.width(3.dp))
            Text(mode.short, color = Color(mode.color), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ModePickerDialog(
    current: ChatMode,
    onDismiss: () -> Unit,
    onSelect: (ChatMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101010),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        title = {
            Text(
                "وضع المحادثة",
                color = Color(0xFFFFB800),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    "يتبدّل الوضع داخل المحادثة الحالية نفسها دون فتح صفحة جديدة، ويبقى سياق الحوار محفوظاً. الأدوات والتعليمات تتبع الوضع المختار ابتداءً من الرسالة التالية.",
                    color = Color(0xFFB8B8B8),
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                ChatMode.entries.forEach { mode ->
                    val selected = mode == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) Color(0xFFFFB800).copy(alpha = 0.12f)
                                else Color(0xFF1B1B1B)
                            )
                            .border(
                                1.dp,
                                if (selected) Color(0xFFFFB800).copy(alpha = 0.60f)
                                else Color(0xFF2A2A2A),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelect(mode) }
                            .padding(vertical = 10.dp, horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(mode.icon, fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                mode.label + if (selected) "  • الحالي" else "",
                                color = if (selected) Color(0xFFFFB800) else Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                mode.hint,
                                color = Color(0xFF9A9A9A),
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun PromotionDialog(
    mode: ChatMode,
    onDismiss: () -> Unit,
    onPromote: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = { Text("🛠️ ترقية إلى وضع البرمجة", color = Color(0xFF2196F3)) },
        text = {
            Text(
                ModeGuard.promotionOffer(mode) +
                    "\n\nسيتحوّل هذا الحوار نفسه إلى وضع المطوّر مع الاحتفاظ بسياقه، ثم يُنفَّذ طلبك فوراً دون فتح محادثة جديدة.",
                color = Color(0xFFE0E0E0),
                fontSize = 13.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onPromote) {
                Text("ترقية هذه المحادثة إلى وضع البرمجة", color = Color(0xFF2196F3))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ليس الآن", color = Color.White) }
        }
    )
}
