package com.lion.ai

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationStudioScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(0) }
    var selectedStyle by remember { mutableStateOf(MediaProviders.GenerationStyle.REALISTIC) }
    var generatedImage by remember { mutableStateOf<Bitmap?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(" استوديو التوليد", color = Color(0xFFFFB800)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "رجوع", tint = Color(0xFFFFB800))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
        },
        containerColor = Color(0xFF0D0D0D)
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            // شريط التبويبات
            TabRow(selectedTabIndex = activeTab, containerColor = Color(0xFF1A1A1A), contentColor = Color(0xFFFFB800)) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) { Text("🎨 صور", modifier = Modifier.padding(16.dp)) }
                Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) { Text("🎬 فيديو", modifier = Modifier.padding(16.dp)) }
            }
            Spacer(Modifier.height(16.dp))

            // حقل إدخال النص
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it; errorMessage = null },
                label = { Text("اكتب فكرة الصورة...", color = Color(0xFF7A7A7A)) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFFB800),
                    unfocusedBorderColor = Color(0xFF2A2A2A),
                    focusedTextColor = Color.White,
                    cursorColor = Color(0xFFFFB800)
                ),
                textStyle = LocalTextStyle.current.copy(color = Color.White)
            )

            Spacer(Modifier.height(16.dp))

            // اختيار النمط
            Text("اختر النمط:", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(MediaProviders.GenerationStyle.values()) { style ->
                    FilterChip(
                        selected = selectedStyle == style,
                        onClick = { selectedStyle = style },
                        label = { Text(style.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFB800),
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // زر التوليد
            Button(
                onClick = {
                    if (prompt.isBlank()) {
                        errorMessage = "⚠️ الرجاء كتابة وصف للصورة أولاً"
                        return@Button
                    }
                    isGenerating = true
                    errorMessage = null
                    scope.launch {
                        generatedImage = MediaProviders.generateImage(prompt, selectedStyle)
                        isGenerating = false
                        if (generatedImage == null) errorMessage = "❌ فشل التوليد، تأكد من الاتصال بالإنترنت"
                    }
                },
                enabled = !isGenerating,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800)),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                } else {
                    Text("✨ توليد الصورة", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(errorMessage!!, color = Color(0xFFE94560), fontSize = 14.sp)
            }

            Spacer(Modifier.height(24.dp))

            // عرض النتيجة
            generatedImage?.let { bitmap ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "الصورة المولدة",
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                Spacer(Modifier.height(16.dp))
                
                // زر الحفظ الذكي
                var showSaveOptions by remember { mutableStateOf(false) }
                
                OutlinedButton(
                    onClick = { showSaveOptions = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB800))
                ) {
                    Icon(Icons.Filled.Download, "حفظ", modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("حفظ الصورة")
                }

                if (showSaveOptions) {
                    AlertDialog(
                        onDismissRequest = { showSaveOptions = false },
                        title = { Text("اختر مكان الحفظ", color = Color(0xFFFFB800)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { 
                                    scope.launch { 
                                        val result = MediaSaver.saveImage(context, bitmap, MediaSaver.SaveLocation.GALLERY)
                                        // عرض رسالة نجاح/فشل
                                    }
                                    showSaveOptions = false 
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))) {
                                    Text("️ معرض الصور")
                                }
                                Button(onClick = { 
                                    scope.launch { 
                                        MediaSaver.saveImage(context, bitmap, MediaSaver.SaveLocation.APP_FOLDER)
                                    }
                                    showSaveOptions = false 
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))) {
                                    Text("📂 مجلد التطبيق")
                                }
                                Button(onClick = { 
                                    scope.launch { 
                                        MediaSaver.saveImage(context, bitmap, MediaSaver.SaveLocation.DRIVE)
                                    }
                                    showSaveOptions = false 
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))) {
                                    Text("☁️ Google Drive")
                                }
                            }
                        },
                        confirmButton = {},
                        containerColor = Color(0xFF1A1A1A)
                    )
                }
            }
        }
    }
}
