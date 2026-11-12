package com.lion.ai

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * إدارة موحّدة: حذف أي مزود API أو صفحة ويب أو منصة وسائط أو منصة مضافة/مستوردة،
 * واستعادة المدمجين المحذوفين. الحذف يطبَّق فوراً على كل التطبيق (الإعدادات، المنتقي، الراوتر، الوكيل).
 */
@Composable
fun ProviderManagerSection(prefs: SharedPreferences) {
    var confirm by remember { mutableStateOf<ProviderInfo?>(null) }
    var note by remember { mutableStateOf("") }
    // قراءة PROVIDERS و removedBuiltins داخل التركيب تشترك في حالة الحذف فتتحدث القائمة فوراً.
    val all = PROVIDERS.filter { it.group != ProviderGroup.LION }
    val removed = ProviderRemoval.removedBuiltins()

    SettingsSection(
        title = "🧹 إدارة المنصات والمزودات",
        subtitle = "احذف أي مزود أو صفحة ويب أو منصة وسائط لا تستخدمها؛ يختفي من كل مكان ويمكن استعادة المدمجين.",
        badge = "${all.size} منصة"
    ) {
        if (note.isNotBlank()) {
            Text(note, color = Color(0xFFFFB800), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
        listOf(ProviderGroup.API, ProviderGroup.WEB, ProviderGroup.MEDIA).forEach { group ->
            val items = all.filter { it.group == group }
            if (items.isNotEmpty()) {
                Text(
                    group.icon + " " + group.title,
                    color = Color(0xFFB0B0B0),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                )
                items.forEach { info ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(info.label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { confirm = info }) {
                            Text("🗑️ حذف", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (removed.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("المحذوفة (قابلة للاستعادة)", color = Color(0xFFB0B0B0), fontSize = 12.sp)
            removed.forEach { info ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(info.label, color = Color(0xFF9A9A9A), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        ProviderRemoval.restore(prefs, info.id)
                        note = "تمت استعادة ${info.label}"
                    }) { Text("↩️ استعادة", color = Color(0xFFFFB800), fontSize = 12.sp) }
                }
            }
            TextButton(onClick = {
                ProviderRemoval.restoreAll(prefs)
                note = "تمت استعادة كل المحذوفة"
            }) { Text("↩️ استعادة الكل", color = Color(0xFFFFB800)) }
        }
    }

    confirm?.let { info ->
        val permanent = info.id.startsWith(UserPlatforms.PREFIX) || info.id.startsWith("imp_")
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("حذف ${info.label}؟") },
            text = {
                Column {
                    Text(
                        if (permanent) "سيُحذف نهائياً مع إعداداته ومفتاحه."
                        else "سيختفي من التطبيق كاملاً (الإعدادات والمنتقي والتوجيه التلقائي) ويمكنك استعادته لاحقاً دون إعادة إدخال المفتاح."
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val done = ProviderRemoval.remove(prefs, info.id)
                    note = if (done) "تم حذف ${info.label}" else "لا يمكن حذف آخر مزود دردشة؛ أبقِ واحداً على الأقل."
                    confirm = null
                }) { Text("حذف", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("إلغاء") } }
        )
    }
}
