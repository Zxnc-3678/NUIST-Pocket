package cn.nuist.pocket

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import cn.nuist.pocket.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.format.DateTimeFormatter

internal data class ClassOperationResult(val success: Boolean, val message: String)
internal data class ClassReminderOverview(val next: ClassEvent?, val state: ClassReminderState)

/** Local alarms only: no school requests, server push, foreground service or fake vendor island data. */
internal object ClassReminders {
    const val ALERT_CHANNEL = "class_before_20min"
    // Default importance permits lockscreen display when the system hides low-priority/silent alerts.
    // A new channel ID is necessary: Android preserves the importance of an already-created channel.
    const val STATUS_CHANNEL = "class_quiet_status_v2"
    private const val NOTICE_ID = 2200
    private const val QUIET_ID = 2201
    private const val ERROR_ID = 2202
    private const val TEST_ID = 2203
    private const val PREFIX = "cn.nuist.pocket.CLASS_"
    private const val REFRESH = PREFIX + "REFRESH"
    private const val RESTORE = PREFIX + "RESTORE"
    private const val BACKUP = PREFIX + "BACKUP"
    private const val RESERVE = PREFIX + "RESERVE"
    private const val CANCEL = PREFIX + "CANCEL"
    private const val END = PREFIX + "END"
    private const val HIDE = PREFIX + "HIDE"
    private const val DISMISS = PREFIX + "DISMISS"
    private const val TEST = PREFIX + "TEST"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private val systemActions = buildSet {
        addAll(listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
            NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED))
        if (Build.VERSION.SDK_INT >= 31) add(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
    }

