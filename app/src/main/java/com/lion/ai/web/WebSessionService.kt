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
 * خدمة أمامية خفيفة تعمل فقط أثناء وجود طلب جارٍ إلى صفحة ويب.
 *
 * السبب: عند مغادرة التطبيق كان النظام يخنق الـ WebView أو يقتل العملية، فينقطع الرد في منتصفه.
 * الخدمة لا تفتح شبكة ولا تلمس البيانات؛ هي فقط تُبقي العملية «أمامية» ما دام الطلب جارياً.
 * تُشغَّل من AndroidWebSessionHost عند بدء الطلب وتُوقَف عند انتهاء الطابور.
 */
class WebSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Lion AI")
            .setContentText("جلسة الويب تعمل — بانتظار رد الموقع")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, notification)
            }
        } catch (_: Throwable) {
            // لا نكسر الطلب إن رفض النظام الخدمة الأمامية (مثلاً التطبيق في الخلفية البحتة).
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "جلسات الويب", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL = "lion_web_session"
        private const val ID = 7411

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, WebSessionService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WebSessionService::class.java)) }
        }
    }
}
