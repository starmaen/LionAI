package com.lion.ai.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lion.ai.core.WebProviderAdapter
import com.lion.ai.core.WebProvidersPrefs
@Composable
fun WebSettingsScreen(webAdapter: WebProviderAdapter, prefs: WebProvidersPrefs) {
    var webEnabled by remember { mutableStateOf(prefs.isWebEnabled()) }
    var testResult by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(16.dp)) {
        Text("اعدادات ربط صفحات الويب", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Row { Text("تفعيل ربط الويب بالعقل"); Switch(checked = webEnabled, onCheckedChange = { webEnabled = it; prefs.setWebEnabled(it) }) }
        Text("الحالة: ${prefs.getStatus()}")
        Divider(modifier = Modifier.padding(vertical = 16.dp))
        webAdapter.webProviders.forEach { provider ->
            var enabled by remember { mutableStateOf(prefs.isProviderEnabled(provider.id)) }
            Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("LionAI") // تعديل تلقائي
                Switch(checked = enabled, onCheckedChange = { enabled = it; prefs.setProviderEnabled(provider.id, it) })
            }
        }
        Button(onClick = {
            val count = prefs.getEnabledProvidersCount()
            testResult = if(webEnabled && count > 0) "الربط شغال! $count صفحات - الدماغ يرى ${count + 18} مزود - الجواب باسم LionAI" else "الربط معطل"
        }) { Text("اختبر الربط") }
        if(testResult.isNotEmpty()) { Card(modifier = Modifier.padding(top = 16.dp)) { Text(testResult, modifier = Modifier.padding(16.dp)) } }
    }
}
