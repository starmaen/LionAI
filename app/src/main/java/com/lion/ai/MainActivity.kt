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
import com.lion.ai.ui.theme.*
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Message(val text: String, val isUser: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        setContent {
            LionAITheme {
                Surface(modifier = Modifier.fillMaxSize(), color = DarkBg) {
                    LionAIScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LionAIScreen() {
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(DarkBg)) {
        // الشريط العلوي
        Surface(color = SurfaceDark, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text("🦁", fontSize = 32.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Lion AI",
                    color = Gold,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // الرسائل
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg -> MessageBubble(msg) }
            if (isLoading) {
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceLight)) {
                            Text("... يفكر", modifier = Modifier.padding(12.dp), color = TextGray)
                        }
                    }
                }
            }
        }

        // الإدخال
        Surface(color = SurfaceDark, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("اكتب رسالتك...", color = TextGray) },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Gold,
                        unfocusedBorderColor = SurfaceLight,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    )
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (inputText.isNotBlank() && !isLoading) {
                            val userMsg = inputText
                            messages = messages + Message(userMsg, true)
                            inputText = ""
                            isLoading = true
                        }
                    },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold)
                ) {
                    Text("➤", color = DarkBg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(msg: Message) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (msg.isUser) Gold else SurfaceLight
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (msg.isUser) 16.dp else 4.dp,
                bottomEnd = if (msg.isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = msg.text,
                modifier = Modifier.padding(12.dp),
                color = if (msg.isUser) DarkBg else TextWhite,
                fontSize = 15.sp
            )
        }
    }
}
