package com.lion.ai

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationStudioScreen(
    onBack: () -> Unit,
    onPickImage: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(0) }
    
    // متغيرات توليد الصور
    var prompt by remember { mutableStateOf("") }
    var selectedStyle by remember { mutableStateOf(MediaProviders.GenerationStyle.REALISTIC) }
    var selectedRatio by remember { mutableStateOf(MediaProviders.AspectRatio.SQUARE) }
    var negativePrompt by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    
    // متغيرات النتيجة
    var generatedImage by remember { mutableStateOf<Bitmap?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    
    // متغيرات التعديل
    var brightness by remember { mutableStateOf(0) }
    var contrast by remember { mutableStateOf(0) }
    var saturation by remember { mutableStateOf(0) }
    var selectedFilter by remember { mutableStateOf(ImageEditorTools.FilterType.NONE) }
    var editedImage by remember { mutableStateOf<Bitmap?>(null) }
    
    // معرض الصور
    var galleryImages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }

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
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // التبويبات الرئيسية
            TabRow(
                selectedTabIndex = activeTab,
                containerColor = Color(0xFF1A1A1A),
                contentColor = Color(0xFFFFB800)
            ) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                    Text("🎨 صور", modifier = Modifier.padding(16.dp))
                }
                Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                    Text("🖼️ المعرض", modifier = Modifier.padding(16.dp))
                }
            }

            when (activeTab) {
                0 -> ImageGenerationTabContent(
                    prompt = prompt,
                    onPromptChange = { prompt = it; errorMessage = null },
                    selectedStyle = selectedStyle,
                    onStyleChange = { selectedStyle = it },
                    selectedRatio = selectedRatio,
                    onRatioChange = { selectedRatio = it },
                    negativePrompt = negativePrompt,
                    onNegativePromptChange = { negativePrompt = it },
                    showAdvanced = showAdvanced,
                    onShowAdvancedChange = { showAdvanced = it },
                    generatedImage = generatedImage ?: editedImage,
                    isGenerating = isGenerating,
                    errorMessage = errorMessage,
                    saveMessage = saveMessage,
                    onGenerate = {
                        if (prompt.isBlank()) {
                            errorMessage = "️ اكتب وصفاً للصورة"
                            return@ImageGenerationTabContent
                        }
                        isGenerating = true
                        errorMessage = null
                        saveMessage = null
                        scope.launch {
                            val img = MediaProviders.generateImage(
                                prompt, selectedStyle, selectedRatio,
                                negativePrompt = negativePrompt
                            )
                            generatedImage = img
                            editedImage = null
                            isGenerating = false
                            if (img != null) {
                                galleryImages = galleryImages + img
                                saveMessage = "✅ تم التوليد بنجاح! استخدم أدوات التعديل أدناه"
                            } else {
                                errorMessage = "❌ فشل التوليد، تحقق من الإنترنت"
                            }
                        }
                    },
                    onPickImage = onPickImage,
                    onSave = { location ->
                        val img = generatedImage ?: editedImage ?: return@ImageGenerationTabContent
                        scope.launch {
                            val result = MediaSaver.saveImage(context, img, location)
                            saveMessage = if (result.isSuccess) "✅ تم الحفظ!" else "❌ فشل الحفظ"
                        }
                    },
                    brightness = brightness,
                    onBrightnessChange = { 
                        brightness = it
                        applyEdits(generatedImage, brightness, contrast, saturation, selectedFilter)?.let { editedImage = it }
                    },
                    contrast = contrast,
                    onContrastChange = { 
                        contrast = it
                        applyEdits(generatedImage, brightness, contrast, saturation, selectedFilter)?.let { editedImage = it }
                    },
                    saturation = saturation,
                    onSaturationChange = { 
                        saturation = it
                        applyEdits(generatedImage, brightness, contrast, saturation, selectedFilter)?.let { editedImage = it }
                    },
                    selectedFilter = selectedFilter,
                    onFilterChange = { 
                        selectedFilter = it
                        applyEdits(generatedImage, brightness, contrast, saturation, selectedFilter)?.let { editedImage = it }
                    },
                    onRotate = {
                        val img = editedImage ?: generatedImage ?: return@ImageGenerationTabContent
                        editedImage = ImageEditorTools.rotateBitmap(img, 90f)
                    },
                    onFlipH = {
                        val img = editedImage ?: generatedImage ?: return@ImageGenerationTabContent
                        editedImage = ImageEditorTools.flipHorizontal(img)
                    },
                    onFlipV = {
                        val img = editedImage ?: generatedImage ?: return@ImageGenerationTabContent
                        editedImage = ImageEditorTools.flipVertical(img)
                    },
                    onUpscale = {
                        val img = editedImage ?: generatedImage ?: return@ImageGenerationTabContent
                        editedImage = ImageEditorTools.upscaleBitmap(img, 2)
                        saveMessage = "✅ تم تكبير الصورة 2x"
                    },
                    onReset = {
                        editedImage = null
                        brightness = 0
                        contrast = 0
                        saturation = 0
                        selectedFilter = ImageEditorTools.FilterType.NONE
                    }
                )
                1 -> GalleryTabContent(
                    images = galleryImages,
                    onSelect = { 
                        generatedImage = it
                        editedImage = null
                        activeTab = 0 
                    }
                )
            }
        }
    }

    // رسالة Snackbar
    saveMessage?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(3000)
            saveMessage = null
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (message.contains("✅")) Color(0xFF2E4A2E) else Color(0xFF4A2E2E)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    message,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// دالة مساعدة لتطبيق التعديلات
private fun applyEdits(
    original: Bitmap?,
    brightness: Int,
    contrast: Int,
    saturation: Int,
    filter: ImageEditorTools.FilterType
): Bitmap? {
    if (original == null) return null
    var result = original
    if (filter != ImageEditorTools.FilterType.NONE) {
        result = ImageEditorTools.applyFilter(result, filter)
    }
    if (brightness != 0) result = ImageEditorTools.adjustBrightness(result, brightness)
    if (contrast != 0) result = ImageEditorTools.adjustContrast(result, contrast)
    if (saturation != 0) result = ImageEditorTools.adjustSaturation(result, saturation)
    return result
}

