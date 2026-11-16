package com.lion.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** بطاقة «تعلّم Lion-1 التلقائي»: تشغيل/إيقاف + مصادر المستخدم + جلب الآن. */
@Composable
fun LionLearnCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { SecurePrefs.get(context) }
    var enabled by remember { mutableStateOf(LionLearn.enabled(prefs)) }
    var sources by remember { mutableStateOf(prefs.getString(LionLearn.PREF_SOURCES, "").orEmpty()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val gold = Color(0xFFE0B96A)
    val muted = Color(0xFF9AA5A8)

    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1E20))) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("تعلّم Lion-1 التلقائي", color = gold, fontWeight = FontWeight.Bold)
                Switch(
                    checked = enabled,
                    onCheckedChange = { value ->
                        enabled = value
                        prefs.edit().putBoolean(LionLearn.PREF_ENABLED, value).apply()
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = gold)
                )
            }
            Text(
                "يتعلّم Lion-1 على جهازك فقط من إجابات المزودين الأخرى ومن مصادر عامة، ويستعين بما تعلّمه عند الأسئلة المشابهة. " +
                    "تُحذف الأسرار والبيانات الشخصية وأسماء المزودين قبل الحفظ.",
                color = muted, fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sources,
                onValueChange = {
                    sources = it
                    prefs.edit().putString(LionLearn.PREF_SOURCES, it).apply()
                },
                label = { Text("مصادر إضافية (رابط https في كل سطر)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = enabled && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "⏳ أجلب المصادر…"
                            val added = withContext(Dispatchers.IO) {
                                runCatching {
                                    prefs.edit().putLong("lion_learn_last_ingest", 0L).apply()
                                    LionLearn.autoIngest(prefs)
                                }.getOrDefault(-1)
                            }
                            val total = withContext(Dispatchers.IO) { LionLearn.knowledge()?.size() ?: 0 }
                            status = if (added < 0) "❌ تعذّر الجلب" else "✅ أُضيفت $added معرفة جديدة (الإجمالي $total)"
                            busy = false
                        }
                    }
                ) { Text("جلب المصادر الآن") }
                Text(status, color = muted, fontSize = 11.sp)
            }
        }
    }
}
