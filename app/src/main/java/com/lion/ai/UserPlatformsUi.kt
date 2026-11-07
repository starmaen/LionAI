package com.lion.ai

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** إضافة منصة مخصصة (Base URL + مفتاح + موديلات) وحذفها؛ تظهر تلقائياً في المنتقي والإعدادات. */
@Composable
fun UserPlatformsSection(prefs: SharedPreferences) {
    var version by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var confirmId by remember { mutableStateOf<String?>(null) }
    val gold = Color(0xFFFFB800)
    val muted = Color(0xFF9A9A9A)
    val platforms = remember(version) { UserPlatforms.list(prefs) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = gold, unfocusedBorderColor = Color(0xFF2A2A2A),
        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = gold
    )

    SettingsSection(
        title = "➕ منصاتي الخاصة",
        subtitle = "أضف أي منصة متوافقة مع OpenAI بمفتاحها وموديلاتها، واحذفها متى شئت",
        badge = "${platforms.size} منصة"
    ) {
        Spacer(Modifier.height(6.dp))
        platforms.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.label, color = Color.White, fontSize = 13.sp)
                    Text(
                        p.baseUrl + " · " + (if (p.models.isEmpty()) "بلا موديلات" else "${p.models.size} موديل"),
                        color = muted, fontSize = 10.sp, maxLines = 2
                    )
                }
                if (confirmId == p.id) {
                    TextButton(onClick = {
                        UserPlatforms.remove(prefs, p.id)
                        runCatching { LlmClient.reloadOverrides(prefs) }
                        confirmId = null
                        status = "🗑️ حُذفت ${p.label}"
                        version++
                    }) { Text("تأكيد الحذف", color = Color(0xFFFF9A9A), fontSize = 12.sp) }
                    TextButton(onClick = { confirmId = null }) { Text("إلغاء", color = muted, fontSize = 12.sp) }
                } else {
                    TextButton(onClick = { confirmId = p.id }) { Text("حذف", color = Color(0xFFFF9A9A), fontSize = 12.sp) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("اسم المنصة (مثل: Together)") }, colors = colors, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it.trim() }, singleLine = true, label = { Text("Base URL (مثل: https://api.together.xyz/v1)") }, colors = colors, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                key, { key = it }, singleLine = true, label = { Text("مفتاح API") }, colors = colors,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(models, { models = it }, label = { Text("الموديلات مفصولة بفواصل (اختياري)") }, colors = colors, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = name.isNotBlank() && url.isNotBlank(),
                onClick = {
                    status = runCatching {
                        val e = UserPlatforms.add(prefs, name, url, key, models)
                        runCatching { LlmClient.reloadOverrides(prefs) }
                        name = ""; url = ""; key = ""; models = ""
                        version++
                        "✅ أُضيفت ${e.label}. تجدها الآن في قائمة اختيار المزود."
                    }.getOrElse { "❌ ${it.message ?: it.javaClass.simpleName}" }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
            ) { Text("إضافة المنصة", color = Color.White) }
            if (status.isNotBlank()) {
                Text(
                    status,
                    color = if (status.startsWith("❌")) Color(0xFFFF9A9A) else Color(0xFF7CC47C),
                    fontSize = 11.sp
                )
            }
        }
    }
}
