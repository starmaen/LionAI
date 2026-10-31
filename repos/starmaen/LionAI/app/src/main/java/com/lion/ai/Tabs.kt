package com.lion.ai

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** علامة تبويب واحدة: ترتبط بمحادثة ومزود محددين. */
data class ChatTab(
    val id: String,
    val name: String,
    val chatId: String,
    val provider: String,
    val autoRoute: Boolean
)

/** حفظ علامات التبويب واستعادتها في SharedPreferences بصيغة JSON. */
object TabsStore {
    private const val KEY_TABS = "chat_tabs_v1"
    private const val KEY_ACTIVE = "chat_tabs_active_v1"
    const val MAX_TABS = 10

    fun newId(): String = "tab_" + System.currentTimeMillis() + "_" + (100..999).random()

    fun load(prefs: SharedPreferences): List<ChatTab> {
        val raw = prefs.getString(KEY_TABS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChatTab(
                    id = o.optString("id", newId()),
                    name = o.optString("name", "محادثة"),
                    chatId = o.optString("chatId", "default"),
                    provider = o.optString("provider", "gemini"),
                    autoRoute = o.optBoolean("autoRoute", true)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(prefs: SharedPreferences, tabs: List<ChatTab>) {
        val arr = JSONArray()
        tabs.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("chatId", t.chatId)
                    .put("provider", t.provider)
                    .put("autoRoute", t.autoRoute)
            )
        }
        prefs.edit().putString(KEY_TABS, arr.toString()).apply()
    }

    fun loadActive(prefs: SharedPreferences): String = prefs.getString(KEY_ACTIVE, "") ?: ""

    fun saveActive(prefs: SharedPreferences, tabId: String) {
        prefs.edit().putString(KEY_ACTIVE, tabId).apply()
    }
}

/** شريط علامات التبويب أعلى منطقة المحادثة. */
@Composable
fun TabsBar(
    tabs: List<ChatTab>,
    activeTabId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161616))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tabs.forEach { tab ->
            val selected = tab.id == activeTabId
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color(0xFFFFB800) else Color(0xFF262626))
                    .clickable { onSelect(tab.id) }
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tab.name,
                    color = if (selected) Color(0xFF0D0D0D) else Color(0xFFE0E0E0),
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = { onClose(tab.id) },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        "إغلاق العلامة",
                        tint = if (selected) Color(0xFF0D0D0D) else Color(0xFFB0B0B0),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Add, "علامة جديدة", tint = Color(0xFFFFB800))
        }
    }
}
