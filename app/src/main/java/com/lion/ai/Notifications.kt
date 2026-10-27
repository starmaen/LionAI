package com.lion.ai

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** إعدادات والتحقق من صلاحية «الوصول إلى الإشعارات». */
object NotificationAccess {
    const val PREFS_CAPTURE = "notif_capture"
    const val ACCESS_SETTINGS_ACTION = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS

    /** التطبيقات المسموح بالتقاط إشعاراتها فقط؛ لا نقرأ بقية إشعارات الجهاز. */
    val ALLOWED_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "org.thunderdog.challegram"
    )

    fun isEnabled(context: Context): Boolean {
        val flat = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        if (flat.isBlank()) return false
        return flat.split(':').any { value ->
            ComponentName.unflattenFromString(value)?.packageName == context.packageName
        }
    }

    fun captureEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREFS_CAPTURE, true)

    fun appName(context: Context, packageName: String): String = when (packageName) {
        "com.whatsapp" -> "WhatsApp"
        "com.whatsapp.w4b" -> "WhatsApp Business"
        "org.telegram.messenger", "org.telegram.messenger.web" -> "Telegram"
        "org.telegram.plus" -> "Telegram Plus"
        "org.thunderdog.challegram" -> "Telegram X"
        else -> runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    fun settingsIntent(): Intent = Intent(ACCESS_SETTINGS_ACTION)

    fun describe(context: Context, prefs: SharedPreferences): String = buildString {
        append("صلاحية الوصول إلى الإشعارات: ").append(if (isEnabled(context)) "مفعّلة ✅" else "غير مفعّلة ❌")
        append("\nالتقاط إشعارات WhatsApp/Telegram: ").append(if (captureEnabled(prefs)) "مفعّل" else "موقوف")
        append("\nالمخزّن: ").append(NotificationStore.count(context)).append(" رسالة")
    }
}

data class StoredNotification(
    val id: Long,
    val key: String,
    val packageName: String,
    val appName: String,
    val conversation: String,
    val sender: String,
    val body: String,
    val postTime: Long,
    val canReply: Boolean,
    val removed: Boolean
)

/**
 * مخزن محلي (SQLite) لإشعارات WhatsApp/Telegram التي مرّت على الجهاز.
 * لا يُخزّن إلا الحقول النصية اللازمة للبحث والرد، ولا يرفع شيئاً لأي سيرفر.
 */
object NotificationStore {
    private const val DB_NAME = "lion_notifications.db"
    private const val DB_VERSION = 1
    private const val LIMIT = 3000

    @Volatile private var helper: Helper? = null

    fun init(context: Context) {
        if (helper == null) {
            synchronized(this) {
                if (helper == null) helper = Helper(context.applicationContext)
            }
        }
    }

    private fun db(context: Context): SQLiteDatabase {
        init(context)
        return helper!!.writableDatabase
    }

    fun add(context: Context, item: StoredNotification): Boolean {
        val values = ContentValues().apply {
            put("nkey", item.key)
            put("pkg", item.packageName)
            put("app", item.appName)
            put("conversation", item.conversation)
            put("sender", item.sender)
            put("body", item.body)
            put("post_time", item.postTime)
            put("can_reply", if (item.canReply) 1 else 0)
            put("removed", 0)
        }
        val id = db(context).insertWithOnConflict("notifications", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (id > 0) prune(context)
        return id > 0
    }

    fun markRemoved(context: Context, key: String) {
        db(context).update(
            "notifications",
            ContentValues().apply { put("removed", 1) },
            "nkey = ?",
            arrayOf(key)
        )
    }

    fun recent(
        context: Context,
        packageName: String = "",
        query: String = "",
        since: Long = 0,
        limit: Int = 100
    ): List<StoredNotification> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (packageName.isNotBlank()) {
            where.add("pkg = ?")
            args.add(packageName)
        }
        if (query.isNotBlank()) {
            where.add("(conversation LIKE ? OR sender LIKE ? OR body LIKE ?)")
            val like = "%" + query.trim() + "%"
            repeat(3) { args.add(like) }
        }
        if (since > 0) {
            where.add("post_time >= ?")
            args.add(since.toString())
        }
        val clause = if (where.isEmpty()) null else where.joinToString(" AND ")
        val cursor = db(context).query(
            "notifications", null, clause, args.toTypedArray(),
            null, null, "post_time DESC", limit.coerceIn(1, 500).toString()
        )
        val out = mutableListOf<StoredNotification>()
        cursor.use {
            while (it.moveToNext()) out.add(read(it))
        }
        return out
    }

    fun chats(context: Context, since: Long = 0, limit: Int = 60): List<Pair<String, Int>> {
        val where = if (since > 0) "post_time >= ?" else null
        val args = if (since > 0) arrayOf(since.toString()) else null
        val cursor = db(context).query(
            "notifications", arrayOf("app", "conversation", "COUNT(*) AS c"),
            where, args, "app, conversation", null, "MAX(post_time) DESC", limit.coerceIn(1, 200).toString()
        )
        val out = mutableListOf<Pair<String, Int>>()
        cursor.use {
            while (it.moveToNext()) {
                val app = it.getString(0).orEmpty()
                val conversation = it.getString(1).orEmpty().ifBlank { "—" }
                out.add("$app · $conversation" to it.getInt(2))
            }
        }
        return out
    }

