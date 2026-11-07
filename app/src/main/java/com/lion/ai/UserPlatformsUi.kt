package com.lion.ai

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        initiallyExpanded = true,
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


/**
 * نافذة إضافة منصة/مزود: للتطبيق (يعمل في كل المحادثات) أو لـ OpenCode Local (يظهر تحت OpenCode).
 * للخوادم المحلية (Ollama/LiteLLM) اترك المفتاح فارغاً واستخدم عنوان http://192.168.x.x:port/v1.
 */
@Composable
fun AddPlatformDialog(prefs: SharedPreferences, onDismiss: () -> Unit, onAdded: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gold = Color(0xFFFFB800)
    val muted = Color(0xFF9A9A9A)
    var forOpenCode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = gold, unfocusedBorderColor = Color(0xFF2A2A2A),
        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = gold
    )
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Color(0xFF1A1A1A),
        title = { Text("➕ إضافة منصة أو مزود", color = gold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("تعمل مع أي منصة متوافقة مع OpenAI (عامة أو خاصة أو محلية).", color = muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { forOpenCode = false },
                        colors = ButtonDefaults.buttonColors(containerColor = if (!forOpenCode) Color(0xFFFF6D00) else Color(0xFF2A2A2A))
                    ) { Text("للتطبيق", color = Color.White, fontSize = 12.sp) }
                    Button(
                        onClick = { forOpenCode = true },
                        colors = ButtonDefaults.buttonColors(containerColor = if (forOpenCode) Color(0xFFFF6D00) else Color(0xFF2A2A2A))
                    ) { Text("لـ OpenCode Local", color = Color.White, fontSize = 12.sp) }
                }
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("اسم المنصة") }, colors = colors, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it.trim() }, singleLine = true, label = { Text("Base URL (https://…/v1 أو http://192.168…/v1)") }, colors = colors, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    key, { key = it }, singleLine = true, label = { Text("المفتاح (فارغ للخوادم المحلية)") }, colors = colors,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(models, { models = it }, label = { Text(if (forOpenCode) "الموديلات (مطلوبة)" else "الموديلات (اختياري)") }, colors = colors, modifier = Modifier.fillMaxWidth())
                TextButton(
                    enabled = !busy && url.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "⏳ جلب الموديلات…"
                            val result = withContext(Dispatchers.IO) { runCatching { UserPlatforms.fetchModels(url, key) } }
                            result.onSuccess { models = it.joinToString(", "); status = "✅ ${it.size} موديل" }
                            result.onFailure { status = "❌ ${it.message ?: it.javaClass.simpleName}" }
                            busy = false
                        }
                    }
                ) { Text("🔄 جلب الموديلات تلقائياً من المنصة", color = gold, fontSize = 12.sp) }
                if (status.isNotBlank()) {
                    Text(status, color = if (status.startsWith("❌")) Color(0xFFFF9A9A) else if (status.startsWith("⏳")) muted else Color(0xFF7CC47C), fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && name.isNotBlank() && url.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        val n = name; val u = url; val k = key; val m = models
                        if (forOpenCode) {
                            status = "⏳ إضافة المنصة وإعادة تشغيل OpenCode (قد يستغرق دقيقة)…"
                            val msg = withContext(Dispatchers.IO) {
                                runCatching {
                                    OpenCodeConfig.applyAndVerify(context) {
                                        OpenCodeConfig.addProvider(it, UserPlatforms.slug(n), n, u, k, m)
                                    }
                                }.getOrElse { "❌ ${it.message ?: it.javaClass.simpleName}" }
                            }
                            status = msg
                            if (msg.startsWith("✅")) { onAdded(); onDismiss() }
                        } else {
                            val result = runCatching {
                                UserPlatforms.add(prefs, n, u, k, m)
                                runCatching { LlmClient.reloadOverrides(prefs) }
                            }
                            result.onSuccess { onAdded(); onDismiss() }
                            result.onFailure { status = "❌ ${it.message ?: it.javaClass.simpleName}" }
                        }
                        busy = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
            ) { Text(if (busy) "…" else "إضافة", color = Color.White) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("إلغاء", color = muted) } }
    )
}
