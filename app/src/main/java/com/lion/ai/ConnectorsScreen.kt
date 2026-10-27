package com.lion.ai

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * ربط حسابات المستخدم بالموصلات: Google Drive / Gmail / Calendar عبر OAuth،
 * وNotion / Slack عبر توكن، وأي خدمة REST عبر عنوان ورأس مصادقة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsScreen(
    prefs: android.content.SharedPreferences,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var busyId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var googleId by remember { mutableStateOf(ConnectorAuth.googleClientId(prefs)) }
    var googleSecret by remember { mutableStateOf(ConnectorAuth.googleClientSecret(prefs)) }
    var showGoogleGuide by remember { mutableStateOf(false) }
    var notifText by remember { mutableStateOf("") }

    fun run(id: String, action: suspend () -> String) {
        if (busyId.isNotBlank()) return
        busyId = id
        message = ""
        scope.launch {
            try {
                val label = action()
                val prettyName = ConnectorAuth.find(id)?.name ?: id
                message = "✅ تم ربط $prettyName${if (label.isNotBlank()) " — $label" else ""}"
                version++
            } catch (e: Exception) {
                message = "❌ ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busyId = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🔌 الموصلات والحسابات", color = Color(0xFFFFB800), fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = Color(0xFFFFB800))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF151515))
            )
        },
        containerColor = Color(0xFF0D0D0D)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "اربط حساباتك مرة واحدة ليستخدمها الذكاء تلقائياً: يقرأ ما تطلبه، ويرسل فقط عند طلبك الصريح. " +
                        "تُحفظ الرموز مشفّرة على جهازك ولا تُرسل لأي خادم وسيط.",
                    color = Color(0xFFB8B8B8),
                    fontSize = 12.sp
                )
            }
            if (message.isNotBlank()) {
                item {
                    Text(
                        message,
                        color = if (message.startsWith("✅")) Color(0xFF7CD992) else Color(0xFFFF8A8A),
                        fontSize = 12.sp
                    )
                }
            }

            item {
                SectionCard(title = "🔑 إعداد Google OAuth", subtitle = "مطلوب مرة واحدة لربط Drive وGmail وCalendar") {
                    Text(
                        "الطريقة المضمونة: أنشئ OAuth Client من نوع Desktop app في Google Cloud Console، وفعّل Drive API وGmail API وCalendar API، " +
                            "ثم ألصق Client ID وSecret هنا. التطبيق يستخدم PKCE مع redirect محلي ثابت (127.0.0.1) — لا يحتاج تسجيل رابط لنوع Desktop.",
                        color = Color(0xFF9C9C9C),
                        fontSize = 11.sp
                    )
                    Text(
                        "إن أنشأت العميل من نوع Web application، أضف هذا الرابط حرفياً في Authorized redirect URIs:",
                        color = Color(0xFF9C9C9C),
                        fontSize = 11.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            ConnectorAuth.defaultRedirectUri,
                            color = Color(0xFF7CD992),
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(ConnectorAuth.defaultRedirectUri))
                            message = "✅ نُسخ رابط إعادة التوجيه"
                        }) { Text("نسخ", color = Color(0xFFFFB800), fontSize = 11.sp) }
                    }
                    OutlinedTextField(
                        value = googleId,
                        onValueChange = { googleId = it },
                        label = { Text("Google OAuth Client ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = googleSecret,
                        onValueChange = { googleSecret = it },
                        label = { Text("Client Secret") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                ConnectorAuth.saveGoogleClient(prefs, googleId, googleSecret)
                                message = "✅ حُفظ إعداد Google — اضغط «ربط الحساب» أسفل"
                                version++
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
                        ) { Text("حفظ الإعداد", color = Color(0xFF0D0D0D)) }
                        TextButton(onClick = onOpenSettings) { Text("الإعدادات", color = Color(0xFFFFB800)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            runCatching { uriHandler.openUri("https://console.cloud.google.com/apis/credentials") }
                        }) { Text("فتح Google Cloud", color = Color(0xFFFFB800), fontSize = 11.sp) }
                        TextButton(onClick = {
                            runCatching { uriHandler.openUri("https://console.cloud.google.com/apis/library") }
                        }) { Text("تفعيل الـ APIs", color = Color(0xFFFFB800), fontSize = 11.sp) }
                        TextButton(onClick = {
                            runCatching { uriHandler.openUri("https://console.cloud.google.com/apis/credentials/consent") }
                        }) { Text("شاشة الموافقة", color = Color(0xFFFFB800), fontSize = 11.sp) }
                    }
                    TextButton(onClick = { showGoogleGuide = !showGoogleGuide }) {
                        Text(
                            if (showGoogleGuide) "إخفاء الخطوات" else "❓ من أين أحصل على Client ID وSecret؟",
                            color = Color(0xFFFFB800),
                            fontSize = 12.sp
                        )
                    }
                    if (showGoogleGuide) {
                        Text(
                            "1) افتح console.cloud.google.com وأنشئ مشروعاً جديداً (أو اختر مشروعاً).\n" +
                                "2) APIs & Services ← Library: فعّل Google Drive API، وإن أردت البريد فعّل Gmail API، وللتقويم فعّل Google Calendar API.\n" +
                                "3) APIs & Services ← OAuth consent screen: اختر External، املأ اسم التطبيق وبريد الدعم، وأضف بريدك في Test users.\n" +
                                "4) في Data access أضف الصلاحيات: drive وgmail.modify وgmail.send وcalendar (حسب ما تريد).\n" +
                                "5) APIs & Services ← Credentials ← Create Credentials ← OAuth client ID ← Application type: Desktop app ← Create.\n" +
                                "6) انسخ Client ID وClient secret والصقهما هنا ثم اضغط حفظ الإعداد، ثم اضغط ربط الحساب.\n" +
                                "لا تحتاج إلى إضافة Redirect URI يدوياً لنوع Desktop؛ التطبيق يستخدم 127.0.0.1 بمنفذ ثابت " +
                                "(${ConnectorAuth.DEFAULT_REDIRECT_PORT}).\n" +
                                "إن ظهرت رسالة أن التطبيق غير موثّق، اضغط Advanced ثم المتابعة، أو أضف حسابك كـ Test user.\n" +
                                "ملاحظة مهمة: في وضع Testing تنتهي صلاحية ربط الحساب بعد 7 أيام؛ لنشر التطبيق اضغط Publish app.",
                            color = Color(0xFFD0D0D0),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            val googleDefs = ConnectorAuth.all.filter { it.auth == AuthType.GOOGLE }
            googleDefs.forEach { def ->
                item(key = def.id) {
                    ConnectorRow(
                        def = def,
                        prefs = prefs,
                        version = version,
                        busy = busyId == def.id,
                        anyBusy = busyId.isNotBlank(),
                        onConnect = {
                            run(def.id) {
                                ConnectorAuth.connect(context, prefs, def.id)
                            }
                        },
                        onTest = {
                            run(def.id) { ConnectorAuth.test(prefs, def.id) }
                        },
                        onDisconnect = {
                            scope.launch {
                                busyId = def.id
                                ConnectorAuth.revoke(prefs, def.id)
                                busyId = ""
                                version++
                                message = "تم فصل ${def.name}"
                            }
                        }
                    )
                }
            }

            item {
                SectionCard(
                    title = "📱 قراءة وردّ WhatsApp/Telegram (حسابك الشخصي)",
                    subtitle = "عبر صلاحية الإشعارات — بدون تسجيل دخول أو QR"
                ) {
                    Text(
                        NotificationAccess.describe(context, prefs),
                        color = Color(0xFFD0D0D0),
                        fontSize = 12.sp
                    )
                    Text(
                        "تقرأ الخدمة إشعارات WhatsApp/Telegram فقط وتخزّنها محلياً على جهازك؛ " +
                            "والرد المباشر يعمل على الإشعار النشط الذي يدعم RemoteInput.",
                        color = Color(0xFF9C9C9C),
                        fontSize = 11.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Switch(
                            checked = NotificationAccess.captureEnabled(prefs),
                            onCheckedChange = { on ->
                                prefs.edit().putBoolean(NotificationAccess.PREFS_CAPTURE, on).apply()
                                version++
                                message = if (on) "✅ التُقاط الرسائل مفعّل" else "تم إيقاف التقاط الرسائل"
                            }
                        )
                        Text(
                            if (NotificationAccess.captureEnabled(prefs)) "التقاط الرسائل مفعّل" else "التقاط الرسائل موقوف",
                            color = Color(0xFFD0D0D0),
                            fontSize = 12.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                runCatching { context.startActivity(NotificationAccess.settingsIntent()) }
                                    .onFailure { message = "❌ تعذّر فتح إعدادات الإشعارات" }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
                        ) { Text("فتح صلاحية الإشعارات", color = Color(0xFF0D0D0D), fontSize = 12.sp) }
                        TextButton(onClick = { version++ }) {
                            Text("تحديث الحالة", color = Color(0xFFFFB800), fontSize = 12.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val rows = NotificationStore.recent(context, limit = 10)
                            notifText = if (rows.isEmpty()) "لا رسائل محفوظة بعد."
                            else rows.joinToString("\n") {
                                "[${it.appName}] ${it.conversation}: ${it.body.take(80)}"
                            }
                        }) { Text("عرض آخر الرسائل", fontSize = 12.sp) }
                        OutlinedButton(onClick = {
                            NotificationStore.clear(context)
                            version++
                            notifText = ""
                            message = "✅ مُسح سجل الرسائل المحلي"
                        }) { Text("مسح السجل", fontSize = 12.sp) }
                    }
                    if (notifText.isNotBlank()) {
                        Text(notifText, color = Color(0xFFB8B8B8), fontSize = 11.sp)
                    }
                }
            }

            item {
                HorizontalDivider(color = Color(0xFF2A2A2A))
                Spacer(Modifier.height(4.dp))
                Text("توكن شخصي", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold)
            }

            val tokenDefs = ConnectorAuth.all.filter { it.auth == AuthType.TOKEN }
            tokenDefs.forEach { def ->
                item(key = def.id) {
                    TokenConnectorCard(
                        def = def,
                        prefs = prefs,
                        version = version,
                        busy = busyId == def.id,
                        anyBusy = busyId.isNotBlank(),
                        onConnect = { token ->
                            ConnectorAuth.saveToken(prefs, def.id, token)
                            run(def.id) { ConnectorAuth.connect(context, prefs, def.id) }
                        },
                        onDisconnect = {
                            ConnectorAuth.disconnect(prefs, def.id)
                            version++
                            message = "تم فصل ${def.name}"
                        }
                    )
                }
            }

            item {
                HorizontalDivider(color = Color(0xFF2A2A2A))
                Spacer(Modifier.height(4.dp))
                Text("خدمة REST مخصّصة", color = Color(0xFFFFB800), fontWeight = FontWeight.Bold)
            }

            item {
                GenericConnectorCard(
                    prefs = prefs,
                    version = version,
                    busy = busyId == "generic",
                    anyBusy = busyId.isNotBlank(),
                    onConnect = { base, header, value ->
                        ConnectorAuth.saveGeneric(prefs, base, header, value)
                        run("generic") { ConnectorAuth.connect(context, prefs, "generic") }
                    },
                    onDisconnect = {
                        prefs.edit()
                            .remove(ConnectorAuth.baseKey("generic"))
                            .remove(ConnectorAuth.headerKey("generic"))
                            .remove(ConnectorAuth.valueKey("generic"))
                            .remove(ConnectorAuth.accountKey("generic"))
                            .apply()
                        version++
                        message = "تم فصل الخدمة المخصّصة"
                    }
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "ملاحظات: Gmail يتطلب صلاحية gmail.modify وgmail.send. Notion يتطلب مشاركة الصفحة مع الـ Integration. " +
                        "Slack يتطلب Bot Token. لا تُرسل أي رسالة أو تعديل إلا بعد طلب صريح منك.",
                    color = Color(0xFF858585),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color(0xFFFFB800), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (subtitle != null) Text(subtitle, color = Color(0xFF9C9C9C), fontSize = 11.sp)
            content()
        }
    }
}

@Composable
private fun ConnectorRow(
    def: ConnectorDef,
    prefs: android.content.SharedPreferences,
    version: Int,
    busy: Boolean,
    anyBusy: Boolean,
    onConnect: () -> Unit,
    onTest: () -> Unit,
    onDisconnect: () -> Unit
) {
    val tick = version
    val connected = remember(tick, def.id) { ConnectorAuth.isConnected(prefs, def.id) }
    val account = remember(tick, def.id) { ConnectorAuth.account(prefs, def.id) }
    SectionCard(title = "${def.icon} ${def.name}", subtitle = def.description) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (connected) "✅ مربوط${if (account.isNotBlank()) " — $account" else ""}" else "⚪ غير مربوط",
                color = if (connected) Color(0xFF7CD992) else Color(0xFFB0B0B0),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFFFFB800), strokeWidth = 2.dp)
            } else if (connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = onTest, enabled = !anyBusy) { Text("اختبار", color = Color(0xFF7CD992)) }
                    OutlinedButton(onClick = onDisconnect, enabled = !anyBusy) { Text("فصل", color = Color(0xFFFF8A8A)) }
                }
            } else {
                Button(
                    onClick = onConnect,
                    enabled = !anyBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
                ) { Text("ربط الحساب", color = Color(0xFF0D0D0D)) }
            }
        }
        if (busy) {
            Text(
                "انتظر الموافقة في المتصفح ثم عد للتطبيق (حتى 3 دقائق)…",
                color = Color(0xFFFFB800),
                fontSize = 11.sp
            )
        }
        if (connected) {
            val expiry = ConnectorAuth.expiresAt(prefs, def.id)
            if (expiry > 0 && def.auth == AuthType.GOOGLE) {
                val minutes = ((expiry - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                Text(
                    if (minutes > 0) "الوصول صالح ~$minutes دقيقة (يتجدد تلقائياً)" else "يتجدد الوصول تلقائياً عند الاستخدام",
                    color = Color(0xFF858585),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun TokenConnectorCard(
    def: ConnectorDef,
    prefs: android.content.SharedPreferences,
    version: Int,
    busy: Boolean,
    anyBusy: Boolean,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    val tick = version
    val connected = remember(tick, def.id) { ConnectorAuth.isConnected(prefs, def.id) }
    val account = remember(tick, def.id) { ConnectorAuth.account(prefs, def.id) }
    var token by remember { mutableStateOf(if (connected) "" else "") }
    SectionCard(title = "${def.icon} ${def.name}", subtitle = def.description) {
        Text(
            if (connected) "✅ مربوط${if (account.isNotBlank()) " — $account" else ""}" else "⚪ غير مربوط",
            color = if (connected) Color(0xFF7CD992) else Color(0xFFB0B0B0),
            fontSize = 12.sp
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(if (connected) "توكن جديد (اختياري)" else "التوكن") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        if (def.tokenHint.isNotBlank()) Text(def.tokenHint, color = Color(0xFF8F8F8F), fontSize = 11.sp)
        if (def.id == "notion" || def.id == "slack") {
            val uriHandler = LocalUriHandler.current
            TextButton(onClick = {
                runCatching {
                    uriHandler.openUri(
                        if (def.id == "notion") "https://www.notion.so/my-integrations" else "https://api.slack.com/apps"
                    )
                }
            }) {
                Text(
                    if (def.id == "notion") "فتح صفحة إنشاء Integration" else "فتح صفحة إنشاء تطبيق Slack",
                    color = Color(0xFFFFB800),
                    fontSize = 11.sp
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onConnect(token) },
                enabled = !anyBusy && token.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
            ) { Text(if (connected) "تحديث" else "حفظ وربط", color = Color(0xFF0D0D0D)) }
            if (connected) {
                OutlinedButton(onClick = onDisconnect, enabled = !anyBusy) { Text("فصل", color = Color(0xFFFF8A8A)) }
            }
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFFFFB800), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun GenericConnectorCard(
    prefs: android.content.SharedPreferences,
    version: Int,
    busy: Boolean,
    anyBusy: Boolean,
    onConnect: (String, String, String) -> Unit,
    onDisconnect: () -> Unit
) {
    val tick = version
    val connected = remember(tick) { ConnectorAuth.isConnected(prefs, "generic") }
    val account = remember(tick) { ConnectorAuth.account(prefs, "generic") }
    var base by remember { mutableStateOf(prefs.getString(ConnectorAuth.baseKey("generic"), "").orEmpty()) }
    var header by remember {
        mutableStateOf(prefs.getString(ConnectorAuth.headerKey("generic"), "Authorization").orEmpty())
    }
    var value by remember { mutableStateOf(prefs.getString(ConnectorAuth.valueKey("generic"), "").orEmpty()) }
    SectionCard(
        title = "🔗 خدمة REST مخصّصة",
        subtitle = defDescription("generic")
    ) {
        Text(
            if (connected) "✅ مربوط${if (account.isNotBlank()) " — $account" else ""}" else "⚪ غير مربوط",
            color = if (connected) Color(0xFF7CD992) else Color(0xFFB0B0B0),
            fontSize = 12.sp
        )
        OutlinedTextField(
            value = base,
            onValueChange = { base = it },
            label = { Text("https://api.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = header,
            onValueChange = { header = it },
            label = { Text("اسم الرأس (Authorization أو X-API-Key)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("قيمة الرأس (Bearer ... أو المفتاح)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onConnect(base, header, value) },
                enabled = !anyBusy && base.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
            ) { Text(if (connected) "تحديث" else "حفظ وربط", color = Color(0xFF0D0D0D)) }
            if (connected) {
                OutlinedButton(onClick = onDisconnect, enabled = !anyBusy) { Text("فصل", color = Color(0xFFFF8A8A)) }
            }
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFFFFB800), strokeWidth = 2.dp)
        }
        Text(
            "يستطيع الذكاء طلب مسارات نسبية فقط على نفس النطاق (مثل /items). لا تُرسل بياناتك إلا لهذه الخدمة.",
            color = Color(0xFF8F8F8F),
            fontSize = 11.sp
        )
    }
}

private fun defDescription(id: String): String =
    ConnectorAuth.find(id)?.description.orEmpty()
