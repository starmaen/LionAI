package com.lion.ai

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** مجموعات المزودات التي تظهر كأزرار تحت صندوق الكتابة. */
enum class HubCategory(val icon: String, val label: String, val hint: String) {
    INDEPENDENT("🔑", "مستقل", "مزودات API مباشرة بمفاتيحها"),
    LOCAL("🖥️", "داخلي", "الخادم المحلي داخل التطبيق"),
    EXTERNAL("☁️", "خارجي", "بوابة الخادم الخارجي (Cloudflare/Worker)"),
    WEB("🌐", "ويب", "دردشات الويب المجانية بدون مفتاح"),
    MEDIA("🎨", "ميديا", "توليد الصور والصوت والفيديو")
}

private val Gold = Color(0xFFFFB800)
private val Ink = Color(0xFF141414)
private val Card = Color(0xFF1E1E1E)
private val Muted = Color(0xFF9A9A9A)

/** نوع المزود الفعلي المستخدم الآن، لعرض أيقونة صغيرة بجانب اسمه. */
fun hubKindOf(provider: String, prefs: SharedPreferences): HubCategory {
    if (provider == RoutePolicy.LOCAL_PROXY) return HubCategory.LOCAL
    val info = providerInfo(provider)
    if (info.group == ProviderGroup.WEB) return HubCategory.WEB
    if (info.group == ProviderGroup.MEDIA) return HubCategory.MEDIA
    return if (WebGateway.supports(provider) && WebGateway.shouldRoute(provider)) {
        HubCategory.EXTERNAL
    } else {
        HubCategory.INDEPENDENT
    }
}

