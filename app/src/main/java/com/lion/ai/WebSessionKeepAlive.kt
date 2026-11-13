package com.lion.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * خدمة أمامية خفيفة تبقي العملية حيّة أثناء انتظار رد صفحة ويب. بدونها يجمّد أندرويد التطبيق
 * عند الخروج منه أو تبديل التطبيق فينقطع الرد في منتصفه.
 */
class WebSessionKeepAlive : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "جلسات الويب", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Lion AI")
            .setContentText("بانتظار رد صفحة الويب…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, notification)
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "web_session_keepalive"
        private const val ID = 7421

        fun set(context: Context, on: Boolean) {
            runCatching {
                val intent = Intent(context, WebSessionKeepAlive::class.java)
                if (on) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
            }
        }
    }
}
