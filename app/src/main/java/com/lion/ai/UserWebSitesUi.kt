package com.lion.ai

import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * نافذة إضافة صفحة ويب بالرابط — تُستخدم من نافذة اختيار المزود في الشات ومن الإعدادات،
 * فتكون الإضافة من مكانين بنفس النتيجة: بطاقة مزود ويب كاملة تُربط بالجسر فوراً.
 */
@Composable
fun AddWebSiteDialog(prefs: SharedPreferences, onDismiss: () -> Unit, onAdded: () -> Unit) {
    val scope = rememberCoroutineScope()
    val gold = Color(0xFFFFB800)
    val muted = Color(0xFF9A9A9A)
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = gold, unfocusedBorderColor = Color(0xFF2A2A2A),
        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = gold
    )
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Color(0xFF1A1A1A),
        title = { Text("➕ إضافة صفحة دردشة ويب", color = gold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "ألصق رابط الصفحة (مثل deepai.org/chat). تُضاف مزود ويب كاملاً: منتقي المزود، " +
                        "جسر الجلسات، الأدوات، والفحص والمهلة والمحددات من بطاقة «Lion Chat».",
                    color = muted, fontSize = 11.sp
                )
                OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text("اسم الصفحة (اختياري)") },
                    colors = colors, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    url, { url = it.trim() }, singleLine = true, label = { Text("رابط صفحة الدردشة (https://…)") },
                    colors = colors, modifier = Modifier.fillMaxWidth()
                )
                if (status.isNotBlank()) {
                    Text(
                        status,
                        color = if (status.startsWith("❌")) Color(0xFFFF9A9A) else Color(0xFF7CC47C),
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && url.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        val result = runCatching {
                            val entry = UserWebSites.add(prefs, name, url)
                            runCatching { LlmClient.reloadOverrides(prefs) }
                            entry
                        }
                        result.onSuccess {
                            status = "✅ أُضيفت ${it.label} — فَعّلها من بطاقة «Lion Chat» ثم اخترها للشات."
                            onAdded(); onDismiss()
                        }
                        result.onFailure { status = "❌ ${it.message ?: it.javaClass.simpleName}" }
                        busy = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
            ) { Text(if (busy) "…" else "إضافة وربط", color = Color.White) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("إلغاء", color = muted) } }
    )
}

/**
 * «➕ صفحاتي الخاصة (ويب)»: المستخدم يلصق رابط أي صفحة دردشة فتُضاف مزوداً كاملاً.
 *
 * لا تسجيل يدوياً في مكان آخر: البطاقة تُدمج في [PROVIDERS] بـ webviewUrl، فمجموعتها WEB
 * مشتقة منها، ومن ثم تظهر في نافذة اختيار المزود وتحت «🌐 مزودات صفحات الويب» وفي بطاقة
 * «Lion Chat» (فحص/مهلة/محددات/دورة أدوات) ويقرأها جسر الويب مباشرة. الكشف الخلفي يضيف
 * ما يوجد في HTML الصفحة من محددات فيُربط زر الإرسال وحقل الكتابة دون أن يعرفها التطبيق سابقاً.
 */
