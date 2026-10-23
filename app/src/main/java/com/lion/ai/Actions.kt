package com.lion.ai

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class ActionKind(val key: String) {
    REMINDER("reminder"),
    SMS("sms"),
    CALL("call");

    companion object {
        fun fromKey(key: String): ActionKind = values().firstOrNull { it.key == key } ?: REMINDER
    }
}

data class ParsedAction(
    val kind: ActionKind,
    val recipient: String,
    val message: String,
    val triggerAt: Long?
)

data class ParseResult(val valid: Boolean, val action: ParsedAction?)
data class Recipient(val name: String, val number: String, val waNumber: String?)
data class PendingAction(
    val kind: ActionKind,
    val text: String,
    val triggerAt: Long?,
    val candidates: List<Recipient>
)

data class ScheduledItem(
    val id: Int,
    val kind: ActionKind,
    val whenMillis: Long,
    val name: String,
    val number: String,
    val text: String
)

fun Contact.toRecipient(): Recipient {
    val wa = (normalized ?: number).trim()
    val digits = wa.removePrefix("+")
    val ok = wa.startsWith("+") && digits.isNotEmpty() && digits.all { it.isDigit() }
    return Recipient(name, number, if (ok) digits else null)
}

object TimeFmt {
    fun whenText(millis: Long): String =
        SimpleDateFormat("EEEE d MMMM، h:mm a", Locale.forLanguageTag("ar")).format(Date(millis))
}

object TextNorm {
    /** يحافظ على الطول (حرف مقابل حرف) ليمكن استخدام المواضع على النص الأصلي. */
    fun arabic(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ة' -> 'ه'
                    'ى' -> 'ي'
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    else -> c.lowercaseChar()
                }
            )
        }
        return sb.toString()
    }

    fun digits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    fun forMatch(s: String): String =
        arabic(s)
            .filterNot { it in '\u064B'..'\u065F' || it == '\u0640' }
            .replace(Regex("\\s+"), " ")
            .trim()
}

object ActionDetector {
    fun looksLikeCommand(text: String): Boolean {
        if (text.isBlank() || text.length > 300) return false
        val t = TextNorm.forMatch(text)
        if (listOf("ذكرني", "نبهني", "ذكرنى", "نبهنى", "remind me").any { t.contains(it) }) return true
        val hasSend = Regex("(ارسل|ابعث|بعث|راسل|ارسلي)").containsMatchIn(t)
        val hasMsg = Regex("(رساله|رسايل|رسائل|رسالتي|sms|واتس)").containsMatchIn(t)
        if (hasSend && hasMsg) return true
        if (Regex("(^|\\s)راسل").containsMatchIn(t)) return true
        if (Regex("(^|\\s)(اتصل|اتصلي|كلم)(\\s|$)").containsMatchIn(t)) return true
        return false
    }
}

object ContactMatcher {
    fun find(contacts: List<Contact>, query: String): List<Contact> {
        val q = TextNorm.forMatch(query)
        if (q.isBlank()) return emptyList()
        val tokens = q.split(' ').filter { it.isNotBlank() }
        val exact = contacts.filter { TextNorm.forMatch(it.name) == q }
        if (exact.isNotEmpty()) return dedupe(exact)
        val all = contacts.filter { c ->
            val n = TextNorm.forMatch(c.name)
            tokens.all { n.contains(it) }
        }
        if (all.isNotEmpty()) return dedupe(all)
        val any = contacts.filter { c ->
            val n = TextNorm.forMatch(c.name)
            tokens.any { it.length >= 3 && n.contains(it) }
        }
        return dedupe(any)
    }

    private fun dedupe(list: List<Contact>): List<Contact> =
        list.distinctBy { it.name + "|" + it.number.filter { ch -> ch.isDigit() } }.take(6)
}

