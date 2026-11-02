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
    val allTabs: List<WebProvider> = WebProviderAdapter.providers
    val lionEnabled = remember { mutableStateOf(WebProvidersPrefs.isLionEnabled(context)) }
    val centralEnabled = remember { mutableStateOf(WebProvidersPrefs.isCentralEnabled(context)) }
    val filesEnabled = remember { mutableStateOf(WebProvidersPrefs.isFilesEnabled(context)) }
    val githubEnabled = remember { mutableStateOf(WebProvidersPrefs.isGitHubEnabled(context)) }
    val emailEnabled = remember { mutableStateOf(WebProvidersPrefs.isEmailEnabled(context)) }
    val toolsEnabled = remember { mutableStateOf(WebProvidersPrefs.isToolsEnabled(context)) }
    val tabsState = remember { mutableStateMapOf<String, Boolean>().apply { allTabs.forEach { p: WebProvider -> put(p.id, WebProvidersPrefs.isProviderEnabled(context, p.id)) } } }
    val orange = Color(0xFFFF6D00)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A0A)), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lion Chat", color = orange, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Surface(color = orange, shape = RoundedCornerShape(8.dp)) { Text(" العقل البرتقالي ", color = Color.Black, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) }
                Spacer(Modifier.weight(1f))
                Switch(checked = lionEnabled.value, onCheckedChange = { v: Boolean -> lionEnabled.value = v; WebProvidersPrefs.setLionEnabled(context, v) }, colors = SwitchDefaults.colors(checkedThumbColor = orange))
            }
            Text("يظهر في الشات بزر برتقالي، مربوط بالعقل المركزي + أدوات المطور", color = Color(0xFFB0B0B0), fontSize = 12.sp)
            if (lionEnabled.value) {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("مربوط بالعقل المركزي", color = Color.White, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Switch(checked = centralEnabled.value, onCheckedChange = { v: Boolean -> centralEnabled.value = v; WebProvidersPrefs.setCentralEnabled(context, v) }, colors = SwitchDefaults.colors(checkedThumbColor = orange))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = filesEnabled.value, onCheckedChange = { v: Boolean -> filesEnabled.value = v; WebProvidersPrefs.setFilesEnabled(context, v) }, colors = SwitchDefaults.colors(checkedThumbColor = orange)); Text(" ملفات", color = Color.White, fontSize = 11.sp) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = githubEnabled.value, onCheckedChange = { v: Boolean -> githubEnabled.value = v; WebProvidersPrefs.setGitHubEnabled(context, v) }, colors = SwitchDefaults.colors(checkedThumbColor = orange)); Text(" GitHub", color = Color.White, fontSize = 11.sp) }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = emailEnabled.value, onCheckedChange = { v: Boolean -> emailEnabled.value = v; WebProvidersPrefs.setEmailEnabled(context, v) }, colors = SwitchDefaults.colors(checkedThumbColor = orange)); Text(" Email", color = Color.White, fontSize = 11.sp) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = toolsEnabled.value, onCheckedChange = { v: Boolean -> toolsEnabled.value = v; WebProvidersPrefs.setToolsEnabled(context, v) }, colors = SwitchDefaults.colors(checkedThumbColor = orange)); Text(" Tools", color = Color.White, fontSize = 11.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                allTabs.forEach { tab: WebProvider ->
                    val en: Boolean = tabsState[tab.id] ?: true
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).background(if (en) Color(0xFF3A2E1F) else Color.Transparent, RoundedCornerShape(8.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${tab.icon} ${tab.name}", color = Color.White, modifier = Modifier.weight(1f), fontSize = 12.sp)
                        Switch(checked = en, onCheckedChange = { c: Boolean -> tabsState[tab.id] = c; WebProvidersPrefs.setProviderEnabled(context, tab.id, c) }, colors = SwitchDefaults.colors(checkedThumbColor = orange))
                    }
                }
            }
        }
    }
}
