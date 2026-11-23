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

enum class ThemeMode(val title: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

enum class CourseCardStyle(val title: String) {
    ClassicBar("清爽彩条"), MorandiFill("莫兰迪柔彩"), GlassOutline("高透描边"), LiquidGlass("液态玻璃")
}

enum class TimetableGridStyle(val title: String, val alpha: Float) {
    STANDARD("标准", 0.72f), FAINT("极淡", 0.30f), HIDDEN("隐藏", 0f)
}

internal enum class WallpaperScope(val title: String) {
    TIMETABLE("仅课程表"), IMMERSIVE("全局沉浸")
}

internal val WALLPAPER_FADE_RANGE = 0..85
internal val LIGHT_GLASS_OPACITY_RANGE = 18..55
internal const val DEFAULT_LIGHT_GLASS_OPACITY = 32

internal enum class TimetableRecipe(
    val title: String, val style: CourseCardStyle, val opacity: Int,
    val grid: TimetableGridStyle, val fade: Int, val blur: Float,
) {
    LIQUID("液态琉璃", CourseCardStyle.LiquidGlass, 58, TimetableGridStyle.HIDDEN, 22, 0f),
    CLEAR("清透痛表", CourseCardStyle.GlassOutline, 65, TimetableGridStyle.HIDDEN, 20, 0f),
    SOFT("柔彩自习", CourseCardStyle.MorandiFill, 88, TimetableGridStyle.FAINT, 45, 4f),
    CLASSIC("经典清爽", CourseCardStyle.ClassicBar, 92, TimetableGridStyle.STANDARD, 45, 0f)
}

/** UI preferences survive account changes; school data remains in its own store. */
class AppPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
    var studentTrack: StudentTrack
        get() = StudentTrack.read(prefs.getString("student_track", null))
        set(value) { schoolSnapshots.mutate {
            prefs.edit { putString("student_track", value.name); putLong("class_schedule_generation", classScheduleGeneration + 1) }
        } }
    internal val classScheduleGeneration: Long get() = prefs.getLong("class_schedule_generation", 0L)
    internal fun newClassScheduleSession() {
        schoolSnapshots.mutate {
            val track = studentTrack
            val prefix = "suspended_${track.name}_"
            CourseOverrideStore(context, track).clear()
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
    var themeMode: ThemeMode
        get() = ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme_mode", null) } ?: ThemeMode.SYSTEM
        set(value) { prefs.edit { putString("theme_mode", value.name) } }
    var wallpaperFade: Int
        get() = prefs.getInt("wallpaper_fade", 45).coerceIn(WALLPAPER_FADE_RANGE)
        set(value) { prefs.edit().putInt("wallpaper_fade", value.coerceIn(WALLPAPER_FADE_RANGE)).apply() }
    internal var wallpaperScope: WallpaperScope
        get() = WallpaperScope.entries.firstOrNull { it.name == prefs.getString("wallpaper_scope", null) }
            ?: WallpaperScope.TIMETABLE
        set(value) { prefs.edit { putString("wallpaper_scope", value.name) } }
    var scheduleMulticolor: Boolean
        get() = prefs.getBoolean("schedule_multicolor", true)
        set(value) { prefs.edit { putBoolean("schedule_multicolor", value) } }
    var timetableShowInactive: Boolean
        get() = prefs.getBoolean("timetable_show_inactive", true)
        set(value) { prefs.edit { putBoolean("timetable_show_inactive", value) } }
    var sharedTimetableShowInactive: Boolean
        get() = prefs.getBoolean("shared_timetable_show_inactive", true)
        set(value) { prefs.edit { putBoolean("shared_timetable_show_inactive", value) } }
    var timetableCardOpacity: Int
        get() = prefs.getInt("timetable_card_opacity", 90).coerceIn(50, 98)
        set(value) { prefs.edit { putInt("timetable_card_opacity", value.coerceIn(50, 98)) } }
    internal var lightGlassOpacity: Int
        get() = prefs.getInt("light_glass_opacity", DEFAULT_LIGHT_GLASS_OPACITY).coerceIn(LIGHT_GLASS_OPACITY_RANGE)
        set(value) { prefs.edit { putInt("light_glass_opacity", value.coerceIn(LIGHT_GLASS_OPACITY_RANGE)) } }
    var courseCardStyle: CourseCardStyle
        get() = CourseCardStyle.entries.firstOrNull { it.name == prefs.getString("course_card_style", null) } ?: CourseCardStyle.ClassicBar
        set(value) { prefs.edit { putString("course_card_style", value.name) } }
    var timetableGridStyle: TimetableGridStyle
        get() = TimetableGridStyle.entries.firstOrNull { it.name == prefs.getString("timetable_grid_style", null) } ?: TimetableGridStyle.STANDARD
        set(value) { prefs.edit { putString("timetable_grid_style", value.name) } }
    var timetableWallpaperBlur: Float
        get() = prefs.getFloat("timetable_wallpaper_blur", 0f)
        set(value) { prefs.edit { putFloat("timetable_wallpaper_blur", value) } }
    internal fun applyTimetableRecipe(recipe: TimetableRecipe) {
        prefs.edit {
            putString("course_card_style", recipe.style.name)
            putInt("timetable_card_opacity", recipe.opacity)
            if (recipe.style == CourseCardStyle.LiquidGlass) putInt("light_glass_opacity", DEFAULT_LIGHT_GLASS_OPACITY)
            putString("timetable_grid_style", recipe.grid.name)
            putInt("wallpaper_fade", recipe.fade)
            putFloat("timetable_wallpaper_blur", recipe.blur)
        }
    }
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
    val store = PocketStore(context, track)
    val preferences = AppPreferences(context)
    val overrides = CourseOverrideStore(context, track)
    cache.load()?.let {
        val semester = it.semester.code
        val courses = applyCourseOverrides(it.courses, semester, overrides.load(semester))
        return WidgetSchedule(it.semester, courses, it.periods, "学校缓存", it.fetchedAt, cache.loginRequired,
            preferences.suspendedDates(track, semester))
    }
    val imported = store.savedCourses()
    val semester = imported?.firstOrNull()?.semester ?: DEFAULT_LOCAL_SEMESTER
    val rules = overrides.load(semester)
    if (imported == null && rules.customCourses.isEmpty()) return null
    return WidgetSchedule(Semester(semester, store.firstMonday()),
        applyCourseOverrides(imported.orEmpty(), semester, rules), store.periods,
        if (imported == null) "手动课程" else "本机导入",
        suspendedDates = preferences.suspendedDates(track, semester))
}
