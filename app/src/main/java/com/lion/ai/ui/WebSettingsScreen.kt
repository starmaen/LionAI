package com.lion.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lion.ai.core.WebProvider
import com.lion.ai.core.WebProviderAdapter
import com.lion.ai.core.WebProvidersPrefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { WebProvidersPrefs(context) }
    val adapter = remember { WebProviderAdapter() }

    var webEnabled by remember { mutableStateOf<Boolean>(prefs.isWebEnabled()) }
    var mode by remember { mutableStateOf<String>(prefs.getMode()) }
    var testResult by remember { mutableStateOf<String>("") }

    val providerStates: SnapshotStateMap<String, Boolean> = remember {
        val map = mutableStateMapOf<String, Boolean>()
        adapter.webProviders.forEach { provider: WebProvider ->
            map[provider.id] = prefs.isProviderEnabled(provider.id)
        }
        map
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF9800)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🦁", fontSize = 20.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Lion Chat", fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF9800)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🦁", fontSize = 28.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Lion Chat", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFFE65100))
                            Text("Web Providers Hub", fontSize = 12.sp, color = Color(0xFF8D6E63))
                            Text(prefs.getStatus(adapter.webProviders), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            items(adapter.webProviders) { provider: WebProvider ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(provider.displayName)
                    Switch(
                        checked = providerStates[provider.id] ?: true,
                        onCheckedChange = { enabled ->
                            providerStates[provider.id] = enabled
                            prefs.setProviderEnabled(provider.id, enabled)
                        }
                    )
                }
            }
        }
    }
}