object ActionParser {
    fun parse(raw: String, now: Long): ParseResult {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return ParseResult(false, null)
        val json = try {
            JSONObject(raw.substring(s, e + 1))
        } catch (ex: Exception) {
            return ParseResult(false, null)
        }
        val kind = when (json.optString("intent", "").trim().lowercase()) {
            "reminder" -> ActionKind.REMINDER
            "sms" -> ActionKind.SMS
            "call" -> ActionKind.CALL
            "none" -> return ParseResult(true, null)
            else -> return ParseResult(false, null)
        }

        fun str(key: String): String {
            if (json.isNull(key)) return ""
            val v = json.optString(key, "").trim()
            return if (v.equals("null", ignoreCase = true)) "" else v
        }

        val delay = if (json.isNull("delay_minutes")) Double.NaN else json.optDouble("delay_minutes", Double.NaN)
        var triggerAt: Long? = null
        if (!delay.isNaN() && delay > 0) {
            triggerAt = now + (delay * 60_000).toLong()
        } else {
            val at = str("at")
            if (at.isNotEmpty()) {
                val parsed = parseDate(at)
                if (parsed != null) {
                    var t = parsed
                    var guard = 0
                    while (t <= now + 1000 && guard < 2) {
                        t += 86_400_000L
                        guard++
                    }
                    if (t > now) triggerAt = t
                }
            }
        }
        return ParseResult(true, ParsedAction(kind, str("recipient"), str("message"), triggerAt))
    }

    private fun parseDate(s: String): Long? {
        for (pattern in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss")) {
            try {
                val f = SimpleDateFormat(pattern, Locale.US)
                f.isLenient = false
                return f.parse(s)?.time
            } catch (e: Exception) {
            }
        }
        return null
    }
}

/** محلل احتياطي بدون إنترنت ولا مفتاح، يفهم الصيغ العربية الشائعة. */
object LocalParser {
    private val WORDS = mapOf(
        "واحد" to 1, "اثنين" to 2, "اثنان" to 2, "ثلاثه" to 3, "ثلاث" to 3,
        "اربعه" to 4, "اربع" to 4, "خمسه" to 5, "خمس" to 5, "سته" to 6, "ست" to 6,
        "سبعه" to 7, "سبع" to 7, "ثمانيه" to 8, "ثماني" to 8, "ثمان" to 8,
        "تسعه" to 9, "تسع" to 9, "عشره" to 10, "عشر" to 10,
        "خمسه عشر" to 15, "خمس عشره" to 15,
        "عشرين" to 20, "ثلاثين" to 30, "اربعين" to 40, "خمسين" to 50
    )
    private val wordAlt = WORDS.keys.sortedByDescending { it.length }.joinToString("|")
    private val hourWordAlt = WORDS.filter { it.value in 1..12 }.keys
        .sortedByDescending { it.length }.joinToString("|")
    private const val MIN = 60_000L

    private class Rel(val millis: Long, val range: IntRange)
    private class Abs(val triggerAt: Long, val range: IntRange)

    fun parse(text: String, now: Long = System.currentTimeMillis()): ParsedAction? {
        val norm = TextNorm.arabic(text)
        val remind = Regex("(ذكرني|نبهني|ذكرنى|نبهنى)").find(norm)
        if (remind != null) return parseReminder(text, now)
        val hasSend = Regex("(ارسل|ابعث|بعث|راسل)").containsMatchIn(norm)
        val hasMsg = Regex("(رساله|رسائل|sms)").containsMatchIn(norm)
        if (hasSend && hasMsg) return parseSms(text, norm, now)
        val call = Regex("(?:^|\\s)(اتصل|اتصلي|كلم)(?=\\s|$)").find(norm)
        if (call != null) return parseCall(text, now)
        return null
    }

    private fun parseReminder(text: String, now: Long): ParsedAction {
        val (at, cleaned) = extractTime(text, now)
        val norm = TextNorm.arabic(cleaned)
        val m = Regex("(ذكرني|نبهني|ذكرنى|نبهنى)").find(norm)
        var body = if (m != null) cleaned.removeRange(m.range) else cleaned
        body = body.replace(Regex("\\s+"), " ").trim().trim('،', ',', '.', ':')
        body = body.replace(Regex("^(?:أن|ان|بأن|بان|أنني|انني|اني|إني|ب|ل)\\s+"), "").trim()
        return ParsedAction(ActionKind.REMINDER, "", body.ifBlank { "تذكير" }, at)
    }