// ============ تبويب توليد الصور ============
@Composable
private fun ImageGenerationTabContent(
    prompt: String,
    onPromptChange: (String) -> Unit,
    selectedStyle: MediaProviders.GenerationStyle,
    onStyleChange: (MediaProviders.GenerationStyle) -> Unit,
    selectedRatio: MediaProviders.AspectRatio,
    onRatioChange: (MediaProviders.AspectRatio) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    showAdvanced: Boolean,
    onShowAdvancedChange: (Boolean) -> Unit,
    generatedImage: Bitmap?,
    isGenerating: Boolean,
    errorMessage: String?,
    saveMessage: String?,
    onGenerate: () -> Unit,
    onPickImage: () -> Unit,
    onSave: (MediaSaver.SaveLocation) -> Unit,
    brightness: Int,
    onBrightnessChange: (Int) -> Unit,
    contrast: Int,
    onContrastChange: (Int) -> Unit,
    saturation: Int,
    onSaturationChange: (Int) -> Unit,
    selectedFilter: ImageEditorTools.FilterType,
    onFilterChange: (ImageEditorTools.FilterType) -> Unit,
    onRotate: () -> Unit,
    onFlipH: () -> Unit,
    onFlipV: () -> Unit,
    onUpscale: () -> Unit,
    onReset: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        // حقل الإدخال
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            label = { Text("✍️ اكتب فكرة الصورة (عربي أو إنجليزي)...") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFFFB800),
                unfocusedBorderColor = Color(0xFF2A2A2A),
                focusedTextColor = Color.White,
                cursorColor = Color(0xFFFFB800)
            ),
            textStyle = LocalTextStyle.current.copy(color = Color.White)
        )

        Spacer(Modifier.height(12.dp))

        // اختيار النمط
        Text("🎨 النمط الفني:", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            items(MediaProviders.GenerationStyle.values()) { style ->
                FilterChip(
                    selected = selectedStyle == style,
                    onClick = { onStyleChange(style) },
                    label = { Text(style.name, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFFFB800),
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // اختيار الأبعاد
        Text("📐 الأبعاد:", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            items(MediaProviders.AspectRatio.values()) { ratio ->
                FilterChip(
                    selected = selectedRatio == ratio,
                    onClick = { onRatioChange(ratio) },
                    label = { Text(ratio.label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFFFB800),
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        // الخيارات المتقدمة
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚙️ خيارات متقدمة:", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold)
            IconButton(onClick = { onShowAdvancedChange(!showAdvanced) }) {
                Icon(
                    if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    "خيارات",
                    tint = Color(0xFFFFB800)
                )
            }
        }

        if (showAdvanced) {
            OutlinedTextField(
                value = negativePrompt,
                onValueChange = onNegativePromptChange,
                label = { Text("🚫 ما لا تريده في الصورة...") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFFB800),
                    unfocusedBorderColor = Color(0xFF2A2A2A)
                ),
                textStyle = LocalTextStyle.current.copy(color = Color.White)
            )
        }

        Spacer(Modifier.height(16.dp))

        // زر التوليد
        Button(
            onClick = onGenerate,
            enabled = !isGenerating,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800)),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (isGenerating) {
                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("جاري التوليد...", color = Color.Black, fontWeight = FontWeight.Bold)
            } else {
                Text("✨ توليد الصورة", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        if (errorMessage != null) {
            Text(errorMessage ?: "", color = Color(0xFFE94560), modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(20.dp))

        // عرض الصورة المولدة
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

            Spacer(Modifier.height(12.dp))

            // أدوات التعديل
            Text("🛠️ أدوات التعديل:", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold)
            
            // أزرار التعديل السريعة
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onRotate) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.RotateRight, "تدوير", tint = Color(0xFFFFB800))
                        Text("تدوير", fontSize = 10.sp, color = Color.White)
                    }
                }
                IconButton(onClick = onFlipH) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Flip, "قلب", tint = Color(0xFFFFB800))
                        Text("قلب", fontSize = 10.sp, color = Color.White)
                    }
                }
                IconButton(onClick = onUpscale) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.ZoomIn, "تكبير", tint = Color(0xFFFFB800))
                        Text("2x", fontSize = 10.sp, color = Color.White)
                    }
                }
                IconButton(onClick = onReset) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Refresh, "إعادة", tint = Color(0xFFFFB800))
                        Text("إعادة", fontSize = 10.sp, color = Color.White)
                    }
                }
            }

            // الفلاتر
            Text("🎭 الفلاتر:", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                items(ImageEditorTools.FilterType.values()) { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { onFilterChange(filter) },
                        label = { Text(filter.name, fontSize = 11.sp) }
                    )
                }
            }

            // السطوع
            Text("☀️ السطوع: $brightness", color = Color.White, fontSize = 13.sp)
            Slider(
                value = brightness.toFloat(),
                onValueChange = { onBrightnessChange(it.toInt()) },
                valueRange = -100f..100f,
                colors = SliderDefaults.colors(thumbColor = Color(0xFFFFB800), activeTrackColor = Color(0xFFFFB800))
            )

            // التباين
            Text(" التباين: $contrast", color = Color.White, fontSize = 13.sp)
            Slider(
                value = contrast.toFloat(),
                onValueChange = { onContrastChange(it.toInt()) },
                valueRange = -100f..100f,
                colors = SliderDefaults.colors(thumbColor = Color(0xFFFFB800), activeTrackColor = Color(0xFFFFB800))
            )

            // التشبع
            Text("🎨 التشبع: $saturation", color = Color.White, fontSize = 13.sp)
            Slider(
                value = saturation.toFloat(),
                onValueChange = { onSaturationChange(it.toInt()) },
                valueRange = -100f..100f,
                colors = SliderDefaults.colors(thumbColor = Color(0xFFFFB800), activeTrackColor = Color(0xFFFFB800))
            )

            Spacer(Modifier.height(12.dp))

            // أزرار الحفظ والمشاركة
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onSave(MediaSaver.SaveLocation.GALLERY) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E4A2E)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.PhotoLibrary, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("المعرض")
                }
                Button(
                    onClick = { onSave(MediaSaver.SaveLocation.APP_FOLDER) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("المجلد")
                }
                Button(
                    onClick = { onSave(MediaSaver.SaveLocation.DRIVE) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A2E4A)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Cloud, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Drive")
                }
            }
        }
    }
}

