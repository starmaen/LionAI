package com.lion.ai

import android.content.Context
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** مجموعات المزودات التي تظهر كأزرار تحت صندوق الكتابة. */
enum class HubCategory(val icon: String, val label: String, val hint: String) {
    LION("🦁", "Lion Chat", "جلسات مواقع الويب نفسها داخل الشات"),
    INDEPENDENT("🔑", "مستقل", "مزودات API مباشرة بمفاتيحها"),
    LOCAL("🖥️", "محلي", "الخادم المحلي داخل التطبيق"),
    EXTERNAL("☁️", "خارجي", "بوابة الخادم الخارجي (Cloudflare/Worker)"),
    WEB("🌐", "ويب", "دردشات الويب المجانية بدون مفتاح"),
    MEDIA("🎨", "ميديا", "توليد الصور والصوت والفيديو")
}

private val Gold = Color(0xFFFFB800)
private val Card = Color(0xFF1E1E1E)
private val Muted = Color(0xFF9A9A9A)
private val Line = Color(0xFF2A2A2A)

/** نوع المزود الفعلي المستخدم الآن، لعرض أيقونة صغيرة بجانب اسمه. */
fun hubKindOf(provider: String, prefs: SharedPreferences, via: String = Channel.AUTO): HubCategory {
    if (provider == RoutePolicy.LOCAL_PROXY || via == Channel.LOCAL) return HubCategory.LOCAL
    val info = providerInfo(provider)
    if (WebChatRuntime.handles(provider)) return HubCategory.LION
    if (info.group == ProviderGroup.MEDIA) return HubCategory.MEDIA
    if (via == Channel.GATEWAY) return HubCategory.EXTERNAL
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
    channelSummary: String = "",
    onOpenMode: () -> Unit,
    onToggleAuto: () -> Unit,
    onOpenCategory: (HubCategory) -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // أيقونة محادثة واحدة: تفتح الوضعين (عام / المطوّر) للانتقاء.
        HubPill(
            text = "💬 ${mode.short} ▾",
            selected = true,
            accent = Color(mode.color),
            onClick = onOpenMode
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(18.dp)
                .background(Color(0xFF333333))
        )
        HubPill(
            (if (autoRoute) "🧠 تلقائي" else "✋ يدوي") +
                (if (channelSummary.isNotBlank()) " · $channelSummary" else ""),
            selected = autoRoute,
            onClick = onToggleAuto
        )
        // اختيار المزود من صندوق الكتابة ومن ⚙️ الإعدادات؛ تبقى «ميديا» فقط كمدخل مستقل للتوليد.
        HubCategory.entries.filter { it == HubCategory.MEDIA }
            .forEach { category ->
            HubPill(
                "${category.icon} ${category.label}",
                selected = currentKind == category,
                accent = if (category == HubCategory.LION) Color(0xFFFF6D00) else Gold,
                onClick = { onOpenCategory(category) }
            )
        }
        HubPill("⚙️", selected = false, onClick = onOpenSettings)
    }
}

/** بطاقة عامة بعنوان ووصف، تُستخدم لتقسيم محتوى النافذة. */
@Composable
private fun HubCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Surface(
        color = Card,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/** صف مفتاح تبديل داخل النافذة (قناة تنفيذ أو خيار عام). */
@Composable
private fun ToggleRow(
    icon: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 15.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 9.5.sp, lineHeight = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Gold)
        )
    }
}

/**
 * قنوات التنفيذ الثلاث. مستقلة تماماً عن اختيار المزود: يمكن تشغيل المزود
 * مباشرة، أو عبر الخادم المحلي، أو عبر الخادم الخارجي، أو عبر أي تركيبة منها.
 */