/** زر ناعم على شكل كبسولة، يُستخدم لأزرار المجموعات والأوضاع. */
@Composable
fun HubPill(
    text: String,
    selected: Boolean = false,
    accent: Color = Gold,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) accent.copy(alpha = 0.20f) else Color(0xFF1F1F1F),
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.65f) else Color(0xFF2C2C2C),
                shape = RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
    ) {
        Text(
            text,
            color = if (selected) accent else Color(0xFFD8D8D8),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

/** شريط الأوضاع والمجموعات أسفل صندوق الكتابة. */
@Composable
fun HubChipsRow(
    mode: ChatMode,
    autoRoute: Boolean,
    currentKind: HubCategory?,
    loading: Boolean,
    onSelectMode: (ChatMode) -> Unit,
    onToggleAuto: () -> Unit,
    onOpenCategory: (HubCategory) -> Unit,
    onFollowUp: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // أيقونة واحدة للوضع تظهر قائمة منسدلة عند اللمس
        var modeMenuOpen by remember { mutableStateOf(false) }
        HubPill(
            modifier = Modifier.size(44.dp),
            selected = false,
            onClick = { modeMenuOpen = !modeMenuOpen }
        ) {
            Icon(
                if (modeMenuOpen) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                tint = Color(0xFFFFB800),
                contentDescription = "اختيار وضع المحادثة"
            )
        }
        DropdownMenu(
            expanded = modeMenuOpen,
            onDismissRequest = { modeMenuOpen = false },
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .offset { offset ->
                    offset.copy(y = 56.dp)
                }
        ) {
            DropdownMenuItem(onClick = { onSelectMode(mode.copy(id = "general")); modeMenuOpen = false }) {
                Text("💬 عام", color = if (mode.id == "general") Color(0xFFFFB800) else Color.White, fontSize = 12.sp)
            }
            DropdownMenuItem(onClick = { onSelectMode(mode.copy(id = "code")); modeMenuOpen = false }) {
                Text("🛠️ برمجة", color = if (mode.id == "code") Color(0xFFFFB800) else Color.White, fontSize = 12.sp)
            }
            DropdownMenuItem(onClick = { onSelectMode(mode.copy(id = "learn")); modeMenuOpen = false }) {
                Text("🎓 تعلم", color = if (mode.id == "learn") Color(0xFFFFB800) else Color.White, fontSize = 12.sp)
            }
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(18.dp)
                .background(Color(0xFF333333))
        )
        HubPill(
            if (autoRoute) "🧠 مزود تلقائي" else "✋ مزود يدوي",
            selected = autoRoute,
            onClick = onToggleAuto
        )
        if (loading) {
            HubPill("⚡ تعقيب/طارئ", selected = true, accent = Color(0xFF4C8DFF), onClick = onFollowUp)
        }
        HubCategory.entries.forEach { category ->
            HubPill(
                "${category.icon} ${category.label}",
                selected = currentKind == category,
                onClick = { onOpenCategory(category) }
            )
        }
        HubPill("⚙️", selected = false, onClick = onOpenSettings)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderHubSheet(
    sheetState: SheetState,
    category: HubCategory,
    prefs: SharedPreferences,
    selectedProvider: String,
    gatewayMode: String,
    gatewayEnabled: Boolean,
    onDismiss: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onOpenWeb: (String) -> Unit,
    onGatewayEnabled: (Boolean) -> Unit,
    onGatewayMode: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val independent = PROVIDERS.filter {
        it.group == ProviderGroup.API && it.id != RoutePolicy.LOCAL_PROXY && !it.custom
    }
    val local = PROVIDERS.filter { it.id == RoutePolicy.LOCAL_PROXY || it.id == RoutePolicy.CUSTOM }
    val web = PROVIDERS.filter { it.group == ProviderGroup.WEB }
    val media = PROVIDERS.filter { it.group == ProviderGroup.MEDIA }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF161616)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 18.dp)
        ) {
            Text(
                "${category.icon} ${category.label}",
                color = Gold,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(category.hint, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.size(10.dp))

            when (category) {
                HubCategory.EXTERNAL -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("تفعيل الخادم الخارجي", color = Color.White, fontSize = 14.sp)
                            Text(
                                if (gatewayEnabled) "الطلبات تمرّ عبر البوابة" else "البوابة متوقفة",
                                color = Muted,
                                fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = gatewayEnabled,
                            onCheckedChange = onGatewayEnabled,
                            colors = SwitchDefaults.colors(checkedThumbColor = Gold)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        HubPill("🔌 مباشر", gatewayMode == WebGateway.MODE_OFF) {
                            onGatewayMode(WebGateway.MODE_OFF)
                        }
                        HubPill("🌐 خادم أولاً", gatewayMode == WebGateway.MODE_AUTO) {
                            onGatewayMode(WebGateway.MODE_AUTO)
                        }
                        HubPill("☁️ خادم فقط", gatewayMode == WebGateway.MODE_ONLY) {
                            onGatewayMode(WebGateway.MODE_ONLY)
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "المزودات التي تعمل عبر البوابة:",
                        color = Color(0xFFB0B0B0),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.size(6.dp))
                    WebGateway.providers.forEach { provider ->
                        ProviderRow(
                            icon = "☁️",
                            label = provider.label,
                            subtitle = provider.defaultModel,
                            enabled = providerEnabled(prefs, provider.id),
                            selected = selectedProvider == provider.id,
                            onToggle = { setProviderEnabled(prefs, provider.id, it) },
                            onSelect = { onSelectProvider(provider.id) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = onOpenSettings) {
                            Text("⚙️ إعدادات البوابة والرابط", color = Gold, fontSize = 12.sp)
                        }
                    }
                }

                HubCategory.LOCAL -> {
                    local.forEach { info ->
                        ProviderRow(
                            icon = "🖥️",
                            label = info.label,
                            subtitle = info.tagline,
                            enabled = providerEnabled(prefs, info.id),
                            selected = selectedProvider == info.id,
                            onToggle = { setProviderEnabled(prefs, info.id, it) },
                            onSelect = { onSelectProvider(info.id) }
                        )
                    }
                    Text(
                        "الخادم المحلي يعمل تلقائياً على 127.0.0.1 ويوجّه الطلبات للمزود النشط.",
                        color = Muted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                HubCategory.INDEPENDENT -> {
                    independent.forEach { info ->
                        ProviderRow(
                            icon = if (info.free) "🟢" else "🔵",
                            label = info.label,
                            subtitle = info.shortName + " · " + info.defaultModel,
                            enabled = providerEnabled(prefs, info.id),
                            selected = selectedProvider == info.id,
                            onToggle = { setProviderEnabled(prefs, info.id, it) },
                            onSelect = { onSelectProvider(info.id) }
                        )
                    }
                }

                HubCategory.WEB, HubCategory.MEDIA -> {
                    val items = if (category == HubCategory.WEB) web else media
                    items.forEach { info ->
                        ProviderRow(
                            icon = if (category == HubCategory.WEB) "🌐" else "🎨",
                            label = info.label,
                            subtitle = info.tagline,
                            enabled = providerEnabled(prefs, info.id),
                            selected = selectedProvider == info.id,
                            onToggle = { setProviderEnabled(prefs, info.id, it) },
                            onSelect = { info.webviewUrl?.let(onOpenWeb) },
                            selectLabel = "فتح"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    icon: String,
    label: String,
    subtitle: String,
    enabled: Boolean,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
    onSelect: () -> Unit,
    selectLabel: String = "اختيار"
) {
    var on by remember(enabled) { mutableStateOf(enabled) }
    Surface(
        color = Card,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .border(
                width = 1.dp,
                color = if (selected) Gold.copy(alpha = 0.7f) else Color(0xFF2A2A2A),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    color = if (selected) Gold else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    color = Muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = on,
                onCheckedChange = {
                    on = it
                    onToggle(it)
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Gold)
            )
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onSelect) {
                Text(
                    if (selected) "نشط" else selectLabel,
                    color = if (selected) Gold else Color(0xFFD8D8D8),
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * صندوق الكتابة الجديد: حقل واسع، زر إرسال/إيقاف مربع، اسم المزود العامل
 * مع أيقونة مصدره، وصف الأزرار الناعمة للمجموعات أسفله.
 */
@Composable
internal fun LionComposer(
    attachment: Attachment?,
    imageAttachment: ImageAttachment?,
    input: String,
    onInputChange: (String) -> Unit,
    providerLabel: String,
    providerModel: String,
    providerKind: HubCategory?,
    mode: ChatMode,
    loading: Boolean,
    autoRoute: Boolean,
    canSend: Boolean,
    onEditImage: (ImageAttachment) -> Unit,
    onClearAttachment: () -> Unit,
    onQuickPrompt: (String) -> Unit,
    onAttachFile: () -> Unit,
    onVoice: () -> Unit,
    onChooseModel: () -> Unit,
    onSelectMode: (ChatMode) -> Unit,
    onToggleAuto: () -> Unit,
    onOpenCategory: (HubCategory) -> Unit,
    onStop: () -> Unit,
    onFollowUp: () -> Unit,
    onOpenSettings: () -> Unit,
    onSend: () -> Unit
) {
    val file = attachment
    val image = imageAttachment

    Surface(
        color = Color(0xFF101010),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .navigationBarsPadding()
        ) {
            if (file != null || image != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (file != null) "📎 ${file.name}" else "🖼️ ${image?.name.orEmpty()}",
                        color = Gold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (image != null) {
                        TextButton(onClick = { onEditImage(image) }) {
                            Text("✏️ تعديل", color = Gold, fontSize = 11.sp)
                        }
                    }
                    IconButton(onClick = onClearAttachment, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            "إزالة المرفق",
                            tint = Color(0xFFFF8C00),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                val quickPrompts = if (image != null) {
                    listOf(
                        "📝 صف الصورة" to "صف هذه الصورة بالتفصيل.",
                        "🔤 استخرج النص" to "استخرج كل النص الظاهر في هذه الصورة كما هو.",
                        "🌐 ترجم النص" to "استخرج النص الظاهر في الصورة ثم ترجمه إلى العربية.",
                        "🔍 حلّل" to "حلّل هذه الصورة: العناصر والألوان والسياق وأي تفاصيل مهمة."
                    )
                } else {
                    listOf(
                        "📄 لخّص" to "لخّص هذا الملف في نقاط واضحة.",
                        "🔎 استخرج الأهم" to "استخرج أهم المعلومات والأرقام من هذا الملف.",
                        "🌐 ترجم" to "ترجم محتوى هذا الملف إلى العربية.",
                        "🛠 راجع" to "راجع هذا الملف وأخبرني بالأخطاء والتحسينات الممكنة."
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    quickPrompts.forEach { (label, prompt) ->
                        HubPill(label) { onQuickPrompt(prompt) }
                    }
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Surface(
                    color = Color(0xFF111111),
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (input.isNotBlank()) Gold.copy(alpha = 0.60f) else Color(0xFF2A2A2A),
                            RoundedCornerShape(26.dp)
                        )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                        // حقل النص وحده في الأعلى، باتجاه RTL صحيح حتى يعمل المؤشر
                        // والمسح والإكمال من آخر النص كما في أي حقل عربي طبيعي.
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 42.dp)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (input.isEmpty()) {
                                    Text(
                                        "اكتب رسالتك...",
                                        color = Color(0xFF6E6E6E),
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                BasicTextField(
                                    value = input,
                                    onValueChange = onInputChange,
                                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                                    cursorBrush = SolidColor(Gold),
                                    maxLines = 5,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(Modifier.size(2.dp))
                        // الإرسال والميكروفون في الصف السفلي بمحاذاة اسم المزود.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledIconButton(
                                onClick = { if (loading) onStop() else onSend() },
                                enabled = loading || canSend,
                                modifier = Modifier.size(40.dp),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (loading) Color(0xFF3A1F1F) else Gold,
                                    contentColor = if (loading) Color(0xFFFF7A7A) else Color(0xFF0D0D0D),
                                    disabledContainerColor = Color(0xFF2A2A2A),
                                    disabledContentColor = Color(0xFF6E6E6E)
                                )
                            ) {
                                Icon(
                                    if (loading) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                                    if (loading) "إيقاف" else "إرسال",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = onVoice, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    Icons.Filled.Mic,
                                    "مكرفون المساعد",
                                    tint = Color(0xFFD0D0D0),
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Surface(
                                color = Color(0xFF1E1E1E),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier
                                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(50))
                                    .clickable(onClick = onChooseModel)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (providerKind != null) {
                                        Text(providerKind.icon, fontSize = 9.sp)
                                        Spacer(Modifier.width(3.dp))
                                    }
                                    Text(
                                        providerLabel,
                                        color = Gold,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 60.dp)
                                    )
                                    if (providerModel.isNotBlank()) {
                                        Text(
                                            " · " + providerModel,
                                            color = Color(0xFF9A9A9A),
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 96.dp)
                                        )
                                    }
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        "اختيار الموديل",
                                        tint = Gold,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(2.dp))
                            IconButton(onClick = onAttachFile, modifier = Modifier.size(30.dp)) {
                                Icon(
                                    Icons.Filled.Add,
                                    "إرفاق ملف",
                                    tint = Color(0xFFD0D0D0),
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.size(6.dp))
            HubChipsRow(
                mode = mode,
                autoRoute = autoRoute,
                currentKind = providerKind,
                loading = loading,
                onSelectMode = onSelectMode,
                onToggleAuto = onToggleAuto,
                onOpenCategory = onOpenCategory,
                onFollowUp = onFollowUp,
                onOpenSettings = onOpenSettings
            )
        }
    }
}