// ============ تبويب الفيديو ============
@Composable
private fun VideoGenerationTabContent(
    prompt: String,
    onPromptChange: (String) -> Unit
) {
    var isGenerating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "🎬 توليد فيديو بالذكاء الاصطناعي",
            color = Color(0xFFFFB800),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "نستخدم محركات مجانية (قد تستغرق بضع دقائق)",
            color = Color(0xFF7A7A7A),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))
        
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            label = { Text("صف مشهد الفيديو (بالإنجليزية لأفضل نتيجة)...") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFFB800)),
            textStyle = LocalTextStyle.current.copy(color = Color.White)
        )
        
        Spacer(Modifier.height(16.dp))
        
        Button(
            onClick = {
                if (prompt.isBlank()) {
                    message = "⚠️ اكتب وصفاً للفيديو"
                    return@Button
                }
                isGenerating = true
                scope.launch {
                    val result = Result.failure<String>(Exception("ميزة توليد الفيديو غير متوفرة حالياً"))
                    isGenerating = false
                    message = if (result.isSuccess) {
                        "✅ ${result.getOrNull()}"
                    } else {
                        " ${result.exceptionOrNull()?.message}"
                    }
                }
            },
            enabled = !isGenerating,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800)),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isGenerating) {
                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
            } else {
                Text("🎬 بدء توليد الفيديو", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        if (message != null) {
            Text(
                message ?: "",
                color = if ((message ?: "").contains("✅")) Color(0xFF4CAF50) else Color(0xFFE94560),
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

// ============ تبويب المعرض ============
@Composable
private fun GalleryTabContent(
    images: List<Bitmap>,
    onSelect: (Bitmap) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            "️ صورك المولدة في هذه الجلسة",
            color = Color(0xFFFFB800),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(Modifier.height(16.dp))
        
        if (images.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(300.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("لا توجد صور بعد. ابدأ بالتوليد!", color = Color(0xFF7A7A7A))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(images.size) { index ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.aspectRatio(1f),
                        onClick = { onSelect(images[index]) }
                    ) {
                        Image(
                            bitmap = images[index].asImageBitmap(),
                            contentDescription = "صورة ${index + 1}",
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                        )
                    }
                }
            }
        }
    }
}