    fun count(context: Context): Int {
        val cursor = db(context).rawQuery("SELECT COUNT(*) FROM notifications", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun clear(context: Context) {
        db(context).delete("notifications", null, null)
    }

    private fun prune(context: Context) {
        db(context).execSQL(
            "DELETE FROM notifications WHERE id NOT IN " +
                "(SELECT id FROM notifications ORDER BY post_time DESC LIMIT $LIMIT)"
        )
    }

    private fun read(cursor: android.database.Cursor): StoredNotification = StoredNotification(
        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        key = cursor.getString(cursor.getColumnIndexOrThrow("nkey")).orEmpty(),
        packageName = cursor.getString(cursor.getColumnIndexOrThrow("pkg")).orEmpty(),
        appName = cursor.getString(cursor.getColumnIndexOrThrow("app")).orEmpty(),
        conversation = cursor.getString(cursor.getColumnIndexOrThrow("conversation")).orEmpty(),
        sender = cursor.getString(cursor.getColumnIndexOrThrow("sender")).orEmpty(),
        body = cursor.getString(cursor.getColumnIndexOrThrow("body")).orEmpty(),
        postTime = cursor.getLong(cursor.getColumnIndexOrThrow("post_time")),
        canReply = cursor.getInt(cursor.getColumnIndexOrThrow("can_reply")) == 1,
        removed = cursor.getInt(cursor.getColumnIndexOrThrow("removed")) == 1
    )

    fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE notifications(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    nkey TEXT NOT NULL,
                    pkg TEXT NOT NULL,
                    app TEXT NOT NULL,
                    conversation TEXT NOT NULL DEFAULT '',
                    sender TEXT NOT NULL DEFAULT '',
                    body TEXT NOT NULL DEFAULT '',
                    post_time INTEGER NOT NULL,
                    can_reply INTEGER NOT NULL DEFAULT 0,
                    removed INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX uidx_notification ON notifications(nkey, post_time, sender, body)"
            )
            db.execSQL("CREATE INDEX idx_notification_time ON notifications(post_time)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS notifications")
            onCreate(db)
        }
    }
}

/**
 * خدمة الوصول إلى الإشعارات: تقرأ إشعارات WhatsApp/Telegram فقط، وتحفظها
 * محلياً، وتتيح الرد المباشر عبر RemoteInput دون فتح التطبيق.
 * المستخدم يفعّلها يدوياً من إعدادات النظام، ويمكنه إيقاف الالتقاط من التطبيق.
 */
class LionNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        if (item.packageName !in NotificationAccess.ALLOWED_PACKAGES) return
        val prefs = runCatching { SecurePrefs.get(this) }.getOrNull() ?: return
        if (!NotificationAccess.captureEnabled(prefs)) return
        val notification = item.notification ?: return
        // ملخصات المجموعة لا تحتوي نص الرسائل الفعلي؛ نتجاهلها ونبقي الرسائل المفردة.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val appName = NotificationAccess.appName(this, item.packageName)
        extract(item, notification, appName).forEach { NotificationStore.add(this, it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        NotificationStore.markRemoved(this, key)
    }

    private fun extract(
        sbn: StatusBarNotification,
        notification: Notification,
        appName: String
    ): List<StoredNotification> {
        val extras = notification.extras ?: Bundle()
        val fallbackTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val canReply = notification.actions?.any { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        } == true
        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull()
        val conversation = style?.conversationTitle?.toString()
            ?.takeIf { it.isNotBlank() } ?: fallbackTitle.ifBlank { appName }

        if (style != null && style.messages.isNotEmpty()) {
            return style.messages.mapNotNull { message ->
                val body = message.text?.toString().orEmpty().trim()
                if (body.isBlank()) return@mapNotNull null
                val sender = message.sender?.toString().orEmpty().ifBlank { conversation }
                val time = message.timestamp.takeIf { it > 0 } ?: sbn.postTime
                StoredNotification(
                    id = 0,
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appName = appName,
                    conversation = conversation,
                    sender = sender,
                    body = body,
                    postTime = time,
                    canReply = canReply,
                    removed = false
                )
            }.distinctBy { it.sender to it.body to it.postTime }
        }

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: lines.lastOrNull())
            ?.trim().orEmpty()
        if (body.isBlank()) return emptyList()
        return listOf(
            StoredNotification(
                id = 0,
                key = sbn.key,
                packageName = sbn.packageName,
                appName = appName,
                conversation = conversation,
                sender = fallbackTitle.ifBlank { conversation },
                body = body,
                postTime = sbn.postTime,
                canReply = canReply,
                removed = false
            )
        )
    }

    companion object {
        @Volatile private var instance: LionNotificationListener? = null

        fun isConnected(): Boolean = instance != null

        /** يرسل رداً مباشراً على إشعار نشط (WhatsApp/Telegram يدعمان Direct Reply). */
        fun reply(key: String, text: String): Boolean {
            val service = instance ?: return false
            val sbn = runCatching {
                service.activeNotifications?.firstOrNull { it.key == key }
            }.getOrNull() ?: return false
            val action = sbn.notification.actions?.firstOrNull { candidate ->
                candidate.remoteInputs?.any { it.allowFreeFormInput } == true
            } ?: return false
            val remoteInput = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return false
            val intent = Intent()
            val results = Bundle().apply {
                putCharSequence(remoteInput.resultKey, text)
            }
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, results)
            return runCatching {
                action.actionIntent.send(service, 0, intent)
                true
            }.getOrDefault(false)
        }

        fun dismiss(key: String): Boolean {
            val service = instance ?: return false
            return runCatching {
                service.cancelNotification(key)
                true
            }.getOrDefault(false)
        }
    }
}