    fun preciseAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun policyAllowed(context: Context): Boolean = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(ALERT_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun requestRefresh(context: Context) { scope.launch { runCatching { refresh(context.applicationContext) } } }
    private suspend fun perform(context: Context, action: String, key: String? = null, mode: QuietMode? = null): ClassOperationResult = mutex.withLock {
        try { process(context, action, key, mode) }
        catch (_: Exception) {
            val message = "预约配置不可用，请检查系统声音状态与授权"
            ClassReminderDiagnostics(context).failed("提醒处理未完成，请重新检查课表与提醒设置")
            problem(context, message); ClassOperationResult(false, message)
        }
    }
    suspend fun refresh(context: Context) = perform(context, REFRESH)
    suspend fun reserve(context: Context, key: String, mode: QuietMode? = null) = perform(context, RESERVE, key, mode)
    suspend fun cancel(context: Context, key: String) = perform(context, CANCEL, key)
    suspend fun cancelAll(context: Context) = perform(context, END)
    suspend fun scheduleCheck(context: Context): ClassOperationResult = mutex.withLock {
        try {
            ensureChannels(context)
            if (!AppPreferences(context).classRemindersEnabled) return@withLock ClassOperationResult(false, "请先开启课前提醒")
            if (!notificationsAllowed(context)) return@withLock ClassOperationResult(false, "请先允许课前提醒通知")
            if (!preciseAllowed(context)) return@withLock ClassOperationResult(false, "请先允许闹钟和提醒，系统才可准时发送")
            val at = System.currentTimeMillis() + 60_000
            check(ClassReminderDiagnostics(context).testScheduled(at))
            val precise = schedule(context.getSystemService(AlarmManager::class.java), context, at, TEST)
            ClassOperationResult(true, if (precise) "已安排1分钟后的测试提醒，现在可退出应用并锁屏；不会改变声音"
                else "准时权限已变化，测试可能延迟，请重新允许闹钟和提醒")
        } catch (_: Exception) {
            ClassReminderDiagnostics(context).testScheduled(0)
            ClassOperationResult(false, "未能安排测试提醒，请检查闹钟和提醒权限")
        }
    }
    suspend fun event(context: Context, key: String): ClassEvent? = mutex.withLock {
        events(context, AppPreferences(context).studentTrack, System.currentTimeMillis()).firstOrNull { it.key == key }
    }

    suspend fun overview(context: Context): ClassReminderOverview = mutex.withLock {
        val prefs = AppPreferences(context)
        val state = runCatching { ClassReminderStore(context).load() }.getOrElse {
            ClassReminderState(error = "预约记录无法读取，请检查系统声音状态")
        }
        val now = System.currentTimeMillis()
        val events = events(context, prefs.studentTrack, now)
        ClassReminderOverview(events.firstOrNull { it.startsAt > now }, state)
    }

    fun receive(context: Context, intent: Intent, finished: () -> Unit) {
        val action = intent.action
        if (action !in systemActions && action !in setOf(REFRESH, RESTORE, BACKUP, RESERVE, CANCEL, END, HIDE, DISMISS, TEST)) {
            finished(); return
        }
        val key = intent.data?.takeIf { it.scheme == "nuist-pocket" && it.host == "class-action" }?.lastPathSegment
        val requestedMode = intent.data?.pathSegments?.takeIf { it.size == 3 }?.get(1)
            ?.let { value -> QuietMode.entries.find { it.name == value } }
        val receivedAt = System.currentTimeMillis()
        // goAsync() retains the broadcast/process, not CPU wakefulness after onReceive returns.
        // Bridge to the IO thread while the alarm's wake lock is still held. Never turn on the screen.
        val wake = runCatching {
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NUISTPocket:ClassReminder")
                .apply { acquire(9_000) }
        }.getOrNull()
        scope.launch {
            try {
                ClassReminderDiagnostics(context).received(receivedAt)
                val result = mutex.withLock {
                    if (action == TEST) deliverCheck(context.applicationContext)
                    else process(context.applicationContext, action ?: REFRESH, key, requestedMode)
                }
                if (!result.success && action in setOf(RESERVE, CANCEL, END)) problem(context, result.message)
            } catch (_: Exception) {
                ClassReminderDiagnostics(context).failed("后台提醒处理未完成，请检查课表与提醒设置")
                // Keep a retry if processing failed before the next plan could be registered.
                runCatching { schedule(context.getSystemService(AlarmManager::class.java), context, System.currentTimeMillis() + 60_000, REFRESH) }
                problem(context, "课前提醒配置不可用，请打开设置检查预约与系统声音")
            } finally {
                try { runCatching { if (wake?.isHeld == true) wake.release() } }
                finally { finished() }
            }
        }
    }

    private fun events(context: Context, track: StudentTrack, now: Long): List<ClassEvent> {
        val snapshot = loadLocalSchedule(context, track) ?: return emptyList()
        return events(snapshot, track, now, AppPreferences(context).classScheduleGeneration)
    }

    private fun events(snapshot: WidgetSchedule, track: StudentTrack, now: Long, generation: Long): List<ClassEvent> {
        val date = Instant.ofEpochMilli(now).atZone(ClassZone).toLocalDate()
        return (0L..7L).flatMap { classEvents(snapshot, track, date.plusDays(it), generation) }
    }

    private fun process(context: Context, action: String, key: String? = null, requestedMode: QuietMode? = null): ClassOperationResult {
        ensureChannels(context)
        val prefs = AppPreferences(context)
        val token = prefs.captureSchoolSnapshot()
        val now = System.currentTimeMillis()
        val store = ClassReminderStore(context)
        var state = store.load()
        val localSchedule = if (prefs.classRemindersEnabled) loadLocalSchedule(context, token.track) else null
        val allEvents = localSchedule?.let { events(it, token.track, now, prefs.classScheduleGeneration) }.orEmpty()
        var result = ClassOperationResult(true, "已更新预约")
        val published = prefs.whileSnapshot(token) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val device = ClassQuietDevice(context, store)
            var reservations = validReservations(state.reservations, allEvents, now)
            var deliveries = state.deliveries.filter { it.endsAt > now - 48 * 60 * 60_000L }.takeLast(256)
            when (action) {
                RESERVE -> {
                    val event = allEvents.firstOrNull { it.key == key }
                    val mode = requestedMode ?: prefs.classQuietMode
                    val problem = when {
                        !prefs.classRemindersEnabled -> "请先在设置开启课前提醒"
                        event == null -> "课程或培养类型已变化，请重新打开最新提醒"
                        !preciseAllowed(context) -> "请先允许准时提醒，再预约上课生效"
                        !device.hasPolicyAccess -> "请先允许声音与勿扰控制"
                        else -> bookingProblem(event, reservations, mode, now)
                            ?: if (mode == QuietMode.DND) runCatching { device.ensureRule(); null }
                                .getOrElse { "无法准备上课勿扰，请检查系统授权并启用上课勿扰规则" } else null
                    }
                    if (problem != null) result = ClassOperationResult(false, problem)
                    else if (event != null) {
                        reservations = reservations.filterNot { it.key == key } + event.reservation(mode)
                        state = state.copy(error = null)
                        result = ClassOperationResult(true, if (now >= event.startsAt - CLASS_QUIET_MARGIN_MS)
                            "已进入${mode.title}时段，下课后1分钟结束" else
                            "已预约${mode.title}，${clock(event.startsAt - CLASS_QUIET_MARGIN_MS)}生效")
                    }
                }
                CANCEL -> reservations = reservations.filterNot { it.key == key }
                END -> {
                    if (key == null) reservations = emptyList()
                    else if (state.lease?.id == key) {
                        val activeKeys = activeQuietWindow(reservations, now)?.keys.orEmpty()
                        reservations = reservations.filterNot { it.key in activeKeys }
                    } else result = ClassOperationResult(false, "预约状态已变化，请查看最新通知")
                }
                HIDE -> if (state.lease?.id == key) state = state.copy(hiddenUntil = state.lease?.endsAt ?: now)
                DISMISS -> deliveries = deliveries.map { if (it.key == key) it.copy(dismissed = true) else it }
            }
            state = state.copy(reservations = reservations, deliveries = deliveries)
            store.save(state)
            // Register every future node before notification/audio work; one failed wake must not
            // break the chain and leave all later classes without an alarm.
            val alarms = context.getSystemService(AlarmManager::class.java)
            val times = if (prefs.classRemindersEnabled && localSchedule != null)
                classWakeTimes(allEvents, reservations, deliveries, now) else emptyList()
            val precise = ClassReminderAlarms(context).replace(times,
                { slot, at -> schedule(alarms, context, at, REFRESH, "slot-$slot") },
                { slot -> alarms.cancel(operation(context, REFRESH, "slot-$slot")) })
            // Retire the pre-fix single chained alarm only after the replacement is armed.
            alarms.cancel(operation(context, REFRESH))
            ClassReminderDiagnostics(context).scheduled(times.firstOrNull() ?: 0, precise)
            if (!prefs.classRemindersEnabled) {
                alarms.cancel(operation(context, TEST))
                ClassReminderDiagnostics(context).testScheduled(0)
                manager.cancel(TEST_ID)
            }
            var completion: String? = null
            val window = activeQuietWindow(reservations, now)
            val oldLease = state.lease
            if (oldLease != null && (window == null || oldLease.mode != window.mode || window.startsAt > oldLease.endsAt)) {
                try {
                    completion = restoreQuiet(oldLease, device, action in setOf(CANCEL, END)).message
                    state = state.copy(lease = null, hiddenUntil = 0, error = null); store.save(state)
                } catch (_: Exception) {
                    state = state.copy(error = "未能自动恢复，请检查声音与勿扰权限及系统声音状态"); store.save(state)
                }
            }
            if (window != null && state.lease == null) {
                if (!device.hasPolicyAccess || !preciseAllowed(context)) {
                    state = state.copy(reservations = state.reservations.filterNot { it.key in window.keys },
                        error = "预约权限未允许，本次课程没有自动切换声音"); store.save(state)
                    problem(context, requireNotNull(state.error))
                } else try {
                    check(armRecovery(context, window.endsAt)) { "准时提醒权限已变化" }
                    beginQuiet(window.mode, window.endsAt, device) { lease ->
                        state = state.copy(lease = lease, hiddenUntil = 0, error = null); store.save(state)
                    }
                } catch (_: Exception) {
                    // Preserve the write-ahead lease: an interrupted system call might already have changed audio.
                    state = state.copy(error = "系统未能完成声音切换，请检查声音状态与勿扰规则",
                        reservations = if (state.lease == null) state.reservations.filterNot { it.key in window.keys } else state.reservations)
                    store.save(state)
                    problem(context, requireNotNull(state.error))
                }
            } else if (window != null && state.lease != null && state.lease?.mode == window.mode && window.startsAt <= state.lease!!.endsAt) {
                state = state.copy(lease = state.lease!!.copy(endsAt = window.endsAt)); store.save(state)
            }
            state.lease?.let { lease -> armRecovery(context, if (lease.endsAt > now && state.error == null) lease.endsAt else now + 60_000) }
                ?: cancelRecovery(context)

            val activatedKeys = state.reservations.filter { it.activatesAt <= now }.map { it.key }.toSet()
            val currentKeys = allEvents.filter { it.startsAt > now && it.key !in activatedKeys }.map { it.key }.toSet()
            state.deliveries.filter { it.key !in currentKeys }.forEach { manager.cancel(it.key, NOTICE_ID) }
            if (prefs.classRemindersEnabled && notificationsAllowed(context)) {
                for (event in allEvents.filter { now >= it.remindAt && now < it.startsAt }) {
                    val delivered = state.deliveries.firstOrNull { it.key == event.key }
                    if (delivered?.dismissed == true) continue
                    val booking = state.reservations.firstOrNull { it.key == event.key }
                    if (booking != null && booking.activatesAt <= now) continue
                    try {
                        manager.notify(event.key, NOTICE_ID, upcoming(context, event, booking, prefs, now))
                        ClassReminderDiagnostics(context).submitted(now)
                        if (delivered == null) {
                            state = state.copy(deliveries = (state.deliveries + ClassDelivery(event.key, event.endsAt)).takeLast(256))
                            store.save(state)
                        }
                    } catch (_: SecurityException) { /* Notification permission may change between validation and notify. */ }
                }
            }
            val lease = state.lease
            if (lease != null && state.hiddenUntil <= now) {
                ongoing(context, state, activeQuietWindow(state.reservations, now), prefs)
            } else if (lease == null && completion != null) finished(context, completion, now)
            else if (lease == null) manager.cancel(QUIET_ID)
            if (state.error != null && action in setOf(RESERVE, CANCEL, END) && result.success)
                result = ClassOperationResult(false, requireNotNull(state.error))
            else if (result.success && action in setOf(CANCEL, END))
                result = ClassOperationResult(true, completion ?: "已取消预约")
        }
        if (!published) { requestRefresh(context); return ClassOperationResult(false, "课表已更新，请使用最新通知重新预约") }
        return result
    }

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "课前20分钟提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "即将上课的课程、地点与本次静音或勿扰预约"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        manager.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "课程声音预约状态", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "已预约、上课生效及下课恢复的状态，不响铃"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        })
    }

    /** Uses the same receiver, scheduling API, channel and compact layout; never books or changes audio. */
    private fun deliverCheck(context: Context): ClassOperationResult {
        val diagnostics = ClassReminderDiagnostics(context)
        val now = System.currentTimeMillis()
        val requested = diagnostics.testAt
        if (requested == 0L || now < requested || !AppPreferences(context).classRemindersEnabled)
            return ClassOperationResult(false, "测试已取消或尚未到时间")
        ensureChannels(context)
        if (!notificationsAllowed(context)) {
            diagnostics.failed("测试触发了，但系统通知或课前提醒通道未允许")
            diagnostics.testScheduled(0)
            return ClassOperationResult(false, "测试触发了，但通知未允许")
        }
        val title = "课前提醒后台测试"
        val details = "后台定时任务已触发 · 仅测试，不改变声音"
        val notification = Notification.Builder(context, ALERT_CHANNEL).setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(title).setContentText(details)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(classReminderCompact(context, title, details, "打开设置", setup(context)))
            .setCustomBigContentView(classReminderExpanded(context, title, details, "看到这条通知说明本次后台测试已送达。"))
            .setContentIntent(setup(context)).setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setWhen(now).setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L).build()
        context.getSystemService(NotificationManager::class.java).notify(TEST_ID, notification)
        diagnostics.testSubmitted(now)
        return ClassOperationResult(true, "测试通知已提交系统")
    }

    private fun operation(context: Context, action: String, key: String? = null, mode: QuietMode? = null): PendingIntent = PendingIntent.getBroadcast(context, 2200,
        Intent(context, ClassReminderReceiver::class.java).setAction(action)
            .setData(Uri.parse("nuist-pocket://class-action/${action.substringAfterLast('.')}/${mode?.name?.plus("/") ?: ""}${key ?: "alarm"}")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @SuppressLint("ScheduleExactAlarm") // Guarded by canScheduleExactAlarms; permission can still be revoked concurrently.
    private fun schedule(alarms: AlarmManager, context: Context, at: Long, action: String, key: String? = null): Boolean {
        if (preciseAllowed(context)) try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation(context, action, key)); return true
        } catch (_: SecurityException) { /* Fall back for reminders/restoration; never begin a new quiet lease without exact access. */ }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation(context, action, key))
        return false
    }

    private fun armRecovery(context: Context, at: Long): Boolean {
        val alarms = context.getSystemService(AlarmManager::class.java)
        // Independent inexact fallback remains useful if a later exact alarm is removed by the system.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 120_000, operation(context, BACKUP))
        return schedule(alarms, context, at, RESTORE)
    }
    private fun cancelRecovery(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(operation(context, RESTORE)); alarms.cancel(operation(context, BACKUP))
    }

    private fun setup(context: Context, key: String? = null, mode: QuietMode? = null, confirmOnly: Boolean = false): PendingIntent = PendingIntent.getActivity(context, 2200,
        Intent(context, ClassQuietSetupActivity::class.java).setData(Uri.parse("nuist-pocket://class-setup/${mode?.name ?: "settings"}/${key ?: "settings"}/${if (confirmOnly) "details" else "book"}"))
            .putExtra("class_key", key).putExtra("quiet_mode", mode?.name).putExtra("auto_reserve", !confirmOnly),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(context: Context, title: String, action: String, key: String, needsSetup: Boolean = false,
                       mode: QuietMode? = null): Notification.Action {
        val pending = if (needsSetup) setup(context, key, mode) else operation(context, action, key, mode)
        val builder = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_reminder), title, pending)
        if (Build.VERSION.SDK_INT >= 31) builder.setAuthenticationRequired(true)
        return builder.build()
    }

    private fun upcoming(context: Context, event: ClassEvent, saved: QuietReservation?, prefs: AppPreferences, now: Long): Notification {
        val title = "即将上课 · ${clock(event.startsAt)}"
        val details = "${clock(event.startsAt)}–${clock(event.endsAt)} · ${event.room}"
        val note = saved?.let { "已预约${it.mode.title}，${clock(it.activatesAt)}–${clock(it.restoresAt)}生效" }
            ?: "可预约${prefs.classQuietMode.title}，上课前1分钟生效，下课后1分钟结束"
        val builder = Notification.Builder(context, ALERT_CHANNEL).setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(title).setContentText("${event.title} · ${event.room}")
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(classReminderCompact(context, event.title, details,
                if (saved == null) "预约${prefs.classQuietMode.title}" else "管理预约",
                if (saved == null) setup(context, event.key, prefs.classQuietMode) else setup(context)))
            .setCustomBigContentView(classReminderExpanded(context, event.title, details, note))
            .setCategory(Notification.CATEGORY_EVENT).setOnlyAlertOnce(true).setAutoCancel(false)
            .setWhen(event.startsAt).setUsesChronometer(true).setChronometerCountDown(true)
            .setContentIntent(if (saved == null) setup(context, event.key, prefs.classQuietMode, confirmOnly = true) else setup(context))
            .setDeleteIntent(operation(context, DISMISS, event.key))
            .setTimeoutAfter((event.startsAt - now).coerceAtLeast(1))
            .setVisibility(if (prefs.classLockscreenDetails) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(context, ALERT_CHANNEL).setSmallIcon(R.drawable.ic_reminder)
                .setContentTitle(title).setContentText("打开应用查看课程与预约").build())
        if (saved != null) builder.addAction(action(context, "取消${saved.mode.title}预约", CANCEL, event.key))
        else builder.addAction(action(context, "预约${prefs.classQuietMode.title}", RESERVE, event.key,
            !preciseAllowed(context) || !policyAllowed(context), prefs.classQuietMode))
        return builder.build()
    }

    private fun ongoing(context: Context, state: ClassReminderState, window: QuietWindow?, prefs: AppPreferences) {
        val lease = state.lease ?: return
        val title = if (state.error == null) "课程${lease.mode.title}时段 · 至${clock(lease.endsAt)}" else "请检查系统声音状态"
        val body = state.error ?: "${window?.titles?.joinToString("、") ?: "课程预约"}\n下课后1分钟结束本次预约，系统手动设置优先"
        val builder = Notification.Builder(context, STATUS_CHANNEL).setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_EVENT)
            .setWhen(lease.endsAt).setUsesChronometer(true).setChronometerCountDown(true)
            .setContentIntent(setup(context)).setDeleteIntent(operation(context, HIDE, lease.id))
            .setVisibility(if (prefs.classLockscreenDetails) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(context, STATUS_CHANNEL).setSmallIcon(R.drawable.ic_reminder)
                .setContentTitle(title).setContentText("下课后1分钟结束本次预约").build())
            .addAction(action(context, "结束本次${lease.mode.title}", END, lease.id))
        // Keep recovery visible until restoration is confirmed, even if an OEM delays the end alarm.
        runCatching { context.getSystemService(NotificationManager::class.java).notify(QUIET_ID, builder.build()) }
    }

    private fun finished(context: Context, message: String, now: Long) {
        val notification = Notification.Builder(context, STATUS_CHANNEL).setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle("课程预约已结束").setContentText(message).setContentIntent(setup(context))
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setAutoCancel(true)
            .setWhen(now).setTimeoutAfter(60_000).build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(QUIET_ID, notification) }
    }
    private fun problem(context: Context, message: String) {
        ensureChannels(context)
        val notification = Notification.Builder(context, STATUS_CHANNEL).setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle("课程预约需要检查").setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message)).setContentIntent(setup(context))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ERROR_ID, notification) }
    }
    internal fun clock(at: Long): String = Instant.ofEpochMilli(at).atZone(ClassZone).format(time)
}

class ClassReminderReceiver : BroadcastReceiver() {
    @SuppressLint("UnsafeProtectedBroadcastReceiver") // receive() rejects all actions outside its explicit system/private allowlist before doing any work.
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        ClassReminders.receive(context, intent) { pending.finish() }
    }
}

/** Shared app lifecycle entry point; each reminder retains its own alarm, channel and settings. */
internal suspend fun refreshReminders(context: Context) {
    EveningReminder.schedule(context)
    ClassReminders.refresh(context)
}
