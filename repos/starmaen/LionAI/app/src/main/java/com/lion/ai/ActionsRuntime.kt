package com.lion.ai

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.RingtoneManager
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

object AlarmIntents {
    fun forReminder(context: Context, id: Int, text: String, at: Long): Intent =
        Intent(context, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("id", id)
            .putExtra("text", text)
            .putExtra("at", at)
}

object Notifier {
    // قناتان جديدتان: قناة منبّه بصوت المنبه الحقيقي، وقناة عادية للإشعارات الأخرى
    private const val CHANNEL_ALARM = "lion_alarm_v3"
    private const val CHANNEL_GENERAL = "lion_general_v3"

    fun enabled(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun ensureChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarm = NotificationChannel(CHANNEL_ALARM, "منبه التذكيرات", NotificationManager.IMPORTANCE_HIGH)
        alarm.description = "تذكيرات بصوت منبه وإشعار ظاهر على الشاشة"
        alarm.enableVibration(true)
        alarm.vibrationPattern = longArrayOf(0, 700, 400, 700, 400, 700)
        alarm.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        alarm.setSound(
            alarmUri,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        manager.createNotificationChannel(alarm)

        val general = NotificationChannel(CHANNEL_GENERAL, "إشعارات Lion AI", NotificationManager.IMPORTANCE_HIGH)
        general.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        manager.createNotificationChannel(general)
    }

    /**
     * alarmIntent != null يعني تذكيراً بصوت منبه: إشعار يظهر منبثقاً ويكرر الصوت،
     * وعند قفل الشاشة تُفتح شاشة المنبه كاملة.
     */
    fun show(
        context: Context,
        id: Int,
        title: String,
        text: String,
        contentIntent: PendingIntent? = null,
        snoozeText: String? = null,
        alarmIntent: Intent? = null
    ): Boolean {
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannels(manager)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val alarmPending = alarmIntent?.let { PendingIntent.getActivity(context, id, it, flags) }
            val content = contentIntent ?: alarmPending ?: PendingIntent.getActivity(
                context,
                id,
                Intent(context, MainActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                flags
            )
            val builder = NotificationCompat.Builder(
                context,
                if (alarmIntent != null) CHANNEL_ALARM else CHANNEL_GENERAL
            )
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text.lineSequence().firstOrNull().orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(if (alarmIntent != null) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(content)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setAutoCancel(true)
            if (alarmPending != null) {
                builder.setFullScreenIntent(alarmPending, true)
                builder.setTimeoutAfter(60_000L)
            }
            if (snoozeText != null) {
                fun action(code: Int, act: String, minutes: Int): PendingIntent =
                    PendingIntent.getBroadcast(
                        context,
                        id * 4 + code,
                        Intent(context, ReminderActionReceiver::class.java)
                            .setAction(act)
                            .putExtra("id", id)
                            .putExtra("text", snoozeText)
                            .putExtra("minutes", minutes),
                        flags
                    )
                builder.addAction(android.R.drawable.ic_menu_recent_history, "⏰ بعد 10 دقائق", action(1, ReminderActionReceiver.ACTION_SNOOZE, 10))
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "✔ تم", action(2, ReminderActionReceiver.ACTION_DONE, 0))
            }
            val notification = builder.build()
            if (alarmIntent != null) {
                // يكرر الصوت حتى يتفاعل المستخدم أو تنتهي المهلة
                notification.flags = notification.flags or Notification.FLAG_INSISTENT
            }
            manager.notify(id, notification)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/** يمنع تنفيذ العنصر المجدول مرتين (من المنبه ومن فاحص التطبيق المفتوح). */
object FiredTracker {
    private val fired = HashSet<String>()

    @Synchronized
    fun mark(id: Int, at: Long): Boolean = fired.add("$id:$at")
}

object ActionRunner {
    fun fire(context: Context, item: ScheduledItem) {
        when (item.kind) {
            ActionKind.REMINDER -> {
                val task = item.text.ifBlank { "حان موعد التذكير" }
                Notifier.show(
                    context, item.id, "⏰ حان موعد التذكير",
                    task + "\n🕐 الموعد: " + TimeFmt.clock(item.whenMillis),
                    snoozeText = task,
                    alarmIntent = AlarmIntents.forReminder(context, item.id, task, item.whenMillis)
                )
            }

            ActionKind.SMS -> {
                val sent = SmsSender.send(context, item.number, item.text)
                Notifier.show(
                    context,
                    item.id,
                    if (sent) "📱 تم إرسال رسالتك إلى ${item.name}" else "⚠️ تعذر إرسال الرسالة إلى ${item.name}",
                    item.text
                )
            }

            ActionKind.CALL -> {
                val canCall = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED
                val callIntent = Intent(
                    if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                    Uri.fromParts("tel", item.number, null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pending = PendingIntent.getActivity(
                    context, item.id, callIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                Notifier.show(
                    context, item.id,
                    "📞 حان موعد الاتصال بـ ${item.name}",
                    "اضغط هنا للاتصال: ${item.number}",
                    pending
                )
            }
        }
    }

    fun missed(context: Context, item: ScheduledItem) {
        val label = when (item.kind) {
            ActionKind.REMINDER -> "⏰ فاتك تذكير"
            ActionKind.SMS -> "⚠️ لم تُرسل الرسالة المجدولة إلى ${item.name} (فات موعدها)"
            ActionKind.CALL -> "⚠️ فاتك الاتصال المجدول بـ ${item.name}"
        }
        Notifier.show(context, item.id, label, item.text.ifBlank { item.name })
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
                        text = o.optString("text"),
                        repeatMs = o.optLong("rep", 0L)
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
                    .put("rep", it.repeatMs)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    /** يعيد null عند النجاح أو نص الخطأ. */
    fun add(context: Context, prefs: SharedPreferences, item: ScheduledItem): String? {
        write(prefs, list(prefs) + item)
        return schedule(context, item)
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

    /** بعد تنفيذ عنصر: التكراري يُجدول لموعده القادم، وغير التكراري يُحذف. */
    fun afterFire(context: Context, prefs: SharedPreferences, item: ScheduledItem) {
        if (item.repeatMs <= 0) {
            removeFromStore(prefs, item.id)
            return
        }
        val now = System.currentTimeMillis()
        val steps = ((now + 1000 - item.whenMillis) / item.repeatMs) + 1
        val updated = item.copy(whenMillis = item.whenMillis + steps * item.repeatMs)
        write(prefs, list(prefs).filter { it.id != item.id } + updated)
        schedule(context, updated)
    }

    fun rescheduleAll(context: Context, prefs: SharedPreferences) {
        val now = System.currentTimeMillis()
        list(prefs).forEach { item ->
            if (item.whenMillis > now + 2000) {
                schedule(context, item)
            } else {
                ActionRunner.missed(context, item)
                afterFire(context, prefs, item)
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
            .putExtra("at", item.whenMillis)
            .putExtra("rep", item.repeatMs)
        return PendingIntent.getBroadcast(
            context,
            item.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context, item: ScheduledItem): String? {
        return try {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = pendingFor(context, item)
            // setAlarmClock لا يحتاج صلاحية المنبهات الدقيقة، ولا يتأخر في وضع توفير الطاقة
            val show = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(item.whenMillis, show), pending)
            null
        } catch (e: Exception) {
            try {
                val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.whenMillis, pendingFor(context, item))
                null
            } catch (e2: Exception) {
                e2.message ?: e2.javaClass.simpleName
            }
        }
    }
}
