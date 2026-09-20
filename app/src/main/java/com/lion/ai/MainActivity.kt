package com.lion.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

data class Message(val text: String, val isUser: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFFB800), background = Color(0xFF0D0D0D), surface = Color(0xFF1A1A1A))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D0D0D)) {
                    LionScreen()
                }
            }
        }
    }
}

@Composable
fun LionScreen() {
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
        Surface(color = Color(0xFF1A1A1A), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("🦁", fontSize = 32.sp)
                Spacer(Modifier.width(8.dp))
                Text("Lion AI", color = Color(0xFFFFB800), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { msg -> Bubble(msg) }
            if (loading) {
                item { Text("... يفكر", color = Color(0xFFB0B0B0), modifier = Modifier.padding(8.dp)) }
            }
        }

        Surface(color = Color(0xFF1A1A1A), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("اكتب رسالتك...", color = Color(0xFFB0B0B0)) },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFFB800), unfocusedBorderColor = Color(0xFF2A2A2A), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (input.isNotBlank() && !loading) {
                            val userMsg = input
                            messages = messages + Message(userMsg, true)
                            input = ""
                            loading = true
                            Thread {
                                try {
                                    val py = Python.getInstance()
                                    val module = py.getModule("assistant")
                                    val reply = module.callAttr("ask", userMsg, "").toString()
                                    runOnUiThread {
                                        messages = messages + Message(reply, false)
                                        loading = false
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread {
                                        messages = messages + Message("خطأ: ${e.message}", false)
                                        loading = false
                                    }
                                }
                            }.start()
                        }
                    },
                    enabled = !loading,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
                ) {
                    Text("➤", color = Color(0xFF0D0D0D), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun Bubble(msg: Message) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start) {
        Card(
            colors = CardDefaults.cardColors(containerColor = if (msg.isUser) Color(0xFFFFB800) else Color(0xFF2A2A2A)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(text = msg.text, modifier = Modifier.padding(12.dp), color = if (msg.isUser) Color(0xFF0D0D0D) else Color.White, fontSize = 15.sp)
        }
    }
}
