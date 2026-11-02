package com.lion.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lion.ai.core.WebProvider
import com.lion.ai.core.WebProviderAdapter
import com.lion.ai.core.WebProvidersPrefs

@Composable
fun LionChatSettingsCard() {
    val context = LocalContext.current
    val allTabs: List<WebProvider> = remember { WebProviderAdapter.providers }
    var lionEnabled by remember { mutableStateOf(WebProvidersPrefs.isLionEnabled(context)) }
    var centralEnabled by remember { mutableStateOf(WebProvidersPrefs.isCentralEnabled(context)) }
    var toolsEnabled by remember { mutableStateOf(WebProvidersPrefs.isToolsEnabled(context)) }
    var githubEnabled by remember { mutableStateOf(WebProvidersPrefs.isGitHubEnabled(context)) }
    var emailEnabled by remember { mutableStateOf(WebProvidersPrefs.isEmailEnabled(context)) }
    var filesEnabled by remember { mutableStateOf(WebProvidersPrefs.isFilesEnabled(context)) }
    var tabsState by remember { 
        mutableStateOf(allTabs.associate { it.id to WebProvidersPrefs.isProviderEnabled(context, it.id) }.toMutableMap()) 
    }
    val orange = Color(0xFFFF6D00)
    val darkOrange = Color(0xFFBF360C)
    
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A0A)), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🦁 Lion Chat", color = orange, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Surface(color = orange, shape = RoundedCornerShape(8.dp)) { 
                    Text(" العقل البرتقالي ", color = Color.Black, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) 
                }
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = lionEnabled, 
                    onCheckedChange = { 
                        lionEnabled = it
                        WebProvidersPrefs.setLionEnabled(context, it)
                    }, 
                    colors = SwitchDefaults.colors(checkedThumbColor = orange, checkedTrackColor = darkOrange)
                )
            }
            Text("يظهر في الشات بزر برتقالي، مربوط بالعقل المركزي + أدوات المطور", color = Color(0xFFB0B0B0), fontSize = 12.sp)
            if (lionEnabled) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF3A2E1F))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🔗 مربوط بالعقل المركزي (تلقائي)", color = Color.White, fontSize = 13.sp)
                        Text("يختاره CentralBrain إذا فشلت مزودات API", color = Color(0xFF9A9A9A), fontSize = 10.sp)
                    }
                    Switch(
                        checked = centralEnabled, 
                        onCheckedChange = { 
                            centralEnabled = it
                            WebProvidersPrefs.setCentralEnabled(context, it)
                        }, 
                        colors = SwitchDefaults.colors(checkedThumbColor = orange)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("🧰 أدوات المطور المرتبطة:", color = orange, fontSize = 13.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { 
                            Switch(
                                checked = filesEnabled, 
                                onCheckedChange = { 
                                    filesEnabled = it
                                    WebProvidersPrefs.setFilesEnabled(context, it)
                                }, 
                                colors = SwitchDefaults.colors(checkedThumbColor = orange)
                            )
                            Text(" 📁 ملفات", color = Color.White, fontSize = 11.sp) 
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) { 
                            Switch(
                                checked = githubEnabled, 
                                onCheckedChange = { 
                                    githubEnabled = it
                                    WebProvidersPrefs.setGitHubEnabled(context, it)
                                }, 
                                colors = SwitchDefaults.colors(checkedThumbColor = orange)
                            )
                            Text(" 🐙 GitHub", color = Color.White, fontSize = 11.sp) 
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { 
                            Switch(
                                checked = emailEnabled, 
                                onCheckedChange = { 
                                    emailEnabled = it
                                    WebProvidersPrefs.setEmailEnabled(context, it)
                                }, 
                                colors = SwitchDefaults.colors(checkedThumbColor = orange)
                            )
                            Text(" 📧 Email", color = Color.White, fontSize = 11.sp) 
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) { 
                            Switch(
                                checked = toolsEnabled, 
                                onCheckedChange = { 
                                    toolsEnabled = it
                                    WebProvidersPrefs.setToolsEnabled(context, it)
                                }, 
                                colors = SwitchDefaults.colors(checkedThumbColor = orange)
                            )
                            Text(" 🛠 Tools", color = Color.White, fontSize = 11.sp) 
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF3A2E1F))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("🌐 تبويبات الويب (${tabsState.values.count { it }}/${allTabs.size})", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { 
                        val newVal = tabsState.values.count { it } < allTabs.size
                        allTabs.forEach { provider: WebProvider -> 
                            WebProvidersPrefs.setProviderEnabled(context, provider.id, newVal)
                        }
                        tabsState = allTabs.associate { it.id to newVal }.toMutableMap()
                    }) { 
                        Text(if (tabsState.values.count { it } < allTabs.size) "تفعيل الكل" else "تعطيل الكل", color = orange, fontSize = 11.sp) 
                    }
                }
                allTabs.forEach { tab: WebProvider ->
                    val enabled = tabsState[tab.id] ?: true
                    Row(
                        verticalAlignment = Alignment.CenterVertically, 
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).background(if (enabled) Color(0xFF3A2E1F) else Color.Transparent, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("${tab.icon} ${tab.name}", color = if (enabled) Color.White else Color(0xFF777777), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = enabled, 
                            onCheckedChange = { checked: Boolean -> 
                                tabsState[tab.id] = checked
                                WebProvidersPrefs.setProviderEnabled(context, tab.id, checked)
                                tabsState = tabsState.toMutableMap()
                            }, 
                            colors = SwitchDefaults.colors(checkedThumbColor = orange)
                        )
                    }
                }
            }
        }
    }
}
