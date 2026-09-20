package com.lion.ai

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.PermissionRequest
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val perms = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.SEND_SMS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPerms.launch(perms)
        setContent {
            Surface(color = Color(0xFF0D0D0D), modifier = Modifier.fillMaxSize()) {
                LionWebView()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun LionWebView() {
        var isLoading by remember { mutableStateOf(true) }
        val url = "http://127.0.0.1:8082"

        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        webViewClient = WebViewClient()
                        webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                request?.grant(request.resources)
                            }
                        }
                        loadUrl(url)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("🦁", fontSize = 60.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Lion AI", color = Color(0xFFFFB800), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(24.dp))
                    CircularProgressIndicator(color = Color(0xFFFFB800))
                    Spacer(Modifier.height(16.dp))
                    Text("جاري التحميل...", color = Color(0xFFB0B0B0), fontSize = 14.sp)
                }
            }
        }
    }
}
