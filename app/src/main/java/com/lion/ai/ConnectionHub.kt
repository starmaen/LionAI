package com.lion.ai

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Amber = Color(0xFFFFB800)

/** تبويبات الإعدادات: كل قسم في مكانه الواضح بدل قائمة طويلة واحدة. */
val SETTINGS_TABS = listOf("🔑 المزودات", "🔌 الاتصال", "🧠 الذكاء والأدوات", "⚙️ عام")

@Composable
fun SettingsTabsBar(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SETTINGS_TABS.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (on) Amber else Color(0xFF222222))
                    .clickable { onSelect(index) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (on) Color.Black else Color.White, fontSize = 13.sp)
            }
        }
    }
}

/**
 * طريق التنفيذ في مكان واحد: تلقائي (خادم ثم مباشر ثم محلي كشبكة أمان) / مباشر فقط / خادم فقط،
 * مع زر واحد لفحص عمل الخوادم. طبقة المزودات واحدة؛ الطريق تفصيل نقل يُختار تلقائياً.
 */
@Composable
fun ConnectionHubSection(prefs: SharedPreferences, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(WebGateway.load(prefs).mode) }
    var report by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val config = WebGateway.load(prefs)

    fun apply(value: String) {
        val cfg = WebGateway.load(prefs)
        WebGateway.save(prefs, cfg.url, cfg.token, value)
        RoutePolicy.setChannelEnabled(prefs, Channel.GATEWAY, value != WebGateway.MODE_OFF)
        setProviderEnabled(prefs, WebGateway.ENABLED_ID, value != WebGateway.MODE_OFF)
        RoutePolicy.setChannelEnabled(prefs, Channel.DIRECT, value != WebGateway.MODE_ONLY)
        LlmClient.reloadOverrides(prefs)
        WebGateway.load(prefs)
        mode = value
        onChanged()
    }

    SettingsSection(
        title = "🔌 طريق التنفيذ",
        subtitle = "نفس المزودات في كل الطرق؛ يختار التطبيق الطريق السليم ويعود للتالي عند الفشل",
        badge = WebGateway.modeLabel(mode),
        initiallyExpanded = true
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                WebGateway.MODE_AUTO to "🧠 تلقائي",
                WebGateway.MODE_OFF to "🔌 مباشر فقط",
                WebGateway.MODE_ONLY to "☁️ خادم فقط"
            ).forEach { (value, label) ->
                val on = mode == value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (on) Amber else Color(0xFF262626))
                        .clickable { apply(value) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text(label, color = if (on) Color.Black else Color.White, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (mode) {
                WebGateway.MODE_AUTO -> "تلقائي: يجرّب الخادم الخارجي أولاً ثم المباشر، والخادم المحلي شبكة أمان أخيرة. لا شيء يتعطل بتعطل طريق واحد."
                WebGateway.MODE_ONLY -> "خادم فقط: كل مزود مدعوم يمرّ عبر الخادم الخارجي ولا يعود للمباشر (يمكنك التبديل في أي وقت)."
                else -> "مباشر فقط: كل مزود يستخدم مفتاحه وعنوانه مباشرة، دون أي خادم."
            },
            color = Color(0xFFB0B0B0),
            fontSize = 12.sp
        )
        if (mode != WebGateway.MODE_OFF && (!config.configured || config.token.isBlank())) {
            Spacer(Modifier.height(6.dp))
            Text(
                "⚠️ الخادم الخارجي غير مكتمل: أدخل الرابط وRELAY_TOKEN في «تفاصيل الخوادم» أدناه، وإلا يُتخطى تلقائياً.",
                color = Color(0xFFFFB800),
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF262626))
                .clickable(enabled = !busy) {
                    busy = true
                    report = "⏳ جارٍ فحص الخوادم…"
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            val external = runCatching { WebGateway.test(prefs) }
                                .getOrElse { "❌ تعذّر فحص الخادم الخارجي: ${it.message}" }
                            val local = if (LocalAIProxy.isRunning()) "✅ الخادم المحلي يعمل داخل التطبيق." else "ℹ️ الخادم المحلي متوقف (اختياري)."
                            "الخادم الخارجي:\n$external\n\n$local"
                        }
                        report = text
                        busy = false
                        onChanged()
                    }
                }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) { Text(if (busy) "⏳ …" else "🩺 فحص الخوادم", color = Amber, fontSize = 14.sp) }
        if (report.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(report, color = Color(0xFFD0D0D0), fontSize = 12.sp)
        }
    }
}
