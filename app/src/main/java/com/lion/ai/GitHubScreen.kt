package com.lion.ai

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val GOLD = Color(0xFFFFB800)
private val DARK = Color(0xFF0D0D0D)
private val CARD = Color(0xFF1A1A1A)
private val MUTED = Color(0xFF9A9A9A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubScreen(
    prefs: SharedPreferences,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val token = remember { (prefs.getString("github_token", "") ?: "").trim() }
    var repos by remember { mutableStateOf(listOf<GitHubClient.Repo>()) }
    var selected by remember { mutableStateOf<GitHubClient.Repo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    var mode by remember { mutableStateOf("files") }
    var branch by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(listOf<GitHubClient.Entry>()) }
    var file by remember { mutableStateOf<GitHubClient.FileData?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editContent by remember { mutableStateOf("") }
    var commitMessage by remember { mutableStateOf("تحديث من Lion AI") }
    var newFilePath by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    var issues by remember { mutableStateOf(listOf<GitHubClient.Issue>()) }
    var pulls by remember { mutableStateOf(listOf<GitHubClient.Pull>()) }
    var issueDialog by remember { mutableStateOf(false) }
    var issueTitle by remember { mutableStateOf("") }
    var issueBody by remember { mutableStateOf("") }
    var issueDetail by remember { mutableStateOf<GitHubClient.Issue?>(null) }
    var comments by remember { mutableStateOf(listOf<GitHubClient.Comment>()) }
    var commentText by remember { mutableStateOf("") }
    var prDialog by remember { mutableStateOf(false) }
    var prTitle by remember { mutableStateOf("") }
    var prHead by remember { mutableStateOf("") }
    var prBase by remember { mutableStateOf("") }
    var prBody by remember { mutableStateOf("") }

    fun repoOwner(repo: GitHubClient.Repo): Pair<String, String> =
        GitHubClient.parseFullName(repo.fullName)

    fun loadRepos() {
        if (token.isBlank()) return
        scope.launch {
            loading = true
            status = ""
            val result = withContext(Dispatchers.IO) { runCatching { GitHubClient.listRepos(token) } }
            result.onSuccess { repos = it }.onFailure { status = "⚠️ ${it.message}" }
            loading = false
        }
    }

    fun openRepo(repo: GitHubClient.Repo) {
        selected = repo
        mode = "files"
        path = ""
        branch = repo.defaultBranch
        file = null
        editing = false
        status = ""
    }

    fun openFile(entry: GitHubClient.Entry) {
        val repo = selected ?: return
        val (owner, name) = repoOwner(repo)
        scope.launch {
            loading = true
            status = ""
            val result = withContext(Dispatchers.IO) {
                runCatching { GitHubClient.readFile(token, owner, name, entry.path, branch) }
            }
            result.onSuccess {
                file = it
                editContent = it.text
                editing = false
            }.onFailure { status = "⚠️ ${it.message}" }
            loading = false
        }
    }

    fun saveFile() {
        val repo = selected ?: return
        val current = file ?: return
        val (owner, name) = repoOwner(repo)
        scope.launch {
            loading = true
            status = ""
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    GitHubClient.writeFile(
                        token, owner, name, current.path, editContent,
                        commitMessage.ifBlank { "تحديث من Lion AI" }, branch, current.sha.ifBlank { null }
                    )
                }
            }
            result.onSuccess {
                status = "✅ حُفظ ${current.path}"
                editing = false
                val refreshed = withContext(Dispatchers.IO) {
                    runCatching { GitHubClient.readFile(token, owner, name, current.path, branch) }
                }
                refreshed.onSuccess { file = it; editContent = it.text }
            }.onFailure { status = "⚠️ ${it.message}" }
            loading = false
        }
    }

    fun openIssue(issue: GitHubClient.Issue) {
        val repo = selected ?: return
        val (owner, name) = repoOwner(repo)
        issueDetail = issue
        comments = emptyList()
        commentText = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { GitHubClient.listComments(token, owner, name, issue.number) }
            }
            result.onSuccess { comments = it }.onFailure { status = "⚠️ ${it.message}" }
        }
    }

    fun sendComment() {
        val repo = selected ?: return
        val issue = issueDetail ?: return
        if (commentText.isBlank()) return
        val (owner, name) = repoOwner(repo)
        scope.launch {
            val text = commentText
            val result = withContext(Dispatchers.IO) {
                runCatching { GitHubClient.commentIssue(token, owner, name, issue.number, text) }
            }
            result.onSuccess {
                commentText = ""
                comments = withContext(Dispatchers.IO) {
                    runCatching { GitHubClient.listComments(token, owner, name, issue.number) }
                }.getOrDefault(comments)
            }.onFailure { status = "⚠️ ${it.message}" }
        }
    }

    LaunchedEffect(token) { loadRepos() }

    LaunchedEffect(selected?.fullName, mode, path, branch) {
        val repo = selected ?: return@LaunchedEffect
        val (owner, name) = repoOwner(repo)
        loading = true
        status = ""
        try {
            when (mode) {
                "files" -> entries = withContext(Dispatchers.IO) {
                    GitHubClient.listDir(token, owner, name, path, branch)
                }
                "issues" -> issues = withContext(Dispatchers.IO) {
                    GitHubClient.listIssues(token, owner, name)
                }
                "prs" -> pulls = withContext(Dispatchers.IO) {
                    GitHubClient.listPulls(token, owner, name)
                }
            }
        } catch (e: Exception) {
            status = "⚠️ ${e.message}"
        } finally {
            loading = false
        }
    }

    Scaffold(
        containerColor = DARK,
        topBar = {
            TopAppBar(
                title = { Text("🐙 GitHub", color = GOLD, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selected != null) {
                            selected = null
                            file = null
                        } else {
                            onBack()
                        }
                    }) { Icon(Icons.Filled.ArrowBack, "رجوع", tint = GOLD) }
                },
                actions = {
                    if (selected == null) {
                        IconButton(onClick = { loadRepos() }) { Icon(Icons.Filled.Refresh, "تحديث", tint = GOLD) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CARD)
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            if (token.isBlank()) {
                Text("🐙 GitHub", color = GOLD, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "أضف توكن GitHub من الإعدادات لقراءة المستودعات وتعديل الملفات وإنشاء Issues وPRs. " +
                        "الذكاء الاصطناعي يستخدم نفس الأدوات تلقائياً عند الحاجة.",
                    color = Color.White
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenSettings, colors = ButtonDefaults.buttonColors(containerColor = GOLD)) {
                    Text("فتح الإعدادات", color = DARK)
                }
                return@Column
            }

            if (status.isNotBlank()) {
                Text(status, color = if (status.startsWith("✅")) Color(0xFF7CC47C) else Color(0xFFE9A23B), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
            }
            if (loading) {
                Text("⏳ جارٍ التحميل...", color = GOLD, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
            }

            if (selected == null) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ابحث في مستودعاتك...", color = Color(0xFF707070)) },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White),
                    colors = fieldColors()
                )
                Spacer(Modifier.height(8.dp))
                val filtered = repos.filter {
                    query.isBlank() || it.fullName.contains(query, true) || it.description.contains(query, true)
                }
                if (filtered.isEmpty() && !loading) {
                    Text("لا مستودعات ظاهرة. تأكد من صلاحية التوكن.", color = MUTED)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.fullName }) { repo ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { openRepo(repo) },
                            colors = CardDefaults.cardColors(containerColor = CARD)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "📦 ${repo.fullName} ${if (repo.private) "🔒" else "🌍"}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                if (repo.description.isNotBlank()) {
                                    Text(repo.description, color = MUTED, fontSize = 12.sp)
                                }
                                Text(
                                    "الفرع: ${repo.defaultBranch} · ${repo.language.ifBlank { "—" }} · ⭐${repo.stars}",
                                    color = GOLD,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            } else {
                val repo = selected ?: return@Column
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📦 ${repo.fullName}", color = GOLD, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(repo.defaultBranch, color = MUTED, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tabButton("📁 الملفات", mode == "files") { mode = "files"; path = ""; file = null }
                    tabButton("🐞 Issues", mode == "issues") { mode = "issues"; file = null }
                    tabButton("🔀 PRs", mode == "prs") { mode = "prs"; file = null }
                }
                Spacer(Modifier.height(8.dp))

                when (mode) {
                    "files" -> {
                        if (file == null) {
                            OutlinedTextField(
                                value = branch,
                                onValueChange = { branch = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("الفرع", color = MUTED, fontSize = 11.sp) },
                                singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                                colors = fieldColors()
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    if (path.isNotEmpty()) {
                                        path = path.substringBeforeLast('/', "")
                                    }
                                }) { Text("⬆️ أعلى", color = GOLD, fontSize = 12.sp) }
                                Text("/$path", color = MUTED, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = newFilePath,
                                    onValueChange = { newFilePath = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("مسار ملف جديد", color = Color(0xFF707070), fontSize = 12.sp) },
                                    singleLine = true,
                                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                                    colors = fieldColors()
                                )
                                Spacer(Modifier.width(6.dp))
                                TextButton(onClick = {
                                    if (newFilePath.isNotBlank()) {
                                        val target = if (path.isBlank()) newFilePath.trim() else "$path/${newFilePath.trim()}"
                                        file = GitHubClient.FileData(target, "", "", false)
                                        editContent = ""
                                        editing = true
                                        newFilePath = ""
                                    }
                                }) { Text("➕ جديد", color = GOLD, fontSize = 12.sp) }
                            }
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(entries, key = { it.path }) { entry ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                if (entry.type == "dir") {
                                                    path = entry.path
                                                } else {
                                                    openFile(entry)
                                                }
                                            }
                                            .padding(vertical = 10.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(if (entry.type == "dir") "📁" else "📄", fontSize = 16.sp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            entry.name,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (entry.type != "dir") {
                                            Text("${entry.size} B", color = MUTED, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        } else {
                            val shown = file ?: return@Column
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "📄 ${shown.path}",
                                    color = GOLD,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { file = null; editing = false }) {
                                    Text("رجوع", color = GOLD, fontSize = 12.sp)
                                }
                            }
                            if (shown.binary) {
                                Text("ملف ثنائي — لا يمكن عرضه كنص.", color = MUTED)
                            } else if (!editing) {
                                TextButton(onClick = {
                                    editContent = shown.text
                                    editing = true
                                }) { Text("✏️ تعديل", color = GOLD, fontSize = 12.sp) }
                                TextButton(onClick = { deleteTarget = shown.path }) {
                                    Text("🗑️ حذف", color = Color(0xFFE94560), fontSize = 12.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    Text(
                                        shown.text,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            } else {
                                OutlinedTextField(
                                    value = editContent,
                                    onValueChange = { editContent = it },
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                    colors = fieldColors()
                                )
                                Spacer(Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = commitMessage,
                                    onValueChange = { commitMessage = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("رسالة الحفظ", color = MUTED, fontSize = 11.sp) },
                                    singleLine = true,
                                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                                    colors = fieldColors()
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { saveFile() },
                                        colors = ButtonDefaults.buttonColors(containerColor = GOLD)
                                    ) { Text("💾 حفظ ورفع", color = DARK) }
                                    TextButton(onClick = {
                                        editing = false
                                        editContent = shown.text
                                    }) { Text("إلغاء", color = MUTED) }
                                }
                            }
                        }
                    }

                    "issues" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Issues", color = Color.White, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                issueTitle = ""
                                issueBody = ""
                                issueDialog = true
                            }) { Text("➕ Issue", color = GOLD, fontSize = 12.sp) }
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(issues, key = { it.number.toString() + it.isPull }) { issue ->
                                Card(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { openIssue(issue) },
                                    colors = CardDefaults.cardColors(containerColor = CARD)
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(
                                            "#${issue.number} ${issue.title}${if (issue.isPull) " (PR)" else ""}",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text("@${issue.user} · ${issue.state} · 💬${issue.comments}", color = MUTED, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    "prs" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Pull Requests", color = Color.White, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                prTitle = ""
                                prHead = ""
                                prBase = repo.defaultBranch
                                prBody = ""
                                prDialog = true
                            }) { Text("➕ PR", color = GOLD, fontSize = 12.sp) }
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(pulls, key = { it.number }) { pr ->
                                Card(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = CARD)
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text("#${pr.number} ${pr.title}", color = Color.White, fontWeight = FontWeight.Bold)
                                        Text("${pr.head} → ${pr.base} · @${pr.user} · ${pr.state}", color = MUTED, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (issueDialog) {
        val repo = selected
        AlertDialog(
            onDismissRequest = { issueDialog = false },
            containerColor = CARD,
            title = { Text("🐞 Issue جديد", color = GOLD) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = issueTitle,
                        onValueChange = { issueTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("العنوان", color = MUTED, fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White),
                        colors = fieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = issueBody,
                        onValueChange = { issueBody = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("الوصف", color = MUTED, fontSize = 11.sp) },
                        minLines = 4,
                        textStyle = TextStyle(color = Color.White),
                        colors = fieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = repo ?: return@TextButton
                    val (owner, name) = repoOwner(r)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { GitHubClient.createIssue(token, owner, name, issueTitle.trim(), issueBody) }
                        }
                        result.onSuccess {
                            issueDialog = false
                            issues = withContext(Dispatchers.IO) {
                                runCatching { GitHubClient.listIssues(token, owner, name) }
                            }.getOrDefault(issues)
                            status = "✅ أُنشئ Issue #${it.number}"
                        }.onFailure { status = "⚠️ ${it.message}" }
                    }
                }) { Text("إنشاء", color = GOLD) }
            },
            dismissButton = { TextButton(onClick = { issueDialog = false }) { Text("إلغاء", color = MUTED) } }
        )
    }

    issueDetail?.let { issue ->
        AlertDialog(
            onDismissRequest = { issueDetail = null },
            containerColor = CARD,
            title = { Text("#${issue.number} ${issue.title}", color = GOLD, fontSize = 14.sp) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(issue.body.ifBlank { "(لا وصف)" }, color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFF2A2A2A))
                    Spacer(Modifier.height(8.dp))
                    comments.forEach { comment ->
                        Text("@${comment.user}", color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(comment.body, color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = commentText,
                        onValueChange = { commentText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("اكتب تعليقاً...", color = Color(0xFF707070), fontSize = 12.sp) },
                        minLines = 2,
                        textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                        colors = fieldColors()
                    )
                }
            },
            confirmButton = { TextButton(onClick = { sendComment() }) { Text("إرسال", color = GOLD) } },
            dismissButton = { TextButton(onClick = { issueDetail = null }) { Text("إغلاق", color = MUTED) } }
        )
    }

    if (prDialog) {
        val repo = selected
        AlertDialog(
            onDismissRequest = { prDialog = false },
            containerColor = CARD,
            title = { Text("🔀 Pull Request", color = GOLD) },
            text = {
                Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                    listOf(
                        Triple("العنوان", prTitle, { value: String -> prTitle = value }),
                        Triple("الفرع المصدر (head)", prHead, { value: String -> prHead = value }),
                        Triple("الفرع الهدف (base)", prBase, { value: String -> prBase = value })
                    ).forEach { (label, value, onChange) ->
                        OutlinedTextField(
                            value = value,
                            onValueChange = onChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(label, color = MUTED, fontSize = 11.sp) },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                            colors = fieldColors()
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    OutlinedTextField(
                        value = prBody,
                        onValueChange = { prBody = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("الوصف", color = MUTED, fontSize = 11.sp) },
                        minLines = 3,
                        textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                        colors = fieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = repo ?: return@TextButton
                    val (owner, name) = repoOwner(r)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                GitHubClient.createPull(
                                    token, owner, name, prTitle.trim(), prHead.trim(),
                                    prBase.trim(), prBody, false
                                )
                            }
                        }
                        result.onSuccess {
                            prDialog = false
                            pulls = withContext(Dispatchers.IO) {
                                runCatching { GitHubClient.listPulls(token, owner, name) }
                            }.getOrDefault(pulls)
                            status = "✅ أُنشئ PR #${it.number}"
                        }.onFailure { status = "⚠️ ${it.message}" }
                    }
                }) { Text("إنشاء", color = GOLD) }
            },
            dismissButton = { TextButton(onClick = { prDialog = false }) { Text("إلغاء", color = MUTED) } }
        )
    }

    deleteTarget?.let { target ->
        val repo = selected
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = CARD,
            title = { Text("🗑️ حذف ملف", color = Color(0xFFE94560)) },
            text = { Text("سيُحذف «$target» من الفرع $branch ويُنشأ commit جديد.", color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    val r = repo ?: return@TextButton
                    val (owner, name) = repoOwner(r)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                GitHubClient.deleteFile(token, owner, name, target, "حذف $target من Lion AI", branch)
                            }
                        }
                        result.onSuccess {
                            deleteTarget = null
                            file = null
                            entries = withContext(Dispatchers.IO) {
                                runCatching { GitHubClient.listDir(token, owner, name, path, branch) }
                            }.getOrDefault(entries)
                            status = "✅ حُذف $target"
                        }.onFailure { status = "⚠️ ${it.message}" }
                    }
                }) { Text("حذف", color = Color(0xFFE94560)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("إلغاء", color = MUTED) } }
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = GOLD,
    unfocusedBorderColor = Color(0xFF2A2A2A),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = GOLD
)

@Composable
private fun tabButton(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (active) GOLD else CARD,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            color = if (active) DARK else Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
