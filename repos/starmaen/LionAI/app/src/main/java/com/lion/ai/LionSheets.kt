package com.lion.ai

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    sheetState: SheetState,
    autoRoute: Boolean,
    selectedProvider: String,
    modelFor: (ProviderInfo) -> String,
    onDismiss: () -> Unit,
    onChooseAuto: () -> Unit,
    onChooseProvider: (ProviderInfo) -> Unit,
    onOpenSettings: () -> Unit
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
        "اختر الموديل",
        color = Color(0xFFFFB800),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    Text(
        "🧠 تلقائي = العقل المركزي يختار الأنسب لكل سؤال ويستبعد ما لا يعمل. " +
            "🔒 يدوي = حرية الاختيار لك، ولا يبدّل مزودك تلقائياً إلا إذا فعّلت المساعدة من الإعدادات.",
        color = Color(0xFF9A9A9A),
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChooseAuto() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = autoRoute,
            onClick = onChooseAuto,
            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFB800))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text("🧠 تلقائي (العقل المركزي)", color = Color(0xFFFFB800), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "يختار الأنسب لكل سؤال (سريع، برمجة، صور...) ويجرّب بديلاً إذا فشل، والحوار يبقى واحداً",
                color = Color(0xFF9A9A9A),
                fontSize = 11.sp
            )
        }
    }
    PROVIDERS.filter { it.group == ProviderGroup.API }.forEach { info ->
        val configured = modelFor(info)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChooseProvider(info) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = !autoRoute && selectedProvider == info.id,
                onClick = { onChooseProvider(info) },
                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFB800))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    info.label + (if (info.webviewUrl != null) "  🌐" else if (info.free) "  🟢" else "  🔵"),
                    color = Color.White,
                    fontSize = 14.sp
                )
                Text(
                    configured,
                    color = Color(0xFF9A9A9A),
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }
    HorizontalDivider(color = Color(0xFF2A2A2A))
    TextButton(
        onClick = onOpenSettings,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Text(
            "⚙️ الإعدادات والمفاتيح وحدود الاستخدام",
            color = Color(0xFFFFB800),
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
