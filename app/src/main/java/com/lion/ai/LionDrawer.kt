package com.lion.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun LionDrawerContent(
    activeChats: List<ChatMeta>,
    archivedChats: List<ChatMeta>,
    currentChat: String,
    contactsCount: Int,
    scheduledCount: Int,
    showArchive: Boolean,
    onToggleArchive: () -> Unit,
    onNewChat: () -> Unit,
    onContacts: () -> Unit,
    onScheduled: () -> Unit,
    onImageEditor: () -> Unit,
    onOpenChat: (String) -> Unit,
    onRename: (ChatMeta) -> Unit,
    onAutoTitle: (String) -> Unit,
    onArchive: (ChatMeta) -> Unit,
    onUnarchive: (ChatMeta) -> Unit,
    onDelete: (ChatMeta) -> Unit,
    onShare: (ChatMeta) -> Unit
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
            onClick = onNewChat,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
        ) {
            Text("+ محادثة جديدة", color = Color(0xFF0D0D0D))
        }
        Button(
            onClick = onContacts,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F))
        ) {
            Text("📇 جهات الاتصال (${contactsCount})", color = Color.White)
        }
        Button(
            onClick = onScheduled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E4A2E))
        ) {
            Text("⏰ المجدولات (${scheduledCount})", color = Color.White)
        }
        Button(
            onClick = onImageEditor,
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
                    onOpen = { onOpenChat(meta.id) },
                    onRename = { onRename(meta) },
                    onAutoTitle = { onAutoTitle(meta.id) },
                    onArchiveToggle = { onArchive(meta) },
                    onDelete = { onDelete(meta) },
                    onShare = { onShare(meta) }
                )
            }
            if (archivedChats.isNotEmpty()) {
                item(key = "archive_header") {
                    TextButton(
                        onClick = onToggleArchive,
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
                            onOpen = { onOpenChat(meta.id) },
                            onRename = { onRename(meta) },
                            onAutoTitle = { onAutoTitle(meta.id) },
                            onArchiveToggle = { onUnarchive(meta) },
                            onDelete = { onDelete(meta) },
                            onShare = { onShare(meta) }
                        )
                    }
                }
            }
        }
    }
}
