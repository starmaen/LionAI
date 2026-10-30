package com.lion.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val T_GOLD = Color(0xFFFFB800)
private val T_DARK = Color(0xFF0D0D0D)
private val T_CARD = Color(0xFF1A1A1A)
private val T_MUTED = Color(0xFF9A9A9A)
private val T_GREEN = Color(0xFF7CC47C)
private val T_RED = Color(0xFFE94560)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevToolsScreen(
    prefs: SharedPreferences,
    onBack: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf("home") }

    Scaffold(
        containerColor = T_DARK,
        topBar = {
            TopAppBar(
                title = { Text("🧰 أدوات المطوّر", color = T_GOLD, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { if (tab == "home") onBack() else tab = "home" }) {
                        Icon(Icons.Filled.ArrowBack, "رجوع", tint = T_GOLD)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = T_CARD)
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                "home" -> ToolsHome(
                    onOpenGitHub = onOpenGitHub,
                    onOpenSettings = onOpenSettings,
                    onTab = { tab = it }
                )
                "web" -> WebToolTab()
                "files" -> FilesToolTab(context)
                "db" -> DatabaseToolTab(context)
                "code" -> CodeToolTab(context)
                "manager" -> ToolManagerTab(prefs, onOpenGitHub, onOpenSettings)
                "audit" -> ToolAuditTab()
            }
        }
    }
}

@Composable
private fun ToolsHome(
    onOpenGitHub: () -> Unit,
    onOpenSettings: () -> Unit,
    onTab: (String) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Text("كل الأدوات متاحة أيضاً للذكاء الاصطناعي تلقائياً عند الحاجة.", color = T_MUTED, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
        }
        item {
            ToolCard("🌐", "متصفح الويب", "جلب صفحة، استخراج النص والأكواد والروابط والصور، استدعاء API، وتنزيل ملفات") { onTab("web") }
            ToolCard("🐙", "GitHub Explorer", "استعراض المستودعات، الشجرة، الملفات، README، الفروع، Issues، PRs، Actions") { onOpenGitHub() }
            ToolCard("📁", "قارئ الملفات", "قراءة وكتابة ملفات مساحة العمل، CSV، JSON، ZIP، وحفظ التعديلات") { onTab("files") }
            ToolCard("🗄️", "قواعد البيانات", "إنشاء SQLite، تنفيذ SQL، عرض المخطط، استيراد/تصدير JSON") { onTab("db") }
            ToolCard("🔍", "محلل الكود", "مقاييس، دوال، كلاسات، Imports، تعقيد، TODO/FIXME، وملاحظات") { onTab("code") }
            ToolCard("🚀", "Git Push", "إنشاء/تعديل الملفات وإنشاء الفروع وPRs من شاشة GitHub") { onOpenGitHub() }
            ToolCard("🧠", "مدير الأدوات", "حالة Function Calling، صلاحيات الكتابة، وربط الأدوات بالمحادثة") { onTab("manager") }
            ToolCard("📜", "سجل الأدوات", "آخر ما نفّذه الذكاء الاصطناعي من أدوات ونتائجها") { onTab("audit") }
            ToolCard("⚙️", "إعدادات الذكاء", "التوكن، المستودع الافتراضي، تشغيل الأدوات والكتابة") { onOpenSettings() }
        }
    }
}