@Composable
fun UserWebSitesSection(prefs: SharedPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmId by remember { mutableStateOf<String?>(null) }
    val gold = Color(0xFFFFB800)
    val muted = Color(0xFF9A9A9A)
    val sites = remember(version) { UserWebSites.list(prefs) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = gold, unfocusedBorderColor = Color(0xFF2A2A2A),
        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = gold
    )

    fun detect(id: String, link: String, hostLabel: String) {
        scope.launch {
            busy = true
            status = "⏳ تُقرأ الصفحة… " + UserWebSites.hostOf(link)
            val line = withContext(Dispatchers.IO) {
                runCatching {
                    val f = UserWebSites.fetch(link)
                    val d = UserWebSites.detect(f.html)
                    UserWebSites.storeDetection(prefs, id, d, f.finalHost, hostLabel)
                    "🔎 " + (d.summary().ifBlank { "لم يُعثر على محددات — المحددات العامة تكفي أغلب الصفحات." }) +
                        (if (f.finalHost.isNotBlank() && f.finalHost != UserWebSites.hostOf(link))
                            "\nأُضيف نطاق التحويل: ${f.finalHost}" else "")
                }.getOrElse { "❌ تعذّرت قراءة الصفحة (${it.javaClass.simpleName}) — الربط يعمل بالمحددات العامة." }
            }
            status = line
            version++
            busy = false
        }
    }

    SettingsSection(
        title = "➕ صفحاتي الخاصة (ويب)",
        initiallyExpanded = true,
        subtitle = "ألصق رابط أي صفحة دردشة فتُضاف مزوداً كاملاً: الجسر والأدوات وواجهة التطبيق " +
            "تتعرف عليها تلقائياً، ويُقترن زر الإرسال وحقل الكتابة بما في الصفحة نفسها.",
        badge = "${sites.size} صفحة"
    ) {
        Spacer(Modifier.height(6.dp))
        sites.forEach { site ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(context, ArenaActivity::class.java).putExtra("ARENA_URL", site.url)
                                )
                            }
                        }
                ) {
                    Text(site.label, color = Color.White, fontSize = 13.sp)
                    val detected = UserWebSites.Detected(
                        input = site.input, send = site.send, stop = site.stop, answer = site.answer
                    ).summary()
                    Text(
                        site.url + " — " + if (detected.isBlank()) "محددات عامة" else detected,
                        color = muted, fontSize = 10.sp, maxLines = 2
                    )
                }
                Switch(
                    checked = prefs.getBoolean("web_chat_enabled_${site.id}", false),
                    onCheckedChange = { value ->
                        UserWebSites.setBridgeEnabled(prefs, site.id, value)
                        runCatching { LlmClient.reloadOverrides(prefs) }
                        version++
                        status = if (value) "✅ ${site.label} في جسر جلسات المواقع — اختَرها للشات من بطاقة «Lion Chat»."
                        else "⏸️ ${site.label} خارج الجسر."
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF6D00))
                )
            }
            Row(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                TextButton(enabled = !busy, onClick = { detect(site.id, site.url, site.label) }) {
                    Text("🔎 إعادة كشف المحددات", color = gold, fontSize = 11.sp)
                }
                TextButton(onClick = {
                    WebChatRuntime.setDesktopUa(prefs, site.id, !WebChatRuntime.desktopUa(prefs, site.id))
                    version++
                    status = if (WebChatRuntime.desktopUa(prefs, site.id))
                        "🖥️ وكيل سطح المكتب مفعّل لـ${site.label} — يُطبَّق على الجلسة التالية."
                    else "📱 وكيل الجوال لـ${site.label}."
                }) {
                    Text(
                        if (WebChatRuntime.desktopUa(prefs, site.id)) "🖥️ سطح مكتب" else "📱 جوال",
                        color = muted, fontSize = 11.sp
                    )
                }
                if (confirmId == site.id) {
                    TextButton(onClick = {
                        UserWebSites.remove(prefs, site.id)
                        runCatching { LlmClient.reloadOverrides(prefs) }
                        confirmId = null
                        status = "حُذفت ${site.label}"
                        version++
                    }) { Text("تأكيد الحذف", color = Color(0xFFFF9A9A), fontSize = 11.sp) }
                    TextButton(onClick = { confirmId = null }) { Text("إلغاء", color = muted, fontSize = 11.sp) }
                } else {
                    TextButton(onClick = { confirmId = site.id }) {
                        Text("حذف", color = Color(0xFFFF9A9A), fontSize = 11.sp)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                name, { name = it }, singleLine = true,
                label = { Text("اسم الصفحة (اختياري — يُؤخذ من النطاق أو عنوان الصفحة)") },
                colors = colors, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                url, { url = it.trim() }, singleLine = true,
                label = { Text("رابط صفحة الدردشة (https://… مثل deepai.org/chat)") },
                colors = colors, modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = !busy && url.isNotBlank(),
                onClick = {
                    status = runCatching {
                        val entry = UserWebSites.add(prefs, name, url)
                        runCatching { LlmClient.reloadOverrides(prefs) }
                        name = ""; url = ""
                        version++
                        "✅ أُضيفت ${entry.label} (${entry.id}). فعّلها من المفتاح أعلاه ثم اخترها للشات، " +
                            "أو من «🌐 مزودات صفحات الويب». تُقرأ صفحتها الآن لإقران زر الإرسال."
                    }.getOrElse { "❌ ${it.message ?: it.javaClass.simpleName}" }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
            ) { Text("إضافة الصفحة وربطها", color = Color.White) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotBlank()) {
                Text(
                    status,
                    color = if (status.startsWith("❌")) Color(0xFFFF9A9A)
                    else if (status.startsWith("⏳")) muted else Color(0xFF7CC47C),
                    fontSize = 11.sp
                )
            }
            Text(
                "الموقع يُقاد داخل التطبيق كما تُقاد المواقع المدمجة: يكتب التطبيق في حقلها وينتظر " +
                    "جوابها الجديد، وتعمل معها أدوات الجسر بعد فحصها. لا تُرسل مفاتيح ولا كوكيز إلى أي خادم.",
                color = muted, fontSize = 10.sp
            )
        }
    }
}
