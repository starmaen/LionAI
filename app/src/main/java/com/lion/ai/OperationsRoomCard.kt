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
import androidx.compose.foundation.layout.width
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
    var on by remember { mutableStateOf(prefs.getBoolean(OperationsRoom.PREF_ENABLED, false)) }
    var chosen by remember {
        mutableStateOf((prefs.getString(OperationsRoom.PREF_CORE, "") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() })
    }
    val apiAvail = remember(on) { PROVIDERS.map { it.id }.filter { !WebChatRuntime.handles(it) && SmartRouter.available(prefs, it) } }
    val webAvail = remember(on) { PROVIDERS.map { it.id }.filter { WebChatRuntime.isWeb(it) && SmartRouter.available(prefs, it) } }
    fun save(list: List<String>) {
        chosen = list
        prefs.edit().putString(OperationsRoom.PREF_CORE, list.joinToString(",")).apply()
    }
    fun toggle(id: String, limit: Int, sameKind: (String) -> Boolean) {
        val cur = chosen.toMutableList()
        if (id in cur) cur.remove(id)
        else {
            if (cur.count(sameKind) >= limit) cur.remove(cur.first(sameKind)) // الأقدم يخرج، فلا يبقى الاختيار عالقاً
            cur.add(id)
        }
        save(cur)
    }
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF151515), RoundedCornerShape(10.dp)).padding(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = on,
                onCheckedChange = { on = it; prefs.edit().putBoolean(OperationsRoom.PREF_ENABLED, it).apply() }
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("🧠 غرفة العمليات المركزية", color = Color(0xFFFFB800), fontSize = 14.sp)
                Text(
                    "خيار ثالث للمهام المعقدة فقط. عند تفعيله يتحول «التلقائي» إلى اجتماع بين عدة مزودات " +
                        "(API والمواقع) ينتهي بقرار واحد معتمد، ويظهر تحت النتيجة «${OperationsRoom.BRAND}» فقط. " +
                        "المهام العادية تبقى على التلقائي المعتاد. لا يعمل في الوضع اليدوي.",
                    color = Color(0xFFB0B0B0), fontSize = 11.sp
                )
            }
        }
        if (on) {
            Spacer(Modifier.height(8.dp))
            Text(
                "المشاركون (اختياري؛ بلا اختيار تُستخدم أفضل المتاحة ومواقع Mistral وGitHub Copilot وArena وDeepSeek التي نجحت جلساتها):",
                color = Color(0xFF9A9A9A), fontSize = 10.sp
            )
            Spacer(Modifier.height(6.dp))
            Text("مزودات API (حتى ${OperationsRoom.MAX_API})", color = Color(0xFFCCCCCC), fontSize = 11.sp)
            if (apiAvail.isEmpty()) Text("لا توجد مزودات API جاهزة بعد.", color = Color(0xFF8A8A8A), fontSize = 10.sp)
            apiAvail.forEach { id ->
                MemberRow(providerInfo(id).shortName, id in chosen) {
                    toggle(id, OperationsRoom.MAX_API) { it in apiAvail }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("مواقع الويب (حتى ${OperationsRoom.MAX_WEB}، تُستشار بالتتابع)", color = Color(0xFFCCCCCC), fontSize = 11.sp)
            if (webAvail.isEmpty()) Text("فعّل المواقع من إعدادات Lion Chat أولاً.", color = Color(0xFF8A8A8A), fontSize = 10.sp)
            webAvail.forEach { id ->
                MemberRow(providerInfo(id).shortName, id in chosen) {
                    toggle(id, OperationsRoom.MAX_WEB) { it in webAvail }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(if (selected) "☑" else "☐", color = Color(0xFFFFB800), fontSize = 14.sp)
        Text(name, color = Color(0xFFE0E0E0), fontSize = 12.sp)
    }
}
