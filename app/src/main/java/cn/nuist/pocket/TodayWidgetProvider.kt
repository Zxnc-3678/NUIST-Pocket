package cn.nuist.pocket

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import cn.nuist.pocket.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Local-cache-only widget: no login WebView, credentials, or background network requests. */
class TodayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = requestRefresh(context)
    override fun onEnabled(context: Context) = requestRefresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = requestRefresh(context)
    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action !in refreshActions) return
        val pending = goAsync()
        scope.launch {
            try { mutex.withLock { updateAll(context.applicationContext) } }
            finally { pending.finish() }
        }
    }

    companion object {
        const val OPEN_TODAY = "cn.nuist.pocket.OPEN_TODAY"
        const val OPEN_TODOS = "cn.nuist.pocket.OPEN_TODOS"
        private const val REFRESH = "cn.nuist.pocket.REFRESH_WIDGET"
        private const val MIDNIGHT = "cn.nuist.pocket.WIDGET_MIDNIGHT"
        private val zone = ZoneId.of("Asia/Shanghai")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private val refreshActions = setOf(REFRESH, MIDNIGHT, Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)

        fun requestRefresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            if (manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java)).isNotEmpty()) {
                context.sendBroadcast(Intent(context, TodayWidgetProvider::class.java).setAction(REFRESH))
            }
        }

        private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 1,
            Intent(context, TodayWidgetProvider::class.java).setAction(MIDNIGHT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val preferences = AppPreferences(context)
            val token = preferences.captureSchoolSnapshot()
            val track = token.track
            val snapshot = loadLocalSchedule(context, track)
            val now = LocalDateTime.now(zone)
            val agenda = snapshot?.let { widgetAgenda(it, now) }
            // Share one bounded bitmap across all responsive sizes; no image decoding in render.
            val background = loadWidgetBackground(context)
            val appearance = WidgetAppearance(context)
            appearance.syncAppPrimary(appearance.appPrimary(preferences))
            val todos = if (appearance.showTodos) runCatching { widgetTodos(TodoStore(context).load(), now.toLocalDate()) }.getOrDefault(emptyList()) else emptyList()
            ids.forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                val shortHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300).coerceAtLeast(120)
                val tallHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, shortHeight).coerceAtLeast(120)
                val narrowWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280).coerceAtLeast(120)
                val wideWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, narrowWidth).coerceAtLeast(120)
                val sizes = if (Build.VERSION.SDK_INT >= 31) {
                    @Suppress("DEPRECATION")
                    val reported = if (Build.VERSION.SDK_INT >= 33) options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                        else options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                    reported?.filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }?.distinct()?.take(8)
                } else null
                val views = if (Build.VERSION.SDK_INT >= 31 && !sizes.isNullOrEmpty()) {
                    RemoteViews(sizes.associateWith { render(context, snapshot, agenda, now, it.width.toInt(), it.height.toInt(), todos, background, appearance) })
                } else RemoteViews(
                    render(context, snapshot, agenda, now, wideWidth, shortHeight, todos, background, appearance),
                    render(context, snapshot, agenda, now, narrowWidth, tallHeight, todos, background, appearance))
                if (!preferences.whileSnapshot(token) { manager.updateAppWidget(id, views) }) {
                    requestRefresh(context)
                    return
                }
            }
            val next = (agenda?.nextChange ?: now.toLocalDate().plusDays(1).atStartOfDay()).atZone(zone).toInstant().toEpochMilli()
            val alarms = context.getSystemService(AlarmManager::class.java)
            // Only schedule the next class boundary. Native Chronometers tick in the launcher;
            // this app does not run a per-minute service or wake the device just to update a card.
            if (!preferences.whileSnapshot(token) { alarms.set(AlarmManager.RTC, next, alarmIntent(context)) }) requestRefresh(context)
        }

        internal fun render(context: Context, snapshot: WidgetSchedule?, agenda: WidgetAgenda?, now: LocalDateTime, width: Int, height: Int,
                            todos: List<TodoItem> = emptyList(), background: Bitmap? = null, appearance: WidgetAppearance = WidgetAppearance(context)): RemoteViews {
            val view = RemoteViews(context.packageName, R.layout.widget_today)
            val tone = appearance.tone
            val dark = tone == WidgetTone.DARK
            val accent = appearance.accent
            val solidBackground = if (dark) tone.veil else appearance.color
            val accentText = tone.accentText(accent, solidBackground, background != null, appearance.fade, appearance.cardOpacity)
            val cardBackground = if (dark) {
                when (appearance.cardOpacity) {
                    50 -> R.drawable.widget_card_dark_50
                    70 -> R.drawable.widget_card_dark_70
                    85 -> R.drawable.widget_course_dark_85
                    else -> R.drawable.widget_course_dark_100
                }
            } else {
                when (appearance.cardOpacity) {
                    50 -> R.drawable.widget_card_light_50
                    70 -> R.drawable.widget_card_light_70
                    85 -> R.drawable.widget_caption_background
                    else -> R.drawable.widget_course_background
                }
            }
            val policy = widgetLayoutPolicy(width, height, context.resources.configuration.fontScale)
            val entries = agenda?.entries.orEmpty()
            val todoLayout = widgetTodoLayout(width, height, context.resources.configuration.fontScale, entries.size, todos.size)
            val legacyImage = background != null && Build.VERSION.SDK_INT < 31
            view.setViewVisibility(R.id.widget_wallpaper, if (background != null && !legacyImage) View.VISIBLE else View.GONE)
            view.setViewVisibility(R.id.widget_wallpaper_legacy, if (legacyImage) View.VISIBLE else View.GONE)
            view.setViewVisibility(R.id.widget_veil, if (background != null && !legacyImage) View.VISIBLE else View.GONE)
            val colors = listOf(R.drawable.widget_background, R.drawable.widget_background_blue, R.drawable.widget_background_green,
                R.drawable.widget_background_purple, R.drawable.widget_background_warm)
            val panelBackground = if (dark) R.drawable.widget_background_dark
                else colors[WidgetAppearance.colors.indexOf(appearance.color)]
            view.setInt(R.id.widget_panel, "setBackgroundResource", panelBackground)
            if (background != null) {
                if (legacyImage) {
                    val metrics = context.resources.displayMetrics
                    val budget = (widgetImagePixelBudget(metrics.widthPixels, metrics.heightPixels) / 2).coerceAtLeast(1)
                    view.setImageViewBitmap(R.id.widget_wallpaper_legacy,
                        legacyWidgetBackground(background, width, height, appearance.fade, budget, tone.veil))
                }
                else view.setImageViewBitmap(R.id.widget_wallpaper, background)
            }
            view.setInt(R.id.widget_veil, "setBackgroundColor", tone.veilColor(appearance.fade))
            view.setInt(R.id.widget_refresh, "setColorFilter", accentText)
            listOf(R.id.widget_caption, R.id.widget_empty, R.id.widget_todos).forEach {
                view.setInt(it, "setBackgroundResource", cardBackground)
            }
            view.setTextColor(R.id.widget_date, tone.ink)
            view.setTextColor(R.id.widget_week, tone.muted)
            view.setTextColor(R.id.widget_empty, tone.muted)
            view.setTextColor(R.id.widget_todo_heading, accentText)
            if (policy.compact) {
                view.setTextViewTextSize(R.id.widget_date, TypedValue.COMPLEX_UNIT_SP, 13f)
                view.setTextViewTextSize(R.id.widget_week, TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            val date = agenda?.date ?: now.toLocalDate()
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).setAction(OPEN_TODAY)
                    .setData(android.net.Uri.parse("nuist-pocket://widget/day/$date"))
                    .putExtra("date", date.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            view.setOnClickPendingIntent(R.id.widget_root, open)
            view.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 0,
                Intent(context, TodayWidgetProvider::class.java).setAction(REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val day = listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1]
            val week = snapshot?.semester?.weekOn(date)
            val relativeDate = if (date == now.toLocalDate()) "今天" else "明天"
            view.setTextViewText(R.id.widget_date, if (policy.compact) "$relativeDate ${date.format(DateTimeFormatter.ofPattern("M/d"))}"
                else "$relativeDate · ${date.format(DateTimeFormatter.ofPattern("M月d日"))} / 周$day")
            view.setTextViewText(R.id.widget_week, when {
                week == null -> "今日课表"
                week < 1 -> "学期未开始"
                else -> "第${week}周"
            })
            view.removeAllViews(R.id.widget_courses)
            if (entries.size > todoLayout.courseCapacity && week != null && week > 0) {
                view.setTextViewText(R.id.widget_week, "${week}周·${entries.size}课")
            }
            val capacity = todoLayout.courseCapacity
            view.setViewVisibility(R.id.widget_courses, if (entries.isNotEmpty() && capacity > 0) View.VISIBLE else View.GONE)
            entries.take(capacity).forEach { entry ->
                val course = entry.course
                val row = RemoteViews(context.packageName, if (policy.compact) R.layout.widget_course_compact else R.layout.widget_course)
                row.setTextViewText(R.id.widget_start, entry.start?.toLocalTime()?.toString() ?: "待定")
                row.setTextViewText(R.id.widget_end, "${if (policy.compact) "– " else ""}${entry.end?.toLocalTime()?.toString() ?: "待定"}")
                row.setTextViewText(R.id.widget_name, course.displayName)
                row.setInt(R.id.widget_name, "setMaxLines", policy.nameLines)
                row.setInt(R.id.widget_detail, "setMaxLines", policy.detailLines)
                row.setTextViewText(R.id.widget_detail, "第${course.firstPeriod}–${course.lastPeriod}节 | ${course.room.ifBlank { "地点待定" }}${if (policy.compact) "" else " | ${course.teacher.ifBlank { "教师待定" }}"}")
                row.setInt(R.id.widget_course_root, "setBackgroundResource", cardBackground)
                row.setInt(R.id.widget_stripe, "setBackgroundColor",
                    course.customColorHex?.let(android.graphics.Color::parseColor) ?: accent)
                row.setTextColor(R.id.widget_name, tone.ink)
                row.setTextColor(R.id.widget_detail, tone.muted)
                listOf(R.id.widget_start, R.id.widget_end, R.id.widget_countdown, R.id.widget_state).forEach {
                    row.setTextColor(it, accentText)
                }
                val target = entry.countdownTarget
                row.setViewVisibility(R.id.widget_countdown, if (target != null) View.VISIBLE else View.GONE)
                val unknownTime = target == null && (entry.start == null || entry.state == WidgetCourseState.ONGOING)
                row.setViewVisibility(R.id.widget_state, if (unknownTime) View.VISIBLE else View.GONE)
                row.setTextViewText(R.id.widget_state, when {
                    entry.state == WidgetCourseState.ONGOING -> "上课中 · 结束时间待定"
                    entry.start == null -> "时间待定"
                    else -> "待上课"
                })
                row.setChronometerCountDown(R.id.widget_countdown, true)
                val base = SystemClock.elapsedRealtime() + (target?.let { Duration.between(now, it).toMillis() } ?: 0L)
                row.setChronometer(R.id.widget_countdown, base,
                    if (entry.state == WidgetCourseState.IMMINENT) {
                        if (policy.compact) "即将上课 · %s" else "即将上课 · %s 后开始"
                    } else if (policy.compact) "下课倒计时 %s" else "上课中 · %s 后下课", target != null)
                row.setOnClickPendingIntent(R.id.widget_course_root, open)
                view.addView(R.id.widget_courses, row)
            }
            view.setViewVisibility(R.id.widget_empty, if (entries.isEmpty() && !(todoLayout.showSummary && height < 220)) View.VISIBLE else View.GONE)
            view.setTextViewText(R.id.widget_empty, when {
                snapshot == null -> "打开 App，登录或导入课表"
                week != null && week < 1 -> "学期尚未开始，点开查看课表"
                else -> "明天没有排课，安排自己的时间吧"
            })
            view.setViewVisibility(R.id.widget_todos, if (todoLayout.showSummary) View.VISIBLE else View.GONE)
            if (todoLayout.showSummary) {
                val label = when {
                    todos.any { it.date < now.toLocalDate() } -> "待办 · 含逾期"
                    todos.first().date == now.toLocalDate() -> "今天待办"
                    else -> "明天待办"
                }
                view.setTextViewText(R.id.widget_todo_heading, "$label · ${todos.size}项  ›")
                listOf(R.id.widget_todo_one, R.id.widget_todo_two).forEachIndexed { index, id ->
                    view.setTextColor(id, tone.ink)
                    view.setViewVisibility(id, if (index < todoLayout.todoRows) View.VISIBLE else View.GONE)
                    todos.getOrNull(index)?.let { item ->
                        val whenText = if (item.date < now.toLocalDate()) "${item.date.monthValue}/${item.date.dayOfMonth} 逾期" else item.time?.toString().orEmpty()
                        view.setTextViewText(id, "○ ${if (whenText.isEmpty()) "" else "$whenText  "}${item.title}")
                    }
                }
                val openTodos = PendingIntent.getActivity(context, 3,
                    Intent(context, MainActivity::class.java).setAction(OPEN_TODOS)
                        .setData(android.net.Uri.parse("nuist-pocket://widget/todos/${now.toLocalDate()}"))
                        .putExtra("date", now.toLocalDate().toString())
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                view.setOnClickPendingIntent(R.id.widget_todos, openTodos)
            }
            return view
        }
    }
}
