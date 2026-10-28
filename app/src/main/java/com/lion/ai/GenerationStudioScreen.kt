package com.lion.ai

import android.content.Context
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationStudioScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var selectedStyle by remember { mutableStateOf(MediaProviders.GenerationStyle.REALISTIC) }
    var generatedImage by remember { mutableStateOf<Bitmap?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showSaveOptions by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var showImagePicker by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🎨 استوديو التوليد", color = Color(0xFFFFB800)) },
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
            TabRow(
                selectedTabIndex = activeTab,
                containerColor = Color(0xFF1A1A1A),
                contentColor = Color(0xFFFFB800)
            ) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                    Text("🎨 صور", modifier = Modifier.padding(16.dp))
                }
                Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                    Text("🎬 فيديو", modifier = Modifier.padding(16.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            if (activeTab == 0) {
                // تبويب الصور
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

                Text("اختر النمط:", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
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

                    OutlinedButton(
                        onClick = { showSaveOptions = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB800))
                    ) {
                        Icon(Icons.Filled.Download, "حفظ", modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("حفظ الصورة")
                    }
                }
            } else {
                // تبويب الفيديو
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🎬 فيديو ستوديو - قريباً", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("جاري دمج محركات توليد الفيديو...", color = Color(0xFF7A7A7A))
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            label = { Text("وصف المشهد...", color = Color(0xFF7A7A7A)) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFFB800)),
                            textStyle = LocalTextStyle.current.copy(color = Color.White)
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { },
                            enabled = false,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🔒 قيد التطوير")
                        }
                    }
                }
            }
        }
    }

    // نافذة اختيار مكان الحفظ
    if (showSaveOptions && generatedImage != null) {
        AlertDialog(
            onDismissRequest = { showSaveOptions = false },
            title = { Text("اختر مكان الحفظ", color = Color(0xFFFFB800)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val result = MediaSaver.saveImage(context, generatedImage!!, MediaSaver.SaveLocation.GALLERY)
                                saveMessage = if (result.isSuccess) "✅ تم الحفظ في المعرض بنجاح!" else "❌ فشل الحفظ"
                            }
                            showSaveOptions = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🖼️ معرض الصور", color = Color.White) }

                    Button(
                        onClick = {
                            scope.launch {
                                val res2 = MediaSaver.saveImage(context, generatedImage!!, MediaSaver.SaveLocation.APP_FOLDER)
saveMessage = if (res2.isSuccess) "✅ تم الحفظ في مجلد التطبيق" else "❌ فشل الحفظ"
                            }
                            showSaveOptions = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📂 مجلد التطبيق", color = Color.White) }

                    Button(
                        onClick = {
                            scope.launch {
                                val res3 = MediaSaver.saveImage(context, generatedImage!!, MediaSaver.SaveLocation.DRIVE)
saveMessage = if (res3.isSuccess) "✅ جاري الرفع إلى Drive" else "❌ تأكد من ربط Drive في الإعدادات"
                            }
                            showSaveOptions = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("☁️ Google Drive", color = Color.White) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSaveOptions = false }) {
                    Text("إلغاء", color = Color(0xFFFFB800))
                }
            },
            containerColor = Color(0xFF1A1A1A)
        )

        // عرض رسالة نجاح الحفظ
        if (saveMessage != null) {
            LaunchedEffect(saveMessage) {
                kotlinx.coroutines.delay(3000)
                saveMessage = null
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (saveMessage.contains("✅")) Color(0xFF2E4A2E) else Color(0xFF4A2E2E)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        saveMessage,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

    }
}