    private fun parseCall(text: String, now: Long): ParsedAction? {
        val (at, cleaned) = extractTime(text, now)
        val norm = TextNorm.arabic(cleaned)
        val m = Regex("(?:^|\\s)(اتصل|اتصلي|كلم)(?=\\s|$)").find(norm) ?: return null
        var rest = cleaned.substring(m.range.last + 1).trim()
        rest = rest.replace(Regex("(?<![\\p{L}])(بـ|ب|الى|إلى|الي|ل|لـ|الرقم|رقم|بالرقم)(?![\\p{L}])"), " ")
        rest = rest.replace(Regex("\\s+"), " ").trim()
        if (rest.isBlank()) return null
        return ParsedAction(ActionKind.CALL, rest, "", at)
    }

    private fun parseSms(text: String, norm: String, now: Long): ParsedAction? {
        val msgWord = Regex("(رساله|رسائل|sms)").find(norm) ?: return null
        var headEnd = -1
        var bodyStart = -1
        val colon = norm.indexOfFirst { it == ':' || it == '：' }
        if (colon >= 0) {
            headEnd = colon
            bodyStart = colon + 1
        } else {
            val d = Regex("\\s(ان|بان|تتضمن|تقول|مضمونها|مضمونه|نصها|فيها|يقول)\\s")
                .find(norm, msgWord.range.last)
            if (d != null) {
                headEnd = d.range.first
                bodyStart = d.range.last + 1
            }
        }
        if (bodyStart < 0 || bodyStart > text.length) return null
        val body = text.substring(bodyStart).trim()
        if (body.isEmpty()) return null
        val (at, head) = extractTime(text.substring(0, headEnd), now)
        val number = Regex("\\+?\\d[\\d\\s\\-]{5,}\\d").find(TextNorm.arabic(head))
        val recipient = if (number != null) {
            number.value.replace(Regex("[\\s\\-]"), "")
        } else {
            head.replace(Regex("(ارسل|أرسل|ابعث|بعث|راسل|ارسلي)"), " ")
                .replace(Regex("(رسالة|رساله|رسائل|صوتية|صوتيه|نصية|نصيه|كتابية|كتابيه|sms)"), " ")
                .replace(Regex("(?<![\\p{L}])(الى|الي|إلى|لـ|ل|الرقم|رقم|للرقم|لرقم)(?![\\p{L}])"), " ")
                .replace(Regex("\\s+"), " ").trim()
        }
        if (recipient.isBlank()) return null
        return ParsedAction(ActionKind.SMS, recipient, body, at)
    }

    private fun extractTime(src: String, now: Long): Pair<Long?, String> {
        val norm = TextNorm.arabic(src)
        val ranges = mutableListOf<IntRange>()
        var at: Long? = null
        val rel = parseRelative(norm)
        if (rel != null) {
            at = now + rel.millis
            ranges.add(rel.range)
        } else {
            val abs = parseAbsolute(norm, now)
            if (abs != null) {
                at = abs.triggerAt
                ranges.add(abs.range)
            }
        }
        if (at != null && rel == null) {
            val afterTomorrow = Regex("بعد\\s+غد").find(norm)
            val day = afterTomorrow
                ?: Regex("(?<!\\p{L})(?:غدا|غداً|غد|بكره|بكرا|باكر|اليوم)(?!\\p{L})").find(norm)
            if (day != null) ranges.add(day.range)
        }
        var cleaned = src
        ranges.sortedByDescending { it.first }.forEach { r -> cleaned = cleaned.removeRange(r) }
        return Pair(at, cleaned.replace(Regex("\\s+"), " ").trim())
    }

