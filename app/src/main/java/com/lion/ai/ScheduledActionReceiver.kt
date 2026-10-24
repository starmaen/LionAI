package com.lion.ai

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScheduledActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val at = intent.getLongExtra("at", 0L)
        // إن نفّذه فاحص التطبيق المفتوح قبل المنبه فلا نكرره
        if (!FiredTracker.mark(id, at)) return
        val item = ScheduledItem(
            id = id,
            kind = ActionKind.fromKey(intent.getStringExtra("kind") ?: "reminder"),
            whenMillis = if (at > 0) at else System.currentTimeMillis(),
            name = intent.getStringExtra("name").orEmpty(),
            number = intent.getStringExtra("number").orEmpty(),
            text = intent.getStringExtra("text").orEmpty(),
            repeatMs = intent.getLongExtra("rep", 0L)
        )
        ActionRunner.fire(context, item)
        runCatching { Scheduler.afterFire(context, SecurePrefs.get(context), item) }
    }
}

/** أزرار الإشعار: «بعد 10 دقائق» و«تم». */
class ReminderActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SNOOZE = "com.lion.ai.SNOOZE"
        const val ACTION_DONE = "com.lion.ai.DONE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(id)
        if (intent.action == ACTION_SNOOZE) {
            val minutes = intent.getIntExtra("minutes", 10).coerceAtLeast(1)
            val text = intent.getStringExtra("text").orEmpty()
            runCatching {
                Scheduler.add(
                    context,
                    SecurePrefs.get(context),
                    ScheduledItem(
                        Scheduler.newId(),
                        ActionKind.REMINDER,
                        System.currentTimeMillis() + minutes * 60_000L,
                        "", "", text
                    )
                )
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { Scheduler.rescheduleAll(context, SecurePrefs.get(context)) }
    }
}
