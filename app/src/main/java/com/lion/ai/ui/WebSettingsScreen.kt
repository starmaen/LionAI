package com.lion.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lion.ai.core.WebProviderAdapter
import com.lion.ai.core.WebProvidersPrefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { WebProvidersPrefs(context) }
    val adapter = remember { WebProviderAdapter() }
    
    var webEnabled by remember { mutableStateOf(prefs.isWebEnabled()) }
    var mode by remember { mutableStateOf(prefs.getMode()) }
    var testResult by remember { mutableStateOf("") }
    
    val providerStates = remember {
        mutableStateMapOf<String, Boolean>().apply {
            adapter.webProviders.forEach { put(it.id, prefs.isProviderEnabled(it.id)) }
        }
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
            
            item {
                Card {
                    Row(Modifier.fillMaxWidth().padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column {
                            Text("Enable Lion Chat Brain", fontWeight = FontWeight.Bold)
                            Text("Link web providers to central brain", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = webEnabled, 
                            onCheckedChange = { webEnabled = it; prefs.setWebEnabled(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF9800), checkedTrackColor = Color(0xFFFFCC80))
                        )
                    }
                }
            }
            
            item {
                Text("Mode:", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WebProvidersPrefs.WebMode.values().forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { mode = m; prefs.setMode(m) },
                            label = { 
                                Text(when(m) {
                                    WebProvidersPrefs.WebMode.AUTO -> "🔄 AUTO"
                                    WebProvidersPrefs.WebMode.DIRECT -> "🎯 DIRECT"
                                    WebProvidersPrefs.WebMode.INDIVIDUAL -> "👤 SINGLE"
                                }, fontSize = 11.sp)
                            },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFF9800), selectedLabelColor = Color.White)
                        )
                    }
                }
            }
            
            item { Divider() }
            item { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🦁", fontSize = 18.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("Lion Chat Web Providers (10):", fontWeight = FontWeight.Bold, color = Color(0xFFFF9800)) 
                }
            }
            
            items(adapter.webProviders) { provider ->
                val enabled = providerStates[provider.id] ?: true
                Card(colors = CardDefaults.cardColors(containerColor = if(enabled) Color(0xFFFFF8E1) else Color(0xFFF5F5F5))) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier.size(32.dp).clip(CircleShape).background(if(enabled) Color(0xFFFF9800) else Color.Gray),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(provider.icon, fontSize = 16.sp, color = Color.White)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Lion Chat - ${provider.displayName}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(provider.url, style = MaterialTheme.typography.bodySmall, fontSize = 10.sp)
                                Text("Display: 🦁 Lion Chat", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800), fontSize = 9.sp)
                            }
                        }
                        Switch(
                            checked = enabled, 
                            onCheckedChange = { providerStates[provider.id] = it; prefs.setProviderEnabled(provider.id, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF9800))
                        )
                    }
                }
            }
            
            item {
                Button(
                    onClick = {
                        val enabled = adapter.webProviders.filter { providerStates[it.id] == true }
                        testResult = "🦁 Lion Chat Active!\n✅ Enabled: ${enabled.size}/${adapter.webProviders.size}\n🔄 Mode: $mode\n🧠 Brain sees: ${enabled.size + 18} providers\n🏷️ All answers as: 🦁 Lion Chat\n📋 Active: ${enabled.joinToString { it.displayName }}"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) { Text("🧪 Test Lion Chat Connection") }
            }
            
            if(testResult.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                        Text(testResult, Modifier.padding(16.dp), color = Color(0xFF4E342E))
                    }
                }
            }
        }
    }
}
