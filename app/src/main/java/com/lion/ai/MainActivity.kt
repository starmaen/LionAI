package com.lion.ai

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

data class Message(val text: String, val isUser: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFFB800),
                    background = Color(0xFF0D0D0D),
                    surface = Color(0xFF1A1A1A)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D0D0D)) {
                    LionApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LionApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("lion_prefs", Context.MODE_PRIVATE)
    var showSettings by remember { mutableStateOf(prefs.getString("api_key", "").isNullOrEmpty()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🦁", fontSize = 28.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("Lion AI", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color(0xFFFFB800))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A),
                    titleContentColor = Color(0xFFFFB800)
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (showSettings) {
                SettingsScreen(prefs) { showSettings = false }
            } else {
                ChatScreen(prefs)
            }
        }
    }
}

@Composable
fun SettingsScreen(prefs: android.content.SharedPreferences, onSave: () -> Unit) {
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var provider by remember { mutableStateOf(prefs.getString("provider", "groq") ?: "groq") }
    val providers = listOf("groq", "gemini", "openai", "claude")

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text("⚙️ الإعدادات", color = Color(0xFFFFB800), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))

        Text("المزود:", color = Color.White)
        Spacer(Modifier.height(8.dp))
        providers.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                RadioButton(
                    selected = provider == p,
                    onClick = { provider = p },
                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFB800))
                )
                Text(p.replaceFirstChar { it.uppercase() }, color = Color.White)
            }
        }
        Spacer(Modifier.height(16.dp))

        Text("مفتاح API:", color = Color.White)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("أدخل مفتاح API هنا", color = Color.Gray) },
            singleLine = false,
            maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFFFB800),
                unfocusedBorderColor = Color(0xFF2A2A2A),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                prefs.edit().putString("api_key", apiKey).putString("provider", provider).apply()
                onSave()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
        ) {
            Text("💾 حفظ", color = Color(0xFF0D0D0D), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ChatScreen(prefs: android.content.SharedPreferences) {
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg -> Bubble(msg) }
            if (loading) {
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))) {
                            Text("... يفكر", modifier = Modifier.padding(12.dp), color = Color(0xFFB0B0B0))
                        }
                    }
                }
            }
        }

        Surface(color = Color(0xFF1A1A1A), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("اكتب رسالتك...", color = Color(0xFFB0B0B0)) },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFFB800),
                        unfocusedBorderColor = Color(0xFF2A2A2A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (input.isNotBlank() && !loading) {
                            val userMsg = input
                            messages = messages + Message(userMsg, true)
                            input = ""
                            loading = true
                            scope.launch {
                                val reply = withContext(Dispatchers.IO) {
                                    try {
                                        val apiKey = prefs.getString("api_key", "") ?: ""
                                        val provider = prefs.getString("provider", "groq") ?: "groq"
                                        val py = Python.getInstance()
                                        val module = py.getModule("assistant")
                                        module.callAttr("ask", userMsg, apiKey, provider).toString()
                                    } catch (e: Exception) {
                                        "خطأ: \${e.message}"
                                    }
                                }
                                messages = messages + Message(reply, false)
                                loading = false
                            }
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (msg.isUser) Color(0xFFFFB800) else Color(0xFF2A2A2A)
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = msg.text,
                modifier = Modifier.padding(12.dp),
                color = if (msg.isUser) Color(0xFF0D0D0D) else Color.White,
                fontSize = 15.sp
            )
        }
    }
}
