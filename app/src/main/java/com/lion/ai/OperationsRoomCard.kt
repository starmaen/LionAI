package com.lion.ai

import android.content.SharedPreferences
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * بطاقة إعدادات غرفة العمليات: الخيار التلقائي (برمجة فقط) + اختيار المزودات الأساسية.
 * تحفظ مباشرة في SharedPreferences (ops_room_auto / ops_room_core).
 */
@Composable
fun OperationsRoomCard(prefs: SharedPreferences) {
    var enabled by remember { mutableStateOf(prefs.getBoolean(OperationsRoom.PREF_AUTO, false)) }
    var core by remember {
        mutableStateOf(
            (prefs.getString(OperationsRoom.PREF_CORE, "") ?: "")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        )
    }
    // مزودات API التي يملك المستخدم لها مفتاحاً أو لا تحتاج مفتاحاً.
    val candidates = remember {
        PROVIDERS.filter { info ->
            info.group == ProviderGroup.API &&
                (info.keyless || !prefs.getString("api_key_${info.id}", "").isNullOrBlank())
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
            Text("🧠 غرفة العمليات (وضع البرمجة)", color = Color(0xFFFFB800), fontSize = 13.sp)
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                prefs.edit().putBoolean(OperationsRoom.PREF_AUTO, it).apply()
            })
        }
        Text(
            if (enabled) "تلقائي: تُستشار مزوداتك الأساسية بالتوازي ثم ينفّذ منفّذ واحد بالأدوات ويقدّم إجابة واحدة."
            else "يدوي: يعمل مزودك المختار كما هو.",
            color = Color(0xFFB0B0B0), fontSize = 11.sp
        )
        if (enabled) {
            Spacer(Modifier.height(6.dp))
            Text("المزودات الأساسية (حتى 3):", color = Color(0xFFE0E0E0), fontSize = 12.sp)
            if (candidates.isEmpty()) {
                Text("أضف مفتاح مزود واحداً على الأقل أولاً.", color = Color(0xFFB0B0B0), fontSize = 11.sp)
            }
            candidates.forEach { info ->
                val on = info.id in core
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val next = when {
                                on -> core - info.id
                                core.size >= 3 -> core
                                else -> core + info.id
                            }
                            core = next
                            prefs.edit().putString(OperationsRoom.PREF_CORE, next.joinToString(",")).apply()
                        }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(info.shortName, color = if (on) Color(0xFFFFB800) else Color(0xFFE0E0E0), fontSize = 12.sp)
                    Text(if (on) "✅" else "⬜", fontSize = 14.sp)
                }
            }
        }
    }
}
