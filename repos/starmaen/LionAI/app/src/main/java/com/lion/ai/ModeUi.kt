package com.lion.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
        containerColor = Color(0xFF1A1A1A),
        title = { Text("🎛️ وضع المحادثة", color = Color(0xFFFFB800)) },
        text = {
            Column {
                Text(
                    "كل وضع له سياق وتعليمات وأدوات معزولة. تغيير وضع محادثة غير فارغة ينشئ محادثة جديدة حتى لا يتلوث السياق.",
                    color = Color(0xFFB0B0B0),
                    fontSize = 11.sp
                )
                Spacer(Modifier.padding(4.dp))
                ChatMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(vertical = 10.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(mode.icon, fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                mode.label + if (mode == current) "  • الحالي" else "",
                                color = if (mode == current) Color(mode.color) else Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(mode.hint, color = Color(0xFF9A9A9A), fontSize = 10.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق", color = Color.White) }
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
                    "\n\nستُنشأ محادثة برمجية جديدة معزولة، وتبقى هذه المحادثة كما هي دون حقن تاريخها في سياق الكود.",
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
