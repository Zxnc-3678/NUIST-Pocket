package cn.nuist.pocket

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import cn.nuist.pocket.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.ZoneId

/** One inexact daily local reminder. Never refreshes school data in the background. */
class EveningReminder : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in actions) return
        val pending = goAsync()
        scope.launch {
            try {
                mutex.withLock {
                    val catchUp = if (intent.action == DELIVER) deliver(context) else true
                    schedule(context, catchUp)
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        private const val DELIVER = "cn.nuist.pocket.EVENING_REMINDER"
        private const val CHANNEL = "tomorrow_courses"
        private const val NOTIFICATION = 2100
        private val zone = ZoneId.of("Asia/Shanghai")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private val actions = setOf(DELIVER, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)

        fun allowed(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        private fun operation(context: Context) = PendingIntent.getBroadcast(context, 2100,
            Intent(context, EveningReminder::class.java).setAction(DELIVER), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun schedule(context: Context, catchUp: Boolean = true) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "明日课程提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "晚间提醒明天第一节课和已排课程，使用本机课表"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
            val alarms = context.getSystemService(AlarmManager::class.java)
            val prefs = AppPreferences(context)
            val token = prefs.captureSchoolSnapshot()
            val track = token.track
            val hasSchedule = loadLocalSchedule(context, track) != null
            prefs.whileSnapshot(token) {
                if (!prefs.eveningEnabled || !allowed(context) || !hasSchedule) {
                    alarms.cancel(operation(context)); manager.cancel(NOTIFICATION); return@whileSnapshot
                }
                val now = LocalDateTime.now(zone)
                val next = if (catchUp && reminderWindow(now, prefs.eveningTime) && prefs.reminderDate(track) != now.toLocalDate().toString())
                    now.plusMinutes(1) else nextEvening(now, prefs.eveningTime)
                // Inexact by design; no SCHEDULE_EXACT_ALARM permission or foreground service.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(zone).toInstant().toEpochMilli(), operation(context))
            }
        }

        /** Retry within the normal window if a new snapshot superseded this delivery. */
        private fun deliver(context: Context): Boolean {
            val prefs = AppPreferences(context)
            val token = prefs.captureSchoolSnapshot()
            val track = token.track
            val now = LocalDateTime.now(zone)
            if (!prefs.eveningEnabled || !allowed(context) || !reminderWindow(now, prefs.eveningTime) || prefs.reminderDate(track) == now.toLocalDate().toString()) return false
            val snapshot = loadLocalSchedule(context, track) ?: return false
            val message = eveningMessage(snapshot, now.toLocalDate().plusDays(1)) ?: return false
            try {
                return !prefs.whileSnapshot(token) {
                    val open = PendingIntent.getActivity(context, 2100,
                        Intent(context, MainActivity::class.java).setAction(TodayWidgetProvider.OPEN_TODAY)
                            .putExtra("date", message.date.toString())
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_reminder)
                        .setContentTitle(message.title).setContentText(message.greeting)
                        .setStyle(Notification.BigTextStyle().bigText(message.body)).setContentIntent(open)
                        .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true)
                        .setPublicVersion(Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_reminder)
                            .setContentTitle("明日课程提醒").setContentText("打开 NUIST Pocket 查看明天安排").build())
                        .build()
                    context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification)
                    prefs.saveReminderDate(track, now.toLocalDate().toString())
                }
            } catch (_: SecurityException) { return false } // Permission can change before notify.
        }
    }
}
