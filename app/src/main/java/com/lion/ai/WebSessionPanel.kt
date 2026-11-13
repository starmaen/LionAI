package com.lion.ai

import android.content.SharedPreferences
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex

/** ألوان حالة جلسة الموقع: أخضر = يعمل (ردّ فعلي وصل)، أصفر = محمّل/قيد التحقق، أحمر = مغلق/فاشل. */
internal fun liveColor(state: String): Color = when (state) {
    "green" -> Color(0xFF2ECC71)
    "red" -> Color(0xFFE74C3C)
    "amber" -> Color(0xFFF1C40F)
    else -> Color(0xFF777777)
}

internal fun liveStateOf(prefs: SharedPreferences, provider: String): String =
    prefs.getString("web_session_live_$provider", "").orEmpty()

/** حالة مجمّعة لـ Lion Chat: أخضر إن كان أي موقع أخضر، وإلا أصفر إن وُجد، وإلا أحمر إن وُجد. */
internal fun aggregateLive(states: List<String>): String = when {
    states.any { it == "green" } -> "green"
    states.any { it == "amber" } -> "amber"
    states.any { it == "red" } -> "red"
    else -> ""
}

/** One chat surface. Browser stays attached BEHIND it at full viewport size.
 * Only an explicit session-open action raises the browser above chat for login/manual use.
 * Do not use GONE, a 1px viewport, or detach the WebView: those break site layout/polling.
 *
 * إضافات هذه النسخة (بلا لمس LionApp): شريط تبديل المواقع داخل الجلسة، ومؤشر حالة (أخضر/أصفر/أحمر)
 * فوق الشات للمزود المختار، وزر 📎 يرفع صورة أو ملفاً إلى صفحة الموقع المختار.
 */
@Composable
fun WebSessionPanel(content: @Composable () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val keyboard = LocalSoftwareKeyboardController.current
    val prefs = remember(activity) { SecurePrefs.get(activity) }
    var showSession by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var host by remember { mutableStateOf<AndroidWebSessionHost?>(null) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }

    fun closeSession() {
        showSession = false
        host?.setInteractive(false)
        keyboard?.hide()
    }
    DisposableEffect(activity) { onDispose { host?.destroy() } }
    // أي تغيّر في المزود المختار أو حالة جلسة موقع يعيد رسم المؤشر.
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "provider" || key == "auto_route" || (key != null && key.startsWith("web_session_live_"))) {
                version++
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    BackHandler(enabled = showSession) { closeSession() }

    val selected = prefs.getString("provider", "").orEmpty()
    // نقرأ version هنا ليُعاد التركيب عند تغيّر الحالة.
    val pillState = remember(version, selected) {
        when {
            selected == WebChatRuntime.HUB ->
                aggregateLive(WebChatRuntime.members(prefs).map { liveStateOf(prefs, it.id) })
            WebChatRuntime.isWeb(selected) -> liveStateOf(prefs, selected)
            else -> null
        }
    }
    val pillTarget: String? = when {
        selected == WebChatRuntime.HUB -> WebChatRuntime.members(prefs).firstOrNull()?.id
        WebChatRuntime.isWeb(selected) -> selected
        else -> null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val target = currentId ?: pillTarget
        if (uris.isNullOrEmpty() || target == null) return@rememberLauncherForActivityResult
        host?.uploadFiles(target, uris) { message ->
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    val barHeight = 96.dp
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).also { frame ->
                    val engine = AndroidWebSessionHost(activity, frame, prefs) { explicitlyOpen, text ->
                        // Status/stream/error events never open or resize the chat UI.
                        if (explicitlyOpen) showSession = true
                        label = text
                    }
                    host = engine
                    WebSessionTransport.attach(engine)
                }
            },
            update = { frame ->
                // Focus leases are owned by the engine; recomposition must not revoke editor focus.
                frame.importantForAccessibility = if (showSession) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                host?.setInteractive(showSession)
            },
            modifier = Modifier.fillMaxSize()
                .padding(top = if (showSession) barHeight else 0.dp)
                .then(if (showSession) Modifier.windowInsetsPadding(WindowInsets.systemBars) else Modifier)
                .zIndex(if (showSession) 1f else -1f)
        )
        // Opaque full-size Surface also prevents touches falling through to the hidden browser.
        Surface(modifier = Modifier.fillMaxSize().zIndex(0f), color = Color(0xFF0D0D0D)) {
            content()
        }

        // مؤشر الحالة فوق الشات: أخضر يعمل / أصفر قيد التحقق / أحمر مغلق أو فاشل.
        if (!showSession && pillState != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 10.dp)
                    .zIndex(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xCC1A1A1A))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier.size(9.dp).clip(CircleShape).background(liveColor(pillState))
                )
                Text(
                    if (selected == WebChatRuntime.HUB) "Lion Chat" else providerInfo(selected).shortName,
                    color = Color(0xFFE6E6E6),
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.clickable {
                        pillTarget?.let { id -> currentId = id; host?.open(id) }
                    }
                )
                Text(
                    "📎",
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { if (pillTarget != null) picker.launch("*/*") }
                )
            }
        }

        if (showSession) {
            Surface(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().height(barHeight).zIndex(2f),
                color = Color(0xFF20180F)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, color = Color(0xFFFF8A30), fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f))
                        TextButton(onClick = { currentId?.let { id -> picker.launch("*/*"); Unit } }) { Text("📎") }
                        TextButton(onClick = { host?.reloadCurrent() }) { Text("تحديث") }
                        TextButton(onClick = { closeSession() }) { Text("العودة للشات") }
                    }
                    // تبديل صفحة الويب من واجهة التطبيق دون الخروج من الجلسة.
                    Row(
                        Modifier.fillMaxWidth().height(44.dp)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PROVIDERS.filter { it.group == ProviderGroup.WEB }.forEach { info ->
                            val isCurrent = currentId == info.id
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isCurrent) Color(0xFF3A2A12) else Color(0xFF2A2118))
                                    .clickable { currentId = info.id; host?.open(info.id) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    Modifier.size(8.dp).clip(CircleShape)
                                        .background(liveColor(liveStateOf(prefs, info.id)))
                                )
                                Text(
                                    info.shortName,
                                    color = if (isCurrent) Color(0xFFFFB800) else Color(0xFFD8D8D8),
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
