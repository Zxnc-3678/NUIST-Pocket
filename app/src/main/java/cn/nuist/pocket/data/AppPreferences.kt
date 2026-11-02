package cn.nuist.pocket.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import java.time.LocalTime
import java.time.LocalDate
import java.time.DayOfWeek

enum class HomeTab(val index: Int, val title: String) {
    DAY(0, "日程"), TIMETABLE(3, "课程表"), ROOMS(1, "教室"), EXAMS(2, "考试")
}

/** UI preferences survive account changes; school data remains in its own store. */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
    var studentTrack: StudentTrack
        get() = StudentTrack.read(prefs.getString("student_track", null))
        set(value) { schoolSnapshots.mutate {
            prefs.edit { putString("student_track", value.name); putLong("class_schedule_generation", classScheduleGeneration + 1) }
        } }
    internal val classScheduleGeneration: Long get() = prefs.getLong("class_schedule_generation", 0L)
    internal fun newClassScheduleSession() {
        schoolSnapshots.mutate {
            val prefix = "suspended_${studentTrack.name}_"
            prefs.edit {
                putLong("class_schedule_generation", classScheduleGeneration + 1)
                prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
            }
        }
    }
    fun suspendedDates(track: StudentTrack, semester: String): Set<LocalDate> =
        prefs.getStringSet("suspended_${track.name}_$semester", emptySet())!!.map(LocalDate::parse).toSet()
    fun setSuspendedDate(track: StudentTrack, semester: String, date: LocalDate, suspended: Boolean) {
        schoolSnapshots.mutate(track) {
            val dates = suspendedDates(track, semester).toMutableSet()
            if (suspended) dates.add(date) else dates.remove(date)
            prefs.edit { putStringSet("suspended_${track.name}_$semester", dates.map(LocalDate::toString).toSet()) }
        }
    }
    internal fun captureSchoolSnapshot(): SchoolSnapshotToken = schoolSnapshots.capture { studentTrack }
    internal fun whileSnapshot(token: SchoolSnapshotToken, action: () -> Unit): Boolean =
        schoolSnapshots.publish(token, { studentTrack }, action)
    fun reminderDate(track: StudentTrack): String? = prefs.getString(track.preferenceKey("last_reminder_date"), null)
    @SuppressLint("ApplySharedPref") // Persist before the receiver finishes; caller uses Dispatchers.IO.
    fun saveReminderDate(track: StudentTrack, date: String) { prefs.edit(commit = true) { putString(track.preferenceKey("last_reminder_date"), date) } }
    fun graduateMonday(semester: String): LocalDate? = runCatching {
        prefs.getString("graduate_monday_$semester", null)?.let(LocalDate::parse)?.takeIf { it.dayOfWeek == DayOfWeek.MONDAY }
    }.getOrNull()
    fun saveGraduateMonday(semester: String, date: LocalDate) {
        require(semester.matches(Regex("[0-9]{4}-[0-9]{4}-[12]")) && date.dayOfWeek == DayOfWeek.MONDAY)
        prefs.edit { putString("graduate_monday_$semester", date.toString()) }
    }
    var seenReleaseVersion: Int
        get() = prefs.getInt("seen_release_version", 0)
        set(value) { prefs.edit { putInt("seen_release_version", value) } }
    var themeAccent: Int
        get() = ThemeAccent.readable(prefs.getInt("theme_accent", ThemeAccent.DEFAULT))
        set(value) { prefs.edit().putInt("theme_accent", ThemeAccent.readable(value)).apply() }
    var wallpaperFade: Int
        get() = prefs.getInt("wallpaper_fade", 45).coerceIn(20, 90)
        set(value) { prefs.edit().putInt("wallpaper_fade", value.coerceIn(20, 90)).apply() }
    var timetableShowInactive: Boolean
        get() = prefs.getBoolean("timetable_show_inactive", true)
        set(value) { prefs.edit { putBoolean("timetable_show_inactive", value) } }
    var sharedTimetableShowInactive: Boolean
        get() = prefs.getBoolean("shared_timetable_show_inactive", true)
        set(value) { prefs.edit { putBoolean("shared_timetable_show_inactive", value) } }
    var timetableCardOpacity: Int
        get() = prefs.getInt("timetable_card_opacity", 90).coerceIn(80, 98)
        set(value) { prefs.edit { putInt("timetable_card_opacity", value.coerceIn(80, 98)) } }
    var homeTab: HomeTab
        get() {
            val track = studentTrack
            val saved = HomeTab.entries.find { it.name == prefs.getString(track.preferenceKey("home_tab"), null) } ?: HomeTab.DAY
            return if (track.isTabVisible(saved.index)) saved else HomeTab.DAY
        }
        set(value) { prefs.edit().putString(studentTrack.preferenceKey("home_tab"), value.name).apply() }
    var eveningEnabled: Boolean
        get() = prefs.getBoolean("evening_enabled", true)
        set(value) { prefs.edit().putBoolean("evening_enabled", value).apply() }
    var eveningTime: LocalTime
        get() = runCatching { LocalTime.parse(prefs.getString("evening_time", "21:00")) }.getOrDefault(LocalTime.of(21, 0))
        set(value) { prefs.edit().putString("evening_time", value.toString()).apply() }
    var permissionAsked: Boolean
        get() = prefs.getBoolean("notification_asked", false)
        set(value) { prefs.edit().putBoolean("notification_asked", value).apply() }
    var classRemindersEnabled: Boolean
        get() = prefs.getBoolean("class_reminders_enabled", false)
        set(value) { prefs.edit { putBoolean("class_reminders_enabled", value) } }
    internal var classQuietMode: QuietMode
        get() = QuietMode.entries.find { it.name == prefs.getString("class_quiet_mode", null) } ?: QuietMode.SILENT
        set(value) { prefs.edit { putString("class_quiet_mode", value.name) } }
    var classLockscreenDetails: Boolean
        get() = prefs.getBoolean("class_lockscreen_details", true)
        set(value) { prefs.edit { putBoolean("class_lockscreen_details", value) } }
}

fun loadLocalSchedule(context: Context, track: StudentTrack = AppPreferences(context).studentTrack): WidgetSchedule? {
    val cache = SchoolCache(context, track)
    cache.load()?.let {
        return WidgetSchedule(it.semester, it.courses, it.periods, "学校缓存", it.fetchedAt, cache.loginRequired,
            AppPreferences(context).suspendedDates(track, it.semester.code))
    }
    val store = PocketStore(context, track)
    val courses = store.savedCourses() ?: return null
    return runCatching {
        WidgetSchedule(Semester(courses.first().semester, store.firstMonday()), courses, store.periods, "本机导入",
            suspendedDates = AppPreferences(context).suspendedDates(track, courses.first().semester))
    }.getOrNull()
}