@Composable
private fun ToolCard(icon: String, title: String, description: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = T_CARD)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold)
                Text(description, color = T_MUTED, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun WebToolTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var apiMode by remember { mutableStateOf(false) }
    var method by remember { mutableStateOf("GET") }
    var body by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    fun runRemote(block: suspend () -> String) {
        if (url.isBlank()) {
            status = "أدخل رابطاً أولاً"
            return
        }
        scope.launch {
            loading = true
            status = ""
            result = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { "❌ ${it.message}" } }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://example.com", color = Color(0xFF707070)) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White),
            colors = toolFieldColors()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = apiMode,
                onCheckedChange = { apiMode = it },
                colors = SwitchDefaults.colors(checkedThumbColor = T_GOLD)
            )
            Text("وضع API", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            Text(if (loading) "⏳" else "", color = T_GOLD)
        }
        if (apiMode) {
            OutlinedTextField(
                value = method,
                onValueChange = { method = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Method", color = T_MUTED, fontSize = 11.sp) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White),
                colors = toolFieldColors()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Body (JSON)", color = T_MUTED, fontSize = 11.sp) },
                minLines = 3,
                textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                colors = toolFieldColors()
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !loading,
                onClick = {
                    if (apiMode) {
                        runRemote { WebTools.httpRequest(url.trim(), method.trim(), body, "application/json") }
                    } else {
                        runRemote { WebTools.summary(WebTools.fetch(url.trim())) }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = T_GOLD)
            ) { Text(if (apiMode) "📡 استدعاء" else "🌐 جلب", color = T_DARK) }
            if (!apiMode) {
                TextButton(enabled = !loading, onClick = {
                    runRemote {
                        val download = WebTools.download(context, url.trim())
                        "✅ نُزّل ${FileTools.relative(context, download.file)} (${FileTools.formatSize(download.bytes.toLong())})"
                    }
                }) { Text("⬇️ تنزيل", color = T_GOLD) }
            }
        }
        if (status.isNotBlank()) Text(status, color = T_MUTED, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        if (result.isNotBlank()) {
            HorizontalDivider(color = Color(0xFF2A2A2A))
            Spacer(Modifier.height(8.dp))
            Text(result.take(60_000), color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun FilesToolTab(context: Context) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(listOf<FileTools.Entry>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var csvInfo by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(listOf<FileTools.Match>()) }
    var searching by remember { mutableStateOf(false) }
    var newPath by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { FileTools.list(context, path) }
            }
            result.onSuccess { entries = it }
                .onFailure {
                    status = "⚠️ ${it.message}"
                    entries = emptyList()
                }
        }
    }

    fun openFile(target: String) {
        selected = target
        scope.launch {
            loading = true
            csvInfo = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val info = FileTools.read(context, target)
                    var csvText: String? = null
                    if (FileTools.fileType(target) == "csv") {
                        val csv = FileTools.readCsv(context, target)
                        csvText = "CSV: ${csv.rowCount} صفاً × ${csv.columnCount} عموداً\n" +
                            "الأعمدة: ${csv.headers.joinToString(" | ")}"
                    }
                    Triple(info, info.binary, csvText)
                }
            }
            result.onSuccess { (info, binary, csvText) ->
                content = if (binary) "[ملف ثنائي ${FileTools.formatSize(info.size)}]" else info.text
                csvInfo = csvText
                status = ""
            }.onFailure { status = "⚠️ ${it.message}" }
            loading = false
        }
    }

    LaunchedEffect(path) { refresh() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("📁 مساحة العمل: ${FileTools.root(context).absolutePath}", color = T_MUTED, fontSize = 10.sp)
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("المجلد", color = T_MUTED, fontSize = 11.sp) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White),
            colors = toolFieldColors()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = {
                if (path.isNotBlank()) path = path.substringBeforeLast('/', "")
            }) { Text("⬆️ أعلى", color = T_GOLD, fontSize = 12.sp) }
            TextButton(onClick = { refresh() }) { Text("🔄 تحديث", color = T_GOLD, fontSize = 12.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("بحث في الملفات والكود", color = Color(0xFF707070), fontSize = 12.sp) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                colors = toolFieldColors()
            )
            Spacer(Modifier.width(6.dp))
            TextButton(
                enabled = !searching,
                onClick = {
                    if (search.isBlank()) {
                        searchResults = emptyList()
                        return@TextButton
                    }
                    scope.launch {
                        searching = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching { FileTools.search(context, search, maxResults = 80) }
                        }
                        result.onSuccess { searchResults = it }
                            .onFailure { status = "⚠️ ${it.message}" }
                        searching = false
                    }
                }
            ) { Text(if (searching) "⏳" else "🔎", color = T_GOLD) }
        }
        if (selected == null && search.isNotBlank()) {
            if (searchResults.isEmpty()) {
                Text("لا نتائج.", color = T_MUTED, fontSize = 12.sp)
            }
            searchResults.forEach { match ->
                Row(
                    Modifier.fillMaxWidth().clickable { openFile(match.path) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔎")
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(match.path, color = Color.White, fontSize = 12.sp)
                        if (match.line > 0) Text("سطر ${match.line}: ${match.text}", color = T_MUTED, fontSize = 10.sp)
                    }
                }
            }
        }
        if (selected == null && search.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newPath,
                    onValueChange = { newPath = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("مسار ملف جديد داخل المجلد الحالي", color = Color(0xFF707070), fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    colors = toolFieldColors()
                )
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = {
                    if (newPath.isNotBlank()) {
                        selected = if (path.isBlank()) newPath.trim() else "$path/${newPath.trim()}"
                        content = ""
                        csvInfo = null
                        status = "✏️ ملف جديد — اكتب المحتوى ثم احفظ"
                        newPath = ""
                    }
                }) { Text("➕ جديد", color = T_GOLD, fontSize = 12.sp) }
            }
            entries.forEach { entry ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            if (entry.directory) {
                                path = entry.path
                            } else {
                                openFile(entry.path)
                            }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (entry.directory) "📁" else "📄")
                    Spacer(Modifier.width(8.dp))
                    Text(entry.path, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (!entry.directory) Text(FileTools.formatSize(entry.size), color = T_MUTED, fontSize = 10.sp)
                }
            }
            if (entries.isEmpty()) Text("لا ملفات. اكتب مساراً جديداً في الأعلى ثم احفظ ملفاً من المحادثة أو هنا.", color = T_MUTED, fontSize = 12.sp)
        }
        if (selected != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📄 ${selected.orEmpty()}", color = T_GOLD, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = null; content = ""; csvInfo = null }) {
                    Text("رجوع", color = T_GOLD, fontSize = 12.sp)
                }
            }
            csvInfo?.let { Text(it, color = T_GREEN, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 520.dp),
                textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                colors = toolFieldColors()
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !loading,
                    onClick = {
                        val target = selected ?: return@Button
                        scope.launch {
                            loading = true
                            val result = withContext(Dispatchers.IO) {
                                runCatching { FileTools.write(context, target, content) }
                            }
                            status = result.fold({ "✅ حُفظ ${it.path}" }, { "⚠️ ${it.message}" })
                            loading = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = T_GOLD)
                ) { Text("💾 حفظ", color = T_DARK) }
                TextButton(onClick = {
                    val target = selected ?: return@TextButton
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { FileTools.delete(context, target, true) } }
                        result.onSuccess {
                            status = "✅ حُذف"
                            selected = null
                            content = ""
                            refresh()
                        }.onFailure { status = "⚠️ ${it.message}" }
                    }
                }) { Text("🗑️ حذف", color = T_RED, fontSize = 12.sp) }
            }
        }
        if (status.isNotBlank()) Text(status, color = T_MUTED, fontSize = 12.sp)
    }
}