@Composable
private fun ChannelCard(
    prefs: SharedPreferences,
    provider: String,
    onChanged: () -> Unit
) {
    var direct by remember { mutableStateOf(RoutePolicy.channelEnabled(prefs, Channel.DIRECT)) }
    var gateway by remember { mutableStateOf(RoutePolicy.channelEnabled(prefs, Channel.GATEWAY)) }
    var local by remember { mutableStateOf(RoutePolicy.channelEnabled(prefs, Channel.LOCAL)) }
    val gatewayConfig = remember { WebGateway.load(prefs) }
    HubCard(
        title = "🧭 مسار التنفيذ (قنوات مستقلة)",
        subtitle = "فعّل ما تشاء: يجرّب التطبيق القنوات بالترتيب حتى يرد أحدها، " +
            "فلا ينقطع الرد بتعطّل طريق واحد. اختيار المزود يبقى كما هو في كل الحالات."
    ) {
        ToggleRow(
            icon = Channel.icon(Channel.DIRECT),
            title = "مباشر",
            subtitle = "المزود بمفتاحه وعنوانه الأصلي",
            checked = direct
        ) {
            direct = it
            RoutePolicy.setChannelEnabled(prefs, Channel.DIRECT, it)
            onChanged()
        }
        ToggleRow(
            icon = Channel.icon(Channel.GATEWAY),
            title = "الخادم الخارجي",
            subtitle = if (gatewayConfig.configured && gatewayConfig.token.isNotBlank()) {
                "بوابة الويب — ${WebGateway.modeLabel(gatewayConfig.mode)}"
            } else {
                "بوابة الويب — تحتاج رابطاً ومفتاحاً من الإعدادات"
            },
            checked = gateway
        ) {
            gateway = it
            RoutePolicy.setChannelEnabled(prefs, Channel.GATEWAY, it)
            onChanged()
        }
        ToggleRow(
            icon = Channel.icon(Channel.LOCAL),
            title = "الخادم المحلي",
            subtitle = if (LocalAIProxy.isRunning()) {
                "يعمل الآن على ${LocalAIProxy.getBaseUrl()}"
            } else {
                "خادم 127.0.0.1 داخل التطبيق (متوقف حالياً)"
            },
            checked = local
        ) {
            local = it
            RoutePolicy.setChannelEnabled(prefs, Channel.LOCAL, it)
            onChanged()
        }
        val active = RoutePolicy.channels(prefs, provider)
        Text(
            if (active.isEmpty()) {
                "⚠️ كل القنوات مغلقة — سيُستخدم المباشر تلقائياً حتى لا تتعطل المحادثة."
            } else {
                "الترتيب الحالي: " + active.joinToString(" ← ") { Channel.label(it) }
            },
            color = if (active.isEmpty()) Color(0xFFFFB300) else Gold,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (provider.isNotBlank() && provider != RoutePolicy.LOCAL_PROXY && provider != RoutePolicy.CUSTOM) {
            Text(
                "مزودك الحالي: ${RoutePolicy.shortName(provider)}",
                color = Muted,
                fontSize = 9.5.sp
            )
        }
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
    onOpenSettings: () -> Unit,
    onChanged: () -> Unit = {}
) {
    val independent = PROVIDERS.filter {
        it.group == ProviderGroup.API && it.id != RoutePolicy.LOCAL_PROXY && !it.custom
    }
    val backends = PROVIDERS.filter {
        it.supportsAppChat && it.id != RoutePolicy.LOCAL_PROXY && it.id != RoutePolicy.CUSTOM &&
            (!WebChatRuntime.handles(it.id) || WebChatRuntime.available(prefs, it.id))
    }
    val web = PROVIDERS.filter { it.group == ProviderGroup.WEB }
    val media = PROVIDERS.filter { it.group == ProviderGroup.MEDIA }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    // سقف ديناميكي: النافذة لا تتجاوز 85% من الشاشة أبداً، وما زاد عن ذلك يُمرَّر
    // داخلياً. هذا يمنع «قصّ» الخيارات من الأسفل على الشاشات الصغيرة.
    val maxSheetHeight = (configuration.screenHeightDp * 0.85).dp
    var report by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var localRunning by remember { mutableStateOf(LocalAIProxy.isRunning()) }

    fun runCheck(waiting: String, block: () -> String) {
        if (busy) return
        busy = true
        report = waiting
        scope.launch(Dispatchers.IO) {
            val result = runCatching { block() }.getOrElse { "❌ ${it.message ?: it.javaClass.simpleName}" }
            withContext(Dispatchers.Main) {
                report = result
                localRunning = LocalAIProxy.isRunning()
                busy = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF161616)
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = maxSheetHeight)) {
            // رأس ثابت: يبقى ظاهراً مهما طال المحتوى.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
            ) {
                Text(
                    "${category.icon} ${category.label}",
                    color = Gold,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(category.hint, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF2A2A2A))
            }
            // المحتوى ديناميكي: يتمرّر داخلياً بدل أن يُقصّ من الأسفل.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp, top = 8.dp)
            ) {
                when (category) {
                    HubCategory.LION -> {
                        LionChatSettingsCard(prefs, onActivate = { id ->
                            onSelectProvider(id)
                            onChanged()
                        })
                    }
                    HubCategory.EXTERNAL -> {
                        ChannelCard(prefs, selectedProvider) { onChanged() }
                        HubCard(
                            title = "☁️ الخادم الخارجي",
                            subtitle = if (gatewayEnabled) "الطلبات تمرّ عبر البوابة" else "البوابة متوقفة"
                        ) {
                            ToggleRow(
                                icon = "☁️",
                                title = "تفعيل الخادم الخارجي",
                                subtitle = if (gatewayEnabled) "الطلبات تمرّ عبر البوابة" else "البوابة متوقفة",
                                checked = gatewayEnabled,
                                onCheckedChange = onGatewayEnabled
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
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
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        runCheck("⏳ أفحص الخادم الخارجي…") {
                                            val health = WebGateway.test(prefs)
                                            // فحص صحة الخادم لا يكفي: جرّب طلب محادثة فعلياً
                                            // حتى يظهر سبب فشل البوابة الحقيقي (مفتاح/موديل/شبكة).
                                            val probe = WebGateway.providers.firstOrNull {
                                                providerEnabled(prefs, it.id)
                                            }
                                            if (probe == null) health else {
                                                val model = (prefs.getString("model_${probe.id}", "") ?: "")
                                                    .ifBlank { WebGateway.defaultModel(probe.id) }
                                                health + "\n\n" +
                                                    WebGateway.testProvider(prefs, probe.id, model)
                                            }
                                        }
                                    }
                                ) { Text("🧪 اختبار الخادم", color = Gold, fontSize = 12.sp) }
                                TextButton(onClick = onOpenSettings) {
                                    Text("⚙️ إعدادات البوابة والرابط", color = Gold, fontSize = 12.sp)
                                }
                            }
                        }
                        if (report.isNotBlank()) ReportText(report)
                        Text(
                            "المزودات التي تعمل عبر البوابة:",
                            color = Color(0xFFB0B0B0),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Spacer(Modifier.height(6.dp))
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
                    }

                    HubCategory.LOCAL -> {
                        ChannelCard(prefs, selectedProvider) { onChanged() }
                        HubCard(
                            title = "🖥️ الخادم المحلي",
                            subtitle = if (localRunning) {
                                "يعمل على ${LocalAIProxy.getUrl()}"
                            } else {
                                "متوقف — شغّله ليكون مساراً احتياطياً داخل جهازك"
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        val port = LocalAIProxy.start(context, prefs)
                                        localRunning = LocalAIProxy.isRunning()
                                        report = if (port > 0) {
                                            "✅ الخادم يعمل على ${LocalAIProxy.getUrl()}"
                                        } else {
                                            "❌ تعذّر تشغيل الخادم المحلي على هذا الجهاز."
                                        }
                                    }
                                ) { Text("▶️ تشغيل", color = Gold, fontSize = 12.sp) }
                                TextButton(
                                    enabled = !busy && localRunning,
                                    onClick = {
                                        LocalAIProxy.stop()
                                        localRunning = false
                                        report = "⏹ أوقف الخادم المحلي."
                                    }
                                ) { Text("⏹ إيقاف", color = Gold, fontSize = 12.sp) }
                                TextButton(
                                    enabled = !busy,
                                    onClick = { runCheck("⏳ أفحص الخادم المحلي…") { LocalAIProxy.healthCheck() + "\n\n" + LocalAIProxy.selfTest(prefs) } }
                                ) { Text("🧪 فحص", color = Gold, fontSize = 12.sp) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    enabled = !busy,
                                    onClick = { runCheck("⏳ أفحص المزودات عبر الخادم المحلي…") { LocalAIProxy.providerCheck(prefs) } }
                                ) { Text("🔍 فحص المزودات", color = Gold, fontSize = 12.sp) }
                                TextButton(onClick = onOpenSettings) {
                                    Text("⚙️ إعدادات الخادم", color = Gold, fontSize = 12.sp)
                                }
                            }
                            Text(
                                "الخادم المحلي يستقبل طلبات OpenAI-Compatible على 127.0.0.1 ويوجّهها " +
                                    "لمزودك النشط مع سلسلة بدائل داخلية، فيبقى الرد متصلاً حتى لو تعطّل " +
                                    "المسار المباشر من التطبيق.",
                                color = Muted,
                                fontSize = 9.5.sp,
                                lineHeight = 13.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        if (report.isNotBlank()) ReportText(report)
                        Text(
                            "المزود الخلفي الذي ينفّذ الطلب داخل الخادم:",
                            color = Color(0xFFB0B0B0),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        val backend = LocalAIProxy.backendProvider(prefs)
                        backends.forEach { info ->
                            ProviderRow(
                                icon = if (info.free) "🟢" else "🔵",
                                label = info.label,
                                subtitle = info.shortName + " · " + info.defaultModel,
                                enabled = providerEnabled(prefs, info.id),
                                selected = backend == info.id,
                                onToggle = { setProviderEnabled(prefs, info.id, it) },
                                onSelect = {
                                    // تعيين الخلفي: يثبّت المزود داخل الخادم المحلي
                                    // ويجعله مزودك النشط معاً بلا تناقض.
                                    prefs.edit()
                                        .putString("provider", info.id)
                                        .putString("local_proxy_backend", info.id)
                                        .apply()
                                    onSelectProvider(info.id)
                                },
                                selectLabel = "تعيين"
                            )
                        }
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
                        if (category == HubCategory.WEB) {
                            LionChatSettingsCard(prefs, onActivate = { id ->
                                onSelectProvider(id)
                                onChanged()
                            })
                        }
                        val items = if (category == HubCategory.WEB) web else media
                        items.forEach { info ->
                            ProviderRow(
                                icon = if (category == HubCategory.WEB) "🌐" else "🎨",
                                label = info.label,
                                subtitle = info.tagline,
                                enabled = providerEnabled(prefs, info.id),
                                selected = selectedProvider == info.id,
                                onToggle = { setProviderEnabled(prefs, info.id, it) },
                                onSelect = if (category == HubCategory.WEB) {
                                    { onSelectProvider(info.id) }
                                } else {
                                    { info.webviewUrl?.let(onOpenWeb) }
                                },
                                onOpen = if (category == HubCategory.WEB) {
                                    { info.webviewUrl?.let(onOpenWeb) }
                                } else {
                                    null
                                },
                                selectLabel = if (category == HubCategory.WEB) "اختيار" else "فتح"
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** نص نتيجة الفحص داخل النافذة. */
@Composable
private fun ReportText(text: String) {
    Surface(
        color = Color(0xFF101010),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
    ) {
        Text(
            text,
            color = Color(0xFFD8D8D8),
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(10.dp)
        )
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
    onOpen: (() -> Unit)? = null,
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
            if (onOpen != null) {
                TextButton(onClick = onOpen) {
                    Text("فتح ↗", color = Color(0xFF64B5F6), fontSize = 11.sp)
                }
            }
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

private val ClaudeCard = Color(0xFF2B2B2A)
private val ClaudeBorder = Color(0xFF3D3D3B)
private val ClaudeText = Color(0xFFD9D8D4)
private val ClaudeMuted = Color(0xFF8E8D89)
private val ClaudeAccent = Color(0xFFC96442)

/** فقاعة خيارات الدردشة: تظهر صغيرة، وبالضغط عليها تنفتح القائمة (الوضع + تلقائي/يدوي). */
@Composable
private fun ChatOptionsBubble(
    mode: ChatMode,
    autoRoute: Boolean,
    channelSummary: String,
    onOpenMode: () -> Unit,
    onToggleAuto: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            color = Color(0xFF383837),
            shape = RoundedCornerShape(50),
            modifier = Modifier.clickable { open = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("💬", fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                Text(mode.short, color = ClaudeText, fontSize = 12.sp, maxLines = 1)
                Icon(Icons.Filled.ArrowDropDown, null, tint = ClaudeMuted, modifier = Modifier.size(14.dp))
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(ClaudeCard, RoundedCornerShape(14.dp))
        ) {
            DropdownMenuItem(
                text = { Text("💬 وضع الدردشة: ${mode.short}", color = Color.White, fontSize = 13.sp) },
                onClick = { open = false; onOpenMode() }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        (if (autoRoute) "🧠 تلقائي" else "✋ يدوي") +
                            (if (channelSummary.isNotBlank()) " · $channelSummary" else ""),
                        color = Color.White,
                        fontSize = 13.sp
                    )
                },
                onClick = { open = false; onToggleAuto() }
            )
        }
    }
}

/**
 * صندوق الكتابة الجديد: حقل واسع، زر إرسال/إيقاف مربع، اسم المزود العامل
 * مع أيقونة مصدره، وصف الأزرار الناعمة للمجموعات أسفله.
 *
 * زر الإرسال نفسه هو زر الإيقاف: أثناء التوليد يوقف الرد الحالي، وإن كان في
 * صندوق الكتابة نص أرسله مداخلةً بعد الإيقاف (اعتراض/معلومة/مناقشة/تصحيح).
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
    channelSummary: String = "",
    onEditImage: (ImageAttachment) -> Unit,
    onClearAttachment: () -> Unit,
    onQuickPrompt: (String) -> Unit,
    onAttachFile: () -> Unit,
    onVoice: () -> Unit,
    onChooseModel: () -> Unit,
    onOpenMode: () -> Unit,
    onToggleAuto: () -> Unit,
    onOpenCategory: (HubCategory) -> Unit,
    onStop: () -> Unit,
    onFollowUp: () -> Unit,
    onOpenSettings: () -> Unit,
    onSend: () -> Unit
) {
    val file = attachment
    val image = imageAttachment
    val hasDraft = input.isNotBlank() || file != null || image != null
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    // أي طلب يبدأ (أياً كان المزود): أنزل لوحة المفاتيح وأفرغ التركيز ليظهر الرد كاملاً.
    // لا تعود إلا عند لمس صندوق الكتابة.
    LaunchedEffect(loading) { if (loading) dismissKeyboard() }

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
                        listOfNotNull(
                            file?.let { "📎 ${it.name}" },
                            image?.let { "🖼️ ${it.name}" }
                        ).joinToString("  ·  "),
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
                    color = ClaudeCard,
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            ClaudeBorder,
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
                                    cursorBrush = SolidColor(ClaudeAccent),
                                    maxLines = 5,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(Modifier.size(2.dp))
                        // نمط كلود: + وفقاعة الدردشة يساراً، والموديل والميكروفون والإرسال يميناً.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onAttachFile, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Add, "إرفاق ملف", tint = ClaudeText, modifier = Modifier.size(22.dp))
                            }
                            ChatOptionsBubble(mode, autoRoute, channelSummary, onOpenMode, onToggleAuto)
                            Spacer(Modifier.weight(1f))
                            Row(
                                modifier = Modifier
                                    .clickable(onClick = onChooseModel)
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    providerLabel,
                                    color = ClaudeText,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 90.dp)
                                )
                                if (providerModel.isNotBlank()) {
                                    Text(
                                        " " + providerModel,
                                        color = ClaudeMuted,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 80.dp)
                                    )
                                }
                                Icon(
                                    Icons.Filled.ArrowDropDown,
                                    "اختيار الموديل",
                                    tint = ClaudeMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(onClick = onVoice, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Mic, "مكرفون المساعد", tint = ClaudeText, modifier = Modifier.size(20.dp))
                            }
                            val sending = loading && hasDraft
                            FilledIconButton(
                                onClick = {
                                    when {
                                        loading && hasDraft -> onFollowUp()
                                        loading -> onStop()
                                        else -> onSend()
                                    }
                                },
                                enabled = loading || canSend,
                                modifier = Modifier.size(38.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (sending || !loading) ClaudeAccent else Color(0xFF4A2A22),
                                    contentColor = Color.White,
                                    disabledContainerColor = Color(0xFF3D3D3B),
                                    disabledContentColor = Color(0xFF7A7A76)
                                )
                            ) {
                                Icon(
                                    if (loading && !hasDraft) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                                    contentDescription = when {
                                        sending -> "إيقاف الرد الحالي وإرسال مداخلتي"
                                        loading -> "إيقاف"
                                        else -> "إرسال"
                                    },
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (loading) {
                Text(
                    if (hasDraft) {
                        "⚡ اضغط ↗ لإيقاف الرد الحالي وإرسال مداخلتك (اعتراض · معلومة · تصحيح)"
                    } else {
                        "⏹ اضغط لإيقاف الرد الحالي، أو اكتب مداخلتك ثم اضغط ↗"
                    },
                    color = Color(0xFF4C8DFF),
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

        }
    }
}
