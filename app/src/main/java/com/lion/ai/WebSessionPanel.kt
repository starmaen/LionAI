package com.lion.ai

import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** The same Activity as the chat owns the browser; closing a side-page no longer kills chat requests. */
@Composable
fun WebSessionPanel() {
    val activity = LocalContext.current as ComponentActivity
    val prefs = remember(activity) { SecurePrefs.get(activity) }
    var expanded by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var host by remember { mutableStateOf<AndroidWebSessionHost?>(null) }
    DisposableEffect(activity) { onDispose { host?.destroy() } }
    Surface(color = Color(0xFF20180F)) {
        Column(Modifier.fillMaxWidth()) {
            if (label.isNotBlank()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text(label, color = Color(0xFFFF8A30), fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(1f))
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "تصغير" else "الجلسة") }
                    TextButton(onClick = { host?.cancelCurrent(); expanded = false }) { Text("إلغاء") }
                }
            }
            AndroidView(
                factory = { context ->
                    FrameLayout(context).also { frame ->
                        val engine = AndroidWebSessionHost(activity, frame, prefs) { visible, text ->
                            expanded = visible
                            label = text
                        }
                        host = engine
                        WebSessionTransport.attach(engine)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(if (expanded) 360.dp else 1.dp)
            )
        }
    }
}
