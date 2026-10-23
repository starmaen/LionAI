package com.lion.ai

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

class ScheduledActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val kind = ActionKind.fromKey(intent.getStringExtra("kind") ?: "reminder")
        val name = intent.getStringExtra("name").orEmpty()
        val number = intent.getStringExtra("number").orEmpty()
        val text = intent.getStringExtra("text").orEmpty()

        when (kind) {
            ActionKind.REMINDER ->
                Notifier.show(context, id, "⏰ تذكير", text.ifBlank { "تذكير" })

            ActionKind.SMS -> {
                val sent = SmsSender.send(context, number, text)
                Notifier.show(
                    context,
                    id,
                    if (sent) "📱 تم إرسال رسالتك إلى $name" else "⚠️ تعذر إرسال الرسالة إلى $name",
                    text
                )
            }

            ActionKind.CALL -> {
                val canCall = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED
                val callIntent = Intent(
                    if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                    Uri.fromParts("tel", number, null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pending = PendingIntent.getActivity(
                    context, id, callIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                Notifier.show(context, id, "📞 حان موعد الاتصال بـ $name", "اضغط هنا للاتصال: $number", pending)
            }
        }
        runCatching { Scheduler.removeFromStore(SecurePrefs.get(context), id) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { Scheduler.rescheduleAll(context, SecurePrefs.get(context)) }
    }
}
