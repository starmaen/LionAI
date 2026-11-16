package com.lion.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ENGINE_CARD = Color(0xFF1A1A1A)
private val ENGINE_GOLD = Color(0xFFFFB800)
private val ENGINE_MUTED = Color(0xFF9A9A9A)
private val ENGINE_OK = Color(0xFF7CC47C)
private val ENGINE_BAD = Color(0xFFE94560)

/**
 * بطاقة «محرّك Lion-1 المدمج»: تثبيت llama.cpp داخل Alpine، تنزيل GGUF، تشغيل/إيقاف،
 * واختبار فعلي. بعد التثبيت يعمل lion-1 بلا مزوّد خلفي ولا إنترنت.
 */
@Composable
fun LionEngineCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { SecurePrefs.get(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(prefs.getString(LionEngine.KEY_MODEL, LionCatalog.DEFAULT_ID) ?: LionCatalog.DEFAULT_ID) }
    var ctxSize by remember { mutableStateOf(LionEngine.ctx(prefs)) }
    var autoStart by remember { mutableStateOf(LionEngine.autoStart(prefs)) }
    var customUrl by remember { mutableStateOf(LionEngine.customUrl(prefs)) }
    var showCustom by remember { mutableStateOf(prefs.getString(LionEngine.KEY_CUSTOM_URL, "").orEmpty().isNotBlank()) }

    var profile by remember { mutableStateOf<LionEngine.DeviceProfile?>(null) }
    var recommended by remember { mutableStateOf<LionEngine.Pick?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }

    // التركيبة المناسبة للجهاز تُطبَّق مرة واحدة عند أول فتح، ثم يبقى اختيار المستخدم محترماً.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val picked = LionEngine.applyRecommendedOnce(context, prefs)
            if (picked != null) {
                selected = picked.modelId
                ctxSize = picked.ctx
                message = "ضُبطت التركيبة المناسبة لجهازك تلقائياً: ${picked.modelId} · سياق ${picked.ctx} (${picked.why})"
            }
        }
    }

    // فحص الحالة على IO فقط: فحص المنفذ يفتح اتصالاً، ولا يجوز حجبه على خيط الواجهة.
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                LionEngine.refreshPhase(context)
                profile = LionEngine.deviceProfile(context)
                recommended = LionEngine.recommendFor(profile?.totalRamMb ?: 0L, profile?.lowRamDevice ?: false)
                warning = LionEngine.ramWarning(context, prefs)
            }
            delay(5_000)
        }
    }

    fun run(label: String, block: () -> String) {
        scope.launch {
            busy = true
            message = "⏳ $label…"
            val result = withContext(Dispatchers.IO) { runCatching(block).getOrElse { "❌ ${it.message ?: it.javaClass.simpleName}" } }
            message = result
            withContext(Dispatchers.IO) { LionEngine.refreshPhase(context) }
            busy = false
        }
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = ENGINE_CARD)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🦁 محرّك Lion-1 المدمج", color = ENGINE_GOLD, fontWeight = FontWeight.Bold)
                Text(
                    LionEngine.phaseLabel(LionEngine.phase),
                    color = when (LionEngine.phase) {
                        LionEngine.Phase.RUNNING, LionEngine.Phase.READY -> ENGINE_OK
                        LionEngine.Phase.ERROR -> ENGINE_BAD
                        else -> ENGINE_MUTED
                    },
                    fontSize = 11.sp
                )
            }
            Text(
                "llama.cpp داخل بيئة Alpine التي يملكها التطبيق + نموذج GGUF في مساحة العمل. " +
                    "بعد التثبيت لا يحتاج lion-1 أي مزوّد خلفي أو مفتاح: الاستدلال على الجهاز، " +
                    "وأدوات التطبيق تعمل بـ function calling أصلي.",
                color = ENGINE_MUTED, fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))

            // ملفّ الجهاز: أرقام فعلية + التركيبة الموصى بها + تحذير الرام
            profile?.let { p ->
                Text(
                    "جهازك: رام ${p.ramText} · متاح الآن ${p.availText} · مساحة حرة ${p.storageText}",
                    color = ENGINE_MUTED, fontSize = 10.sp
                )
            }
            recommended?.let { rec ->
                val different = rec.modelId != selected || rec.ctx != ctxSize
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("الموصى به: ${rec.modelId} · سياق ${rec.ctx} — ${rec.why}", color = ENGINE_OK, fontSize = 10.sp)
                    if (different) {
                        TextButton(enabled = !busy, onClick = {
                            LionEngine.applyRecommended(prefs, rec)
                            selected = rec.modelId
                            ctxSize = rec.ctx
                            message = if (LionEngine.phase == LionEngine.Phase.RUNNING)
                                "طُبّقت التركيبة. أوقف المحرّك ثم شغّله ليُطبَّق النموذج/السياق الجديد."
                            else "✅ طُبّقت التركيبة الموصى بها."
                        }) { Text("طبّق", color = ENGINE_GOLD, fontSize = 10.sp) }
                    }
                }
            }
            warning?.let { Text(it, color = ENGINE_BAD, fontSize = 10.sp) }

            // اختيار النموذج
            LionCatalog.MODELS.forEach { m ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = !busy && selected != m.id) {
                            selected = m.id
                            LionEngine.setModel(prefs, m.id)
                            // المحرّك يحمل النموذج في الذاكرة عند التشغيل: تغيير الملف يحتاج إعادة تشغيل.
                            message = if (LionEngine.phase == LionEngine.Phase.RUNNING)
                                "النموذج تغيّر إلى ${m.label}. اضغط «⏹️ إيقاف» ثم «▶️ تشغيل» ليُحمَّل النموذج الجديد."
                            else "✅ سيُستخدم ${m.label} عند التشغيل."
                        }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (selected == m.id) "◉" else "○", color = if (selected == m.id) ENGINE_GOLD else ENGINE_MUTED, fontSize = 14.sp)
                    Column(Modifier.padding(start = 6.dp)) {
                        Text(m.label, color = Color.White, fontSize = 12.sp)
                        Text(m.note, color = ENGINE_MUTED, fontSize = 10.sp)
                    }
                }
            }
            TextButton(enabled = !busy, onClick = { showCustom = !showCustom }) {
                Text(if (showCustom) "▲ إخفاء النموذج المخصص" else "＋ نموذج مخصص (GGUF من تدريبك)", color = ENGINE_GOLD, fontSize = 11.sp)
            }
            if (showCustom) {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text("رابط GGUF مباشر أو مسار ملف على الجهاز", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = {
                        val name = customUrl.substringAfterLast('/').substringBefore('?').ifBlank { "lion-custom.gguf" }
                        LionEngine.setCustomModel(prefs, customUrl, name)
                        message = if (customUrl.isBlank()) "أُلغي النموذج المخصص (رجوع إلى الكتالوج)."
                        else "✅ سيُستخدم: ${LionEngine.sanitize(name)}"
                    }) { Text("حفظ", color = ENGINE_GOLD, fontSize = 11.sp) }
                    TextButton(enabled = !busy, onClick = {
                        customUrl = ""
                        LionEngine.setCustomModel(prefs, "", "")
                        message = "أُلغي النموذج المخصص."
                    }) { Text("مسح", color = ENGINE_BAD, fontSize = 11.sp) }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text("حجم النموذج الحالي: " + LionEngine.modelSizeText(context, prefs), color = ENGINE_MUTED, fontSize = 11.sp)
            // حجم السياق: النموذج المحلي محدود، والسياق الأكبر يستهلك ذاكرة أكثر.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("السياق:", color = ENGINE_MUTED, fontSize = 11.sp)
                LionEngine.CTX_CHOICES.forEach { size ->
                    val on = ctxSize == size
                    Text(
                        "$size",
                        color = if (on) Color(0xFF121212) else ENGINE_GOLD,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .background(if (on) ENGINE_GOLD else ENGINE_CARD, RoundedCornerShape(10.dp))
                            .clickable(enabled = !busy) { ctxSize = size; LionEngine.setCtx(prefs, size) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Text(
                    if (autoStart) "تشغيل تلقائي ✅" else "تشغيل يدوي",
                    color = ENGINE_GOLD, fontSize = 10.sp,
                    modifier = Modifier
                        .clickable(enabled = !busy) { autoStart = !autoStart; LionEngine.setAutoStart(prefs, autoStart) }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !busy && LinuxEnv.ready(context),
                    onClick = {
                        run("تثبيت محرّك llama.cpp") {
                            val r = LionEngine.installServer(context) { line -> LionEngine.debugLine(line) }
                            (if (r.ok) "✅ ثُبّت محرّك llama.cpp داخل Alpine" else "❌ فشل التثبيت") +
                                "\n" + r.output.takeLast(2500)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ENGINE_CARD)
                ) { Text("تثبيت المحرّك", color = ENGINE_GOLD, fontSize = 11.sp) }

                Button(
                    enabled = !busy && LinuxEnv.ready(context),
                    onClick = {
                        run("تنزيل النموذج") {
                            val ok = LionEngine.downloadModel(context, prefs)
                            if (ok) "✅ النموذج جاهز: " + LionEngine.modelSizeText(context, prefs)
                            else "❌ " + LionEngine.lastError
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ENGINE_CARD)
                ) { Text("⬇️ تنزيل النموذج", color = ENGINE_GOLD, fontSize = 11.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !busy,
                    onClick = {
                        if (LionEngine.phase == LionEngine.Phase.RUNNING) {
                            run("إيقاف المحرّك") { LionEngine.stop() }
                        } else {
                            run("تشغيل المحرّك") {
                                val ok = LionEngine.start(context)
                                if (ok) LionEngine.statusText(context) else "❌ " + LionEngine.lastError
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (LionEngine.phase == LionEngine.Phase.RUNNING) ENGINE_BAD else ENGINE_GOLD
                    )
                ) {
                    Text(
                        if (LionEngine.phase == LionEngine.Phase.RUNNING) "⏹️ إيقاف" else "▶️ تشغيل",
                        color = Color(0xFF121212), fontSize = 11.sp
                    )
                }
                TextButton(enabled = !busy, onClick = { run("اختبار المحرّك") { LionEngine.selfTest(context) } }) {
                    Text("اختبار", color = ENGINE_GOLD, fontSize = 11.sp)
                }
                TextButton(enabled = !busy, onClick = { run("فحص") { LionEngine.statusText(context) } }) {
                    Text("حالة", color = ENGINE_GOLD, fontSize = 11.sp)
                }
            }

            if (LionEngine.progress.isNotBlank()) Text(LionEngine.progress, color = ENGINE_GOLD, fontSize = 11.sp)
            if (LionEngine.lastError.isNotBlank()) Text(LionEngine.lastError, color = ENGINE_BAD, fontSize = 10.sp)
            if (message.isNotBlank()) {
                Text(
                    message,
                    color = Color(0xFFD0D0D0), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (LionEngine.logTail.isNotBlank()) {
                Text(
                    LionEngine.logTail.takeLast(900),
                    color = ENGINE_MUTED, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}
