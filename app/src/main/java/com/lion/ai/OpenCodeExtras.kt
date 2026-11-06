package com.lion.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val XGOLD = Color(0xFFFFB800)
private val XMUTED = Color(0xFF9A9A9A)

/** تقوية OpenCode: مزامنة المنصات، خوادم MCP، وكلاء مخصصون، وقواعد المشروع AGENTS.md. */
@Composable
fun OpenCodeExtrasCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var config by remember { mutableStateOf(JSONObject()) }
    var rules by remember { mutableStateOf("") }
    var mcpName by remember { mutableStateOf("") }
    var mcpTarget by remember { mutableStateOf("") }
    var agentName by remember { mutableStateOf("") }
    var agentDesc by remember { mutableStateOf("") }
    var agentPrompt by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val (c, r) = withContext(Dispatchers.IO) {
            runCatching { OpenCodeConfig.load(context) to OpenCodeConfig.loadRules(context) }
                .getOrDefault(JSONObject() to "")
        }
        config = c
        rules = r
    }

    fun applyConfig(mutate: (JSONObject) -> JSONObject) {
        scope.launch {
            busy = true
            status = "⏳ جارٍ التطبيق وإعادة تشغيل الخادم (قد يستغرق دقيقة)…"
            status = withContext(Dispatchers.IO) {
                runCatching { OpenCodeConfig.applyAndVerify(context, mutate) }
                    .getOrElse { "❌ ${it.message ?: it.javaClass.simpleName}" }
            }
            config = withContext(Dispatchers.IO) { runCatching { OpenCodeConfig.load(context) }.getOrDefault(JSONObject()) }
            busy = false
        }
    }

    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = XGOLD, unfocusedBorderColor = Color(0xFF2A2A2A),
        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = XGOLD
    )

    Column(
        Modifier.fillMaxWidth().background(Color(0xFF1A1A1A), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("🧩 تقوية OpenCode", color = XGOLD, fontWeight = FontWeight.Bold)

        // 1) المنصات المضافة من الطرفية
        Text(
            "أضفت منصة بـ opencode auth login من الطرفية؟ أعد تشغيل الخادم لتظهر موديلاتها في قائمة الاختيار.",
            color = XMUTED, fontSize = 11.sp
        )
        Button(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    status = "⏳ إعادة التشغيل والمزامنة…"
                    status = withContext(Dispatchers.IO) {
                        runCatching { OpenCodeLocal.restartAndSync() }.getOrElse { "❌ ${it.message ?: it.javaClass.simpleName}" }
                    }
                    busy = false
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))
        ) { Text("🔄 إعادة تشغيل ومزامنة المنصات", color = XGOLD, fontSize = 12.sp) }

        // 2) خوادم MCP
        Spacer(Modifier.height(6.dp))
        Text("🔌 خوادم MCP", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(
            "أدوات إضافية لـ OpenCode. أمر محلي مثل: npx -y @modelcontextprotocol/server-filesystem /workspace — أو رابط https لخادم بعيد.",
            color = XMUTED, fontSize = 11.sp
        )
        OpenCodeConfig.mcpList(config).forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name + " · " + (if (m.type == "remote") "بعيد" else "محلي"), color = Color.White, fontSize = 12.sp)
                    Text(m.target, color = XMUTED, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                }
                TextButton(enabled = !busy, onClick = { applyConfig { OpenCodeConfig.removeMcp(it, m.name) } }) {
                    Text("حذف", color = Color(0xFFFF9A9A), fontSize = 11.sp)
                }
            }
        }
        OutlinedTextField(mcpName, { mcpName = it.trim() }, singleLine = true, label = { Text("اسم الخادم (إنجليزي صغير)") }, colors = colors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(mcpTarget, { mcpTarget = it }, singleLine = true, label = { Text("الأمر أو الرابط") }, colors = colors, modifier = Modifier.fillMaxWidth())
        Button(
            enabled = !busy && mcpName.isNotBlank() && mcpTarget.isNotBlank(),
            onClick = {
                val n = mcpName; val t = mcpTarget
                applyConfig { OpenCodeConfig.addMcp(it, n, t) }
                mcpName = ""; mcpTarget = ""
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
        ) { Text("إضافة MCP", color = Color.White) }

        // 3) قواعد المشروع
        Spacer(Modifier.height(6.dp))
        Text("📜 قواعد المشروع (AGENTS.md)", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("يقرؤها OpenCode في كل جلسة جديدة من /workspace، فلا تكرر تعليماتك.", color = XMUTED, fontSize = 11.sp)
        OutlinedTextField(
            rules, { rules = it }, colors = colors, maxLines = 8,
            label = { Text("القواعد") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp)
        )
        Button(
            enabled = !busy,
            onClick = {
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        runCatching { OpenCodeConfig.saveRules(context, rules); "✅ حُفظت القواعد (تُطبَّق على الجلسات الجديدة)" }
                            .getOrElse { "❌ ${it.message ?: it.javaClass.simpleName}" }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
        ) { Text("حفظ القواعد", color = Color.White) }

        // 4) وكلاء مخصصون
        Spacer(Modifier.height(6.dp))
        Text("🤖 وكلاء مخصصون", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("وكيل فرعي بتعليمات خاصة (مراجع كود، مختبِر…) يستدعيه OpenCode عند الحاجة.", color = XMUTED, fontSize = 11.sp)
        OpenCodeConfig.agentList(config).forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, color = Color.White, fontSize = 12.sp)
                    Text(a.description, color = XMUTED, fontSize = 10.sp, maxLines = 2)
                }
                TextButton(enabled = !busy, onClick = { applyConfig { OpenCodeConfig.removeAgent(it, a.name) } }) {
                    Text("حذف", color = Color(0xFFFF9A9A), fontSize = 11.sp)
                }
            }
        }
        OutlinedTextField(agentName, { agentName = it.trim() }, singleLine = true, label = { Text("اسم الوكيل (إنجليزي صغير)") }, colors = colors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(agentDesc, { agentDesc = it }, singleLine = true, label = { Text("وصف قصير") }, colors = colors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            agentPrompt, { agentPrompt = it }, colors = colors, maxLines = 6,
            label = { Text("تعليمات الوكيل") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
        )
        Button(
            enabled = !busy && agentName.isNotBlank() && agentDesc.isNotBlank() && agentPrompt.isNotBlank(),
            onClick = {
                val n = agentName; val d = agentDesc; val p = agentPrompt
                applyConfig { OpenCodeConfig.addAgent(it, n, d, p) }
                agentName = ""; agentDesc = ""; agentPrompt = ""
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
        ) { Text("إضافة وكيل", color = Color.White) }

        if (status.isNotBlank()) {
            Text(
                status,
                color = if (status.startsWith("✅")) Color(0xFF7CC47C) else if (status.startsWith("⏳")) XMUTED else Color(0xFFFF9A9A),
                fontSize = 11.sp
            )
        }
    }
}
