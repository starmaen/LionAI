package com.lion.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lion.ai.LionChatSettingsCard
import com.lion.ai.SecurePrefs

@Composable
fun WebSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember(context) { SecurePrefs.get(context) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        LionChatSettingsCard(prefs)
    }
}