    private fun parseRelative(t: String): Rel? {
        Regex("بعد\\s+ثلاثه\\s+ارباع\\s+(?:ال)?ساعه").find(t)?.let { return Rel(45 * MIN, it.range) }
        Regex("بعد\\s+(ربع|نصف|نص|ثلث)\\s+(?:ال)?ساعه").find(t)?.let { m ->
            val v = when (m.groupValues[1]) {
                "ربع" -> 15
                "ثلث" -> 20
                else -> 30
            }
            return Rel(v * MIN, m.range)
        }
        Regex("بعد\\s+ساعه\\s+و\\s*(ربع|نصف|نص)").find(t)?.let { m ->
            val extra = if (m.groupValues[1] == "ربع") 15 else 30
            return Rel((60 + extra) * MIN, m.range)
        }
        Regex("بعد\\s+(دقيقتين|ساعتين|يومين|ثانيتين)").find(t)?.let { m ->
            val ms = when (m.groupValues[1]) {
                "دقيقتين" -> 2 * MIN
                "ساعتين" -> 120 * MIN
                "يومين" -> 2 * 1440 * MIN
                else -> 2000L
            }
            return Rel(ms, m.range)
        }
        Regex("بعد\\s+(?:(\\d+(?:\\.\\d+)?)|($wordAlt))\\s*(ثانيه|ثواني|دقيقه|دقايق|دقائق|ساعه|ساعات|يوم|ايام)")
            .find(t)?.let { m ->
                val n = m.groupValues[1].toDoubleOrNull() ?: WORDS[m.groupValues[2]]?.toDouble()
                if (n != null) return Rel((n * unitMillis(m.groupValues[3])).toLong(), m.range)
            }
        Regex("بعد\\s+(ثانيه|دقيقه|ساعه|يوم)(?!\\p{L})").find(t)?.let { m ->
            return Rel(unitMillis(m.groupValues[1]).toLong(), m.range)
        }
        return null
    }

    private fun unitMillis(unit: String): Double = when (unit) {
        "ثانيه", "ثواني" -> 1000.0
        "دقيقه", "دقايق", "دقائق" -> 60_000.0
        "ساعه", "ساعات" -> 3_600_000.0
        else -> 86_400_000.0
    }

    private fun parseAbsolute(t: String, now: Long): Abs? {
        val m = Regex(
            "(?:ال)?ساعه\\s+(?:(\\d{1,2})(?:[:.](\\d{2}))?|($hourWordAlt))" +
                "(?:\\s+و\\s*(نصف|نص|ربع))?\\s*(صباحا|صباح|مساء|ظهرا|ظهر|عصرا|عصر|ليلا|فجرا|الفجر)?"
        ).find(t) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: WORDS[m.groupValues[3]] ?: return null
        var minute = m.groupValues[2].toIntOrNull() ?: 0
        when (m.groupValues[4]) {
            "نصف", "نص" -> minute += 30
            "ربع" -> minute += 15
        }
        if (hour > 23 || minute > 59) return null
        val period = m.groupValues[5]
        val dayOffset = when {
            Regex("بعد\\s+غد").containsMatchIn(t) -> 2
            Regex("(?<!\\p{L})(?:غدا|غداً|غد|بكره|بكرا|باكر)(?!\\p{L})").containsMatchIn(t) -> 1
            else -> 0
        }
        val hours: List<Int> = when {
            period.startsWith("صباح") || period == "فجرا" || period == "الفجر" ->
                listOf(if (hour == 12) 0 else hour)
            period.startsWith("مساء") || period.startsWith("عصر") ->
                listOf(if (hour < 12) hour + 12 else hour)
            period.startsWith("ظهر") -> listOf(if (hour in 1..5) hour + 12 else hour)
            period == "ليلا" -> listOf(if (hour in 6..11) hour + 12 else if (hour == 12) 0 else hour)
            hour > 12 || hour == 0 -> listOf(hour)
            else -> listOf(hour % 12, hour % 12 + 12)
        }
        val times = hours.map { h ->
            Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        val chosen = if (dayOffset == 0) {
            times.filter { it > now + 1000 }.minOrNull() ?: (times.first() + 86_400_000L)
        } else {
            times.first()
        }
        return Abs(chosen, m.range)
    }
}

object ActionResolver {
    /** يحاول فهم الأمر عبر النموذج المختار (أي مزود، ومنهم OpenCode)، ثم المحلل المحلي كاحتياط. */
    fun resolve(
        text: String,
        apiKey: String,
        provider: String,
        model: String,
        accountId: String
    ): ParsedAction? {
        val now = System.currentTimeMillis()
        if (apiKey.isNotBlank()) {
            val raw = LlmClient.extractIntent(text, apiKey, provider, model, accountId, now)
            if (raw != null) {
                val result = ActionParser.parse(raw, now)
                if (result.valid) {
                    val action = result.action ?: return null
                    if (action.kind == ActionKind.REMINDER && action.triggerAt == null) {
                        val at = LocalParser.parse(text, now)?.triggerAt
                        if (at != null) return action.copy(triggerAt = at)
                    }
                    return action
                }
            }
        }
        return LocalParser.parse(text, now)
    }
}

object SmsSender {
    @Suppress("DEPRECATION")
    fun send(context: Context, phone: String, text: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            val sms = (if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)
            } else {
                null
            }) ?: SmsManager.getDefault()
            val parts = sms.divideMessage(text)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                sms.sendTextMessage(phone, null, text, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}

object Notifier {
    private const val CHANNEL = "lion_reminders"

