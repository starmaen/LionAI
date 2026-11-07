package com.lion.ai

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** المنصة = العائلة: كل مزودي OpenCode منصة واحدة، وغيرها منصة لكل مزود. تظهر المنصات الجديدة تلقائياً. */
private fun platformKey(info: ProviderInfo): String =
    if (info.id.startsWith("opencode")) "opencode" else info.id

private fun platformName(key: String, members: List<ProviderInfo>): String =
    if (key == "opencode") "OpenCode" else members.first().label

/** لون ثابت لكل منصة مشتق من اسمها، فتأخذ أي منصة مضافة لوناً مميزاً دون إعداد. */
private fun platformColor(key: String): Color {
    val hue = ((key.hashCode() % 360) + 360) % 360
    return Color.hsv(hue.toFloat(), 0.55f, 1f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    sheetState: SheetState,
    autoRoute: Boolean,
    selectedProvider: String,
    enabled: (String) -> Boolean,
    modelFor: (ProviderInfo) -> String,
    modelsFor: (ProviderInfo) -> List<String>,
    onDismiss: () -> Unit,
    onChooseAuto: () -> Unit,
    onChooseProvider: (ProviderInfo) -> Unit,
    onChooseModel: (ProviderInfo, String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val gold = Color(0xFFFFB800)
    val muted = Color(0xFF9A9A9A)
    val sheetContext = LocalContext.current
    val sheetPrefs = remember { SecurePrefs.get(sheetContext) }
    var refresh by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val refreshTick = refresh // قراءة تُعيد بناء القائمة بعد إضافة/حذف منصة
    val families = PROVIDERS
        .filter { it.supportsAppChat && enabled(it.id) }
        .groupBy { platformKey(it) }
    val initial: Set<String> =
        if (autoRoute) emptySet() else setOf(platformKey(providerInfo(selectedProvider)))
    var expanded by remember { mutableStateOf(initial) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A1A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp)
        ) {
            if (showAdd) {
                AddPlatformDialog(prefs = sheetPrefs, onDismiss = { showAdd = false }, onAdded = { refresh++ })
            }
            Text(
                "اختر المنصة والموديل",
                color = gold,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Text(
                "اضغط اسم المنصة لعرض موديلاتها، ثم اختر الموديل مباشرة. اليدوي لا يبدّل مزودك إلا إذا فعّلت المساعدة من الإعدادات.",
                color = muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (autoRoute) Color(0xFF2B2410) else Color(0xFF222222))
                    .clickable { onChooseAuto() }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = autoRoute,
                    onClick = onChooseAuto,
                    colors = RadioButtonDefaults.colors(selectedColor = gold)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("🧠 تلقائي (العقل المركزي)", color = gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "يختار الأنسب لكل سؤال ويجرّب بديلاً إذا فشل، والحوار يبقى واحداً",
                        color = muted,
                        fontSize = 11.sp
                    )
                }
            }
            families.forEach { (key, members) ->
                val color = platformColor(key)
                val open = key in expanded
                val selectedHere = !autoRoute && members.any { it.id == selectedProvider }
                val rowsCount = members.sumOf { info ->
                    val models = modelsFor(info)
                    if (models.isEmpty()) 1 else (listOf(modelFor(info)) + models).distinct().size
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF202020))
                        .animateContentSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = if (open) expanded - key else expanded + key }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(
                            Modifier
                                .width(4.dp)
                                .height(22.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(color)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                platformName(key, members),
                                color = color,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (selectedHere) "✓ مختارة · $rowsCount موديل" else "$rowsCount موديل",
                                color = if (selectedHere) color else muted,
                                fontSize = 11.sp
                            )
                        }
                        Text(if (open) "▴" else "▾", color = color, fontSize = 16.sp)
                    }
                    if (open) {
                        members.forEach { info ->
                            val configured = modelFor(info)
                            val models = modelsFor(info)
                            val options = if (models.isEmpty()) {
                                listOf(configured)
                            } else {
                                (listOf(configured) + models).distinct().filter { it.isNotBlank() }
                            }
                            if (members.size > 1) {
                                Text(
                                    info.shortName,
                                    color = color.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 36.dp, end = 14.dp, top = 6.dp)
                                )
                            }
                            options.forEach { model ->
                                val picked = !autoRoute && selectedProvider == info.id && model == configured
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (models.isEmpty()) onChooseProvider(info) else onChooseModel(info, model)
                                        }
                                        .padding(start = 26.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = picked,
                                        onClick = {
                                            if (models.isEmpty()) onChooseProvider(info) else onChooseModel(info, model)
                                        },
                                        colors = RadioButtonDefaults.colors(selectedColor = color)
                                    )
                                    Text(
                                        model,
                                        color = if (picked) Color.White else Color(0xFFD0D0D0),
                                        fontSize = 13.sp,
                                        fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        if (key.startsWith(UserPlatforms.PREFIX)) {
                            if (deleteId == key) {
                                Row {
                                    TextButton(onClick = {
                                        UserPlatforms.remove(sheetPrefs, key)
                                        runCatching { LlmClient.reloadOverrides(sheetPrefs) }
                                        deleteId = null
                                        refresh++
                                    }) { Text("تأكيد حذف المنصة", color = Color(0xFFFF9A9A), fontSize = 12.sp) }
                                    TextButton(onClick = { deleteId = null }) { Text("إلغاء", color = muted, fontSize = 12.sp) }
                                }
                            } else {
                                TextButton(onClick = { deleteId = key }) { Text("🗑️ حذف هذه المنصة", color = Color(0xFFFF9A9A), fontSize = 12.sp) }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            Button(
                onClick = { showAdd = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
            ) { Text("➕ إضافة منصة أو مزود", color = Color.White) }
            HorizontalDivider(color = Color(0xFF2A2A2A), modifier = Modifier.padding(top = 8.dp))
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    "⚙️ الإعدادات والمفاتيح وحدود الاستخدام",
                    color = gold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebPickerSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit
) {
ModalBottomSheet(
onDismissRequest = onDismiss,
sheetState = sheetState,
containerColor = Color(0xFF1A1A1A)
        ) {
Column(
    modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(bottom = 12.dp)
) {
    Text(
        "دردشات الويب المجانية (بدون مفتاح)",
        color = Color(0xFFFFB800),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    PROVIDERS.filter { it.group == ProviderGroup.WEB }.forEach { info ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onDismiss(); info.webviewUrl?.let(onOpen) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🌐", fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(info.label, color = Color.White, fontSize = 14.sp)
                Text(info.tagline, color = Color(0xFF9A9A9A), fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaPickerSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit
) {
ModalBottomSheet(
onDismissRequest = onDismiss,
sheetState = sheetState,
containerColor = Color(0xFF1A1A1A)
        ) {
Column(
    modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(bottom = 12.dp)
) {
    Text(
        "استوديو الوسائط المجاني (بدون مفتاح)",
        color = Color(0xFFFFB800),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    val mediaSections = listOf(
        "🖼️ توليد وتعديل الصور" to "image",
        "🎬 فيديو وأنيميشن" to "video",
        "🎵 صوت وموسيقى" to "audio"
    )
    mediaSections.forEach { (sectionTitle, cat) ->
        val items = PROVIDERS.filter { it.category == cat }
        if (items.isNotEmpty()) {
            Text(
                sectionTitle,
                color = Color(0xFF7CC47C),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            items.forEach { info ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismiss(); info.webviewUrl?.let(onOpen) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("▫️", fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(info.label, color = Color.White, fontSize = 14.sp)
                        Text(info.tagline, color = Color(0xFF9A9A9A), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF2A2A2A))
        }
    }
}
        }
}
