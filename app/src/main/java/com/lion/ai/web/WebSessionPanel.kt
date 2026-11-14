package com.lion.ai

import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex

/** One chat surface. Browser stays attached BEHIND it at full viewport size.
 * Only an explicit session-open action raises the browser above chat for login/manual use.
 * Do not use GONE, a 1px viewport, or detach the WebView: those break site layout/polling.
 */
@Composable
fun WebSessionPanel(content: @Composable () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val keyboard = LocalSoftwareKeyboardController.current
    val prefs = remember(activity) { SecurePrefs.get(activity) }
    var showSession by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var host by remember { mutableStateOf<AndroidWebSessionHost?>(null) }
    fun closeSession() {
        showSession = false
        host?.setInteractive(false)
        keyboard?.hide()
    }
    DisposableEffect(activity) { onDispose { host?.destroy() } }
    BackHandler(enabled = showSession) { closeSession() }

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
                .padding(top = if (showSession) 100.dp else 0.dp)
                .then(if (showSession) Modifier.windowInsetsPadding(WindowInsets.systemBars) else Modifier)
                .zIndex(if (showSession) 1f else -1f)
        )
        // Opaque full-size Surface also prevents touches falling through to the hidden browser.
        Surface(modifier = Modifier.fillMaxSize().zIndex(0f), color = Color(0xFF0D0D0D)) {
            content()
        }
        if (showSession) {
            Surface(modifier = Modifier.fillMaxWidth().statusBarsPadding().height(100.dp).zIndex(2f), color = Color(0xFF20180F)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(label, color = Color(0xFFFF8A30), fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f))
                        TextButton(onClick = { host?.goBack() }) { Text("رجوع") }
                        TextButton(onClick = { host?.reloadCurrent() }) { Text("تحديث") }
                        TextButton(onClick = { host?.openCurrentInBrowser() }) { Text("المتصفح") }
                        TextButton(onClick = { closeSession() }) { Text("العودة للشات") }
                    }
                    // تبديل صفحة الويب المعروضة من داخل التطبيق (المواقع المفعّلة فقط).
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        WebChatRuntime.members(prefs).forEach { info ->
                            val st = WebSessionStatus.infoFor(info.id)
                            TextButton(onClick = { WebSessionTransport.open(info.id) }) {
                                Text("● ", color = webLevelColor(st.level), fontSize = 12.sp)
                                Text(info.shortName, color = Color(0xFFEEEEEE), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun webLevelColor(level: WebSessionStatus.Level): Color = when (level) {
    WebSessionStatus.Level.GREEN -> Color(0xFF3DDC84)
    WebSessionStatus.Level.AMBER -> Color(0xFFFFB800)
    WebSessionStatus.Level.RED -> Color(0xFFFF4D4D)
    WebSessionStatus.Level.IDLE -> Color(0xFF8A8A8A)
}

/** نقطة حالة صفحة الويب للمزود المختار: تفتح الجلسة عند النقر. لا تظهر لمزودات API. */
@Composable
fun WebStatusDot(provider: String) {
    if (!WebChatRuntime.handles(provider)) return
    val info = WebSessionStatus.infoFor(provider)
    TextButton(onClick = { WebSessionTransport.open(provider) }) {
        Text("●", color = webLevelColor(info.level), fontSize = 16.sp)
    }
}