    fun show(
        context: Context,
        id: Int,
        title: String,
        text: String,
        contentIntent: PendingIntent? = null
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "تذكيرات Lion AI", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val content = contentIntent ?: PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(id, notification) }
    }
}

object Scheduler {
    private const val KEY = "scheduled_items_v1"

    fun newId(): Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

    fun list(prefs: SharedPreferences): List<ScheduledItem> {
        val raw = prefs.getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        val result = mutableListOf<ScheduledItem>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                result.add(
                    ScheduledItem(
                        id = o.optInt("id"),
                        kind = ActionKind.fromKey(o.optString("kind")),
                        whenMillis = o.optLong("at"),
                        name = o.optString("name"),
                        number = o.optString("number"),
                        text = o.optString("text")
                    )
                )
            }
        } catch (e: Exception) {
        }
        return result.sortedBy { it.whenMillis }
    }

    private fun write(prefs: SharedPreferences, items: List<ScheduledItem>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("kind", it.kind.key)
                    .put("at", it.whenMillis)
                    .put("name", it.name)
                    .put("number", it.number)
                    .put("text", it.text)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun add(context: Context, prefs: SharedPreferences, item: ScheduledItem) {
        write(prefs, list(prefs) + item)
        schedule(context, item)
    }

    fun cancel(context: Context, prefs: SharedPreferences, id: Int) {
        val item = list(prefs).firstOrNull { it.id == id }
        if (item != null) {
            runCatching {
                val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                manager.cancel(pendingFor(context, item))
            }
        }
        removeFromStore(prefs, id)
    }

    fun removeFromStore(prefs: SharedPreferences, id: Int) {
        write(prefs, list(prefs).filter { it.id != id })
    }

    fun rescheduleAll(context: Context, prefs: SharedPreferences) {
        val now = System.currentTimeMillis()
        list(prefs).forEach { item ->
            if (item.whenMillis > now + 2000) {
                schedule(context, item)
            } else {
                val label = when (item.kind) {
                    ActionKind.REMINDER -> "⏰ فاتك تذكير (كان الجهاز مغلقاً)"
                    ActionKind.SMS -> "⚠️ لم تُرسل الرسالة المجدولة إلى ${item.name} (كان الجهاز مغلقاً)"
                    ActionKind.CALL -> "⚠️ فاتك الاتصال المجدول بـ ${item.name}"
                }
                Notifier.show(context, item.id, label, item.text.ifBlank { item.name })
                removeFromStore(prefs, item.id)
            }
        }
    }

    private fun pendingFor(context: Context, item: ScheduledItem): PendingIntent {
        val intent = Intent(context, ScheduledActionReceiver::class.java)
            .putExtra("id", item.id)
            .putExtra("kind", item.kind.key)
            .putExtra("name", item.name)
            .putExtra("number", item.number)
            .putExtra("text", item.text)
        return PendingIntent.getBroadcast(
            context,
            item.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context, item: ScheduledItem) {
        runCatching {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = pendingFor(context, item)
            val canExact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.whenMillis, pending)
            } else {
                // لا يحتاج صلاحية ويعمل بدقة حتى في وضع توفير الطاقة
                val show = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(item.whenMillis, show), pending)
            }
        }
    }
}