@Composable
private fun DatabaseToolTab(context: Context) {
    val scope = rememberCoroutineScope()
    var dbName by remember { mutableStateOf("app") }
    var sql by remember { mutableStateOf("SELECT name FROM sqlite_master WHERE type='table'") }
    var result by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    fun execute(block: suspend () -> String) {
        scope.launch {
            loading = true
            result = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { "❌ ${it.message}" } }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        OutlinedTextField(
            value = dbName,
            onValueChange = { dbName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("اسم قاعدة البيانات", color = T_MUTED, fontSize = 11.sp) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White),
            colors = toolFieldColors()
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = sql,
            onValueChange = { sql = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("SQL", color = T_MUTED, fontSize = 11.sp) },
            minLines = 4,
            textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            colors = toolFieldColors()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = !loading,
                onClick = { execute { DatabaseTools.query(context, dbName, sql) } },
                colors = ButtonDefaults.buttonColors(containerColor = T_GOLD)
            ) { Text("▶️ تنفيذ", color = T_DARK) }
            TextButton(enabled = !loading, onClick = {
                execute { DatabaseTools.schema(context, dbName) }
            }) { Text("🗂️ المخطط", color = T_GOLD, fontSize = 12.sp) }
            TextButton(enabled = !loading, onClick = {
                execute { DatabaseTools.list(context).joinToString("\n") { "• $it" }.ifBlank { "لا قواعد بيانات" } }
            }) { Text("📚 القواعد", color = T_GOLD, fontSize = 12.sp) }
        }
        if (status.isNotBlank()) Text(status, color = T_MUTED, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        if (result.isNotBlank()) {
            HorizontalDivider(color = Color(0xFF2A2A2A))
            Spacer(Modifier.height(8.dp))
            Text(result.take(60_000), color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun CodeToolTab(context: Context) {
    var name by remember { mutableStateOf("Main.kt") }
    var code by remember { mutableStateOf("") }
    var report by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("اسم/امتداد الملف", color = T_MUTED, fontSize = 11.sp) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White),
            colors = toolFieldColors()
        )
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("أو مسار ملف في مساحة العمل", color = T_MUTED, fontSize = 11.sp) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White),
            colors = toolFieldColors()
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp),
            label = { Text("الصق الكود هنا", color = T_MUTED, fontSize = 11.sp) },
            textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            colors = toolFieldColors()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                report = runCatching {
                    if (path.isNotBlank()) {
                        val info = FileTools.read(context, path)
                        CodeAnalyzer.report(CodeAnalyzer.analyze(path, info.text))
                    } else {
                        CodeAnalyzer.report(CodeAnalyzer.analyze(name.ifBlank { "code.txt" }, code))
                    }
                }.getOrElse { "❌ ${it.message}" }
            },
            colors = ButtonDefaults.buttonColors(containerColor = T_GOLD)
        ) { Text("🔍 تحليل", color = T_DARK) }
        Spacer(Modifier.height(8.dp))
        if (report.isNotBlank()) Text(report.take(60_000), color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ToolAuditTab() {
    var version by remember { mutableStateOf(0) }
    val entries = remember(version) { ToolAudit.recent(40) }
    val formatter = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("سجل تنفيذ الأدوات", color = T_GOLD, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { ToolAudit.clear(); version++ }) { Text("مسح", color = T_RED) }
                TextButton(onClick = { version++ }) { Text("تحديث", color = T_GOLD) }
            }
            Text("يعرض آخر 40 عملية. السجل محلي فقط.", color = T_MUTED, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
        }
        if (entries.isEmpty()) {
            item { Text("لا توجد عمليات بعد. استخدم المحادثة أو الأدوات لتظهر هنا.", color = T_MUTED, fontSize = 13.sp) }
        }
        items(entries.size) { index ->
            val entry = entries[index]
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = T_CARD)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (entry.ok) "✅" else "❌", fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(entry.name, color = if (entry.ok) T_GREEN else T_RED, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text(formatter.format(java.util.Date(entry.at)), color = T_MUTED, fontSize = 10.sp)
                    }
                    if (entry.summary.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(entry.summary, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolManagerTab(
    prefs: SharedPreferences,
    onOpenGitHub: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var agent by remember { mutableStateOf(prefs.getBoolean("agent_tools", true)) }
    var write by remember { mutableStateOf(prefs.getBoolean("agent_write", true)) }
    val token = (prefs.getString("github_token", "") ?: "").trim()
    val repo = (prefs.getString("github_default_repo", "") ?: "").trim()
    var fbProject by remember { mutableStateOf(prefs.getString("firebase_project", "").orEmpty()) }
    var fbKey by remember { mutableStateOf(prefs.getString("firebase_api_key", "").orEmpty()) }
    var fbToken by remember { mutableStateOf(prefs.getString("firebase_id_token", "").orEmpty()) }
    var fbBucket by remember { mutableStateOf(prefs.getString("firebase_bucket", "").orEmpty()) }
    var backend by remember { mutableStateOf(prefs.getString("local_proxy_backend", "").orEmpty()) }
    var toolsEverywhere by remember { mutableStateOf(prefs.getBoolean("tools_everywhere", true)) }
    var toolReport by remember { mutableStateOf("") }
    var providerReport by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("🧠 مدير الأدوات", color = T_GOLD, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "يستخدم النموذج بروتوكول Function Calling نصياً موحّداً يعمل مع جميع مزودات API، دون الحاجة لدعم خاص من كل مزود.",
            color = T_MUTED,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = agent, onCheckedChange = {
                agent = it
                prefs.edit().putBoolean("agent_tools", it).apply()
            }, colors = SwitchDefaults.colors(checkedThumbColor = T_GOLD))
            Spacer(Modifier.width(8.dp))
            Text("تشغيل الأدوات تلقائياً", color = Color.White)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = write, onCheckedChange = {
                write = it
                prefs.edit().putBoolean("agent_write", it).apply()
            }, colors = SwitchDefaults.colors(checkedThumbColor = T_GOLD))
            Spacer(Modifier.width(8.dp))
            Text("السماح بالكتابة والحذف والدفع", color = Color.White)
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = Color(0xFF2A2A2A))
        Spacer(Modifier.height(10.dp))
        Text("حالة GitHub: " + if (token.isBlank()) "غير متصل ❌" else "متصل ✅", color = if (token.isBlank()) T_RED else T_GREEN)
        Text("المستودع الافتراضي: " + repo.ifBlank { "غير محدد" }, color = T_MUTED, fontSize = 12.sp)
        Text("مساحة العمل: " + FileTools.root(context).absolutePath, color = T_MUTED, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Color(0xFF2A2A2A))
        Spacer(Modifier.height(10.dp))
        Text("🔥 Firebase (REST)", color = T_GOLD, fontWeight = FontWeight.Bold)
        Text(
            "بدون SDK: Firestore وStorage عبر REST. الأمان في قواعد Firestore/Storage. اترك Token فارغاً إن كانت القواعد عامة للقراءة.",
            color = T_MUTED,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = fbProject,
            onValueChange = { fbProject = it; prefs.edit().putString("firebase_project", it.trim()).apply() },
            label = { Text("Project ID", color = T_MUTED) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            colors = toolFieldColors()
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = fbKey,
            onValueChange = { fbKey = it; prefs.edit().putString("firebase_api_key", it.trim()).apply() },
            label = { Text("Web API Key", color = T_MUTED) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            colors = toolFieldColors()
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = fbBucket,
            onValueChange = { fbBucket = it; prefs.edit().putString("firebase_bucket", it.trim()).apply() },
            label = { Text("Storage Bucket (اختياري)", color = T_MUTED) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            colors = toolFieldColors()
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = fbToken,
            onValueChange = { fbToken = it; prefs.edit().putString("firebase_id_token", it.trim()).apply() },
            label = { Text("ID Token (اختياري — للكتابة)", color = T_MUTED) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
            colors = toolFieldColors()
        )
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = Color(0xFF2A2A2A))
        Spacer(Modifier.height(10.dp))
        Text("🖥️ خادم محلي — مزود الخلفية", color = T_GOLD, fontWeight = FontWeight.Bold)
        Text(
            "الخادم المحلي يوجّه الطلبات لهذا المزود. اتركه فارغاً لاستخدام المزود النشط. أمثلة: gemini, deepseek, qwen, openrouter, groq.",
            color = T_MUTED,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = backend,
            onValueChange = { backend = it; prefs.edit().putString("local_proxy_backend", it.trim()).apply() },
            label = { Text("معرّف مزود الخلفية", color = T_MUTED) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            colors = toolFieldColors()
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = toolsEverywhere, onCheckedChange = {
                toolsEverywhere = it
                prefs.edit().putBoolean("tools_everywhere", it).apply()
            }, colors = SwitchDefaults.colors(checkedThumbColor = T_GOLD))
            Spacer(Modifier.width(8.dp))
            Text("تفعيل كل الأدوات في الأوضاع الثلاثة (عام/تعلّم/برمجة)", color = Color.White, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = Color(0xFF2A2A2A))
        Spacer(Modifier.height(10.dp))
        Text("🧪 اختبار المزودات والخادم", color = T_GOLD, fontWeight = FontWeight.Bold)
        Text(
            "اختبار الخادم يرسل طلباً عبر HTTP إلى 127.0.0.1 (المسار نفسه الذي يستخدمه التطبيق). فحص المزودات يجرّب كل مزود متاح بكلمة واحدة.",
            color = T_MUTED,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !working,
                onClick = {
                    working = true
                    providerReport = "⏳ أختبر الخادم المحلي…"
                    scope.launch {
                        providerReport = withContext(Dispatchers.IO) { LocalAIProxy.selfTest(prefs) }
                        working = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = T_CARD)
            ) { Text("🖥️ اختبار الخادم", color = T_GOLD) }
            Button(
                enabled = !working,
                onClick = {
                    working = true
                    providerReport = "⏳ أختبر كل المزودات المتاحة… قد يستغرق دقيقة."
                    scope.launch {
                        providerReport = withContext(Dispatchers.IO) {
                            val rows = mutableListOf<String>()
                            PROVIDERS.filter { it.group == ProviderGroup.API }.take(14).forEach { info ->
                                if (!SmartRouter.available(prefs, info.id)) {
                                    rows.add("⚪ " + info.shortName + ": غير متاح (مفتاح/إعداد ناقص)")
                                    return@forEach
                                }
                                val key = (prefs.getString("api_key_" + info.id, "") ?: "").trim()
                                val account = (prefs.getString("cf_account_id", "") ?: "").trim()
                                val model = (prefs.getString("model_" + info.id, "") ?: "").trim()
                                val reply = runCatching {
                                    LlmClient.ask(
                                        "أجب بكلمة واحدة فقط: تم", key, info.id, emptyList(), model, account,
                                        readTimeoutMs = 15_000
                                    )
                                }.getOrElse { "❌ " + (it.message ?: "خطأ") }
                                rows.add(
                                    (if (LlmClient.isErrorReply(reply)) "❌ " else "✅ ") +
                                        info.shortName + ": " + reply.replace("\n", " ").take(80)
                                )
                            }
                            rows.joinToString("\n")
                        }
                        working = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = T_CARD)
            ) { Text("📡 فحص المزودات", color = T_GOLD) }
            Button(
                enabled = !working,
                onClick = {
                    working = true
                    providerReport = "⏳ أجمع التشخيص…"
                    scope.launch {
                        providerReport = withContext(Dispatchers.IO) { LlmClient.diagnostics() }
                        working = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = T_CARD)
            ) { Text("🧾 تشخيص", color = T_GOLD) }
        }
        if (providerReport.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(providerReport.take(4000), color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(10.dp))
        Text("📦 حزم الجهاز/الخادم", color = T_GOLD, fontWeight = FontWeight.Bold)
        Text(
            "تثبيت الأدوات الناقصة (git/java/gradle/node/python/jq/ffmpeg...) عبر pkg أو apt داخل الجهاز أو الخادم الداخلي.",
            color = T_MUTED,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !working,
                onClick = {
                    working = true
                    scope.launch {
                        toolReport = withContext(Dispatchers.IO) { AiTools.toolchainReport(ToolContext(context, prefs, AiTools.allowWrite(prefs))) }
                        working = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = T_CARD)
            ) { Text(if (working) "…" else "🔍 فحص", color = T_GOLD) }
            Button(
                enabled = !working,
                onClick = {
                    working = true
                    scope.launch {
                        toolReport = withContext(Dispatchers.IO) {
                            listOf("git", "unzip", "curl", "jq").joinToString("\n") { pkg ->
                                val result = ShellTools.install(context, pkg)
                                (if (result.ok) "✅ " else "❌ ") + pkg + ": " + result.output.takeLast(300)
                            }
                        }
                        working = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = T_CARD)
            ) { Text("⬇️ تثبيت الأساسيات", color = T_GOLD) }
        }
        if (toolReport.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(toolReport.take(4000), color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(10.dp))
        Text("الأدوات المتاحة", color = T_GOLD, fontWeight = FontWeight.Bold)
        listOf(
            "🌐 الويب: جلب/API/بحث/تنزيل" to ToolGroup.WEB,
            "🐙 GitHub: قراءة/كتابة/فروع/Issues/PRs" to ToolGroup.GITHUB,
            "📁 الملفات: مساحة عمل/ZIP/CSV" to ToolGroup.FILES,
            "🗄️ SQLite: استعلام/مخطط/JSON" to ToolGroup.DATABASE,
            "🔍 الكود: مقاييس/تعقيد/اعتماديات" to ToolGroup.CODE,
            "🧰 أدوات: JSON/Base64/Hash/Regex" to ToolGroup.UTIL,
            "🔌 الموصلات: Drive/Gmail/Calendar/Notion/Slack" to ToolGroup.CONNECTORS,
            "🔥 Firebase: Firestore/Storage عبر REST" to ToolGroup.FIREBASE,
            "🛡️ الأمان: فحص الأسرار + بوابات الجودة" to ToolGroup.QUALITY
        ).forEach { (label, _) ->
            Text("• $label", color = Color.White, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenGitHub, colors = ButtonDefaults.buttonColors(containerColor = T_CARD)) {
                Text("🐙 GitHub", color = T_GOLD)
            }
            Button(onClick = onOpenSettings, colors = ButtonDefaults.buttonColors(containerColor = T_CARD)) {
                Text("⚙️ الإعدادات", color = T_GOLD)
            }
        }
    }
}

@Composable
private fun toolFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = T_GOLD,
    unfocusedBorderColor = Color(0xFF2A2A2A),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = T_GOLD
)
