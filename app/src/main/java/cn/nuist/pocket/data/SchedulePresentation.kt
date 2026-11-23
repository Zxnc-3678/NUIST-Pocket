package cn.nuist.pocket.data

import java.time.LocalDate
import java.time.LocalDateTime

/** Only today's known, finished times collapse; browsing other dates keeps the full agenda. */
fun courseHasEndedToday(course: CourseSlot, periods: List<Period>, date: LocalDate, now: LocalDateTime): Boolean {
    if (date != now.toLocalDate()) return false
    val (start, end) = course.times(periods)
    if (end == null) return false
    val endDate = if (start != null && end < start) date.plusDays(1) else date
    return !now.isBefore(endDate.atTime(end))
}

/** Keep exact teaching weeks, including gaps caused by holidays and rescheduling. */
fun formatCourseWeeks(weeks: Set<Int>): String {
    val sorted = weeks.filter { it > 0 }.sorted()
    if (sorted.isEmpty()) return "周次待定"
    val ranges = mutableListOf<String>()
    var first = sorted.first()
    var last = first
    for (week in sorted.drop(1)) {
        if (week == last + 1) last = week else {
            ranges += if (first == last) "$first" else "$first–$last"
            first = week; last = week
        }
    }
    ranges += if (first == last) "$first" else "$first–$last"
    val parity = when {
        sorted.size < 2 -> ""
        sorted.all { it % 2 == 1 } -> "单周 · "
        sorted.all { it % 2 == 0 } -> "双周 · "
        else -> ""
    }
    if (parity.isNotEmpty() && sorted.zipWithNext().all { (a, b) -> b == a + 2 }) {
        return "${parity}第${sorted.first()}–${sorted.last()}周"
    }
    return "${parity}第${ranges.joinToString("、")}周"
}

fun courseWeeksSummary(weeks: Set<Int>): String {
    val exact = formatCourseWeeks(weeks)
    val parity = when {
        exact.startsWith("单周") -> "单周 · "
        exact.startsWith("双周") -> "双周 · "
        else -> ""
    }
    return if (exact.length <= 20) exact else "${parity}第${weeks.minOrNull()}–${weeks.maxOrNull()}周 · 非连续"
}

/** Fragmented rows for the same placement become one card; different locations stay separate. */
fun overviewCourses(courses: List<CourseSlot>): List<CourseSlot> = courses.filter { it.arranged }
    .groupBy { listOf(it.code, it.name, it.teacher, it.room, it.weekday, it.firstPeriod,
        it.lastPeriod, it.start, it.end, it.semester, it.rescheduledFrom, it.id.takeIf { _ -> it.isCustom }) }
    .values.map { rows -> rows.first().copy(weeks = rows.flatMap { it.weeks }.toSet(),
        locallyRemovedWeeks = rows.flatMap { it.locallyRemovedWeeks }.toSet()) }
    .sortedWith(compareBy<CourseSlot> { it.weekday }.thenBy { it.firstPeriod }.thenBy { it.lastPeriod }.thenBy { it.name })

fun coursesOnDate(courses: List<CourseSlot>, semester: Semester, date: LocalDate): List<CourseSlot> =
    overviewCourses(courses.filter { it.semester == semester.code && it.occurs(semester.weekOn(date), date.dayOfWeek.value) })

/** One overview card per course/time, with week-dependent rooms and teachers inside it. */
data class OverviewCourseGroup(val placements: List<CourseSlot>) {
    val course: CourseSlot get() = placements.first()
    val weeks: Set<Int> = placements.flatMap { it.weeks }.toSet()
    fun activePlacements(week: Int): List<CourseSlot> = placements.filter { week in it.weeks }
}

fun overviewGroups(courses: List<CourseSlot>): List<OverviewCourseGroup> = overviewCourses(courses)
    .groupBy { listOf(it.semester, it.code, it.name, it.weekday, it.firstPeriod, it.lastPeriod,
        it.rescheduledFrom, it.id.takeIf { _ -> it.isCustom }) }
    .values.map { OverviewCourseGroup(it) }

fun overviewLocation(group: OverviewCourseGroup, week: Int): String {
    val active = group.activePlacements(week)
    val rooms = (active.ifEmpty { group.placements }).map { it.room.ifBlank { "地点待定" } }.distinct()
    return when {
        active.isNotEmpty() && rooms.size > 1 -> "本周${rooms.size}处安排 · 查看详情"
        active.isNotEmpty() -> rooms.single()
        rooms.size > 1 -> "地点随周次变化 · 查看详情"
        else -> rooms.single()
    }
}

data class WidgetSchedule(
    val semester: Semester, val courses: List<CourseSlot>, val periods: List<Period>,
    val source: String, val updatedAt: String? = null, val loginRequired: Boolean = false,
    val suspendedDates: Set<LocalDate> = emptySet(),
)

fun WidgetSchedule.coursesOn(date: LocalDate): List<CourseSlot> =
    if (date in suspendedDates) emptyList() else coursesOnDate(courses, semester, date)

data class WidgetLayoutPolicy(val compact: Boolean, val nameLines: Int, val detailLines: Int, val capacity: Int)

internal const val WIDGET_HEADER_DP = 82
internal fun widgetCourseRowDp(compact: Boolean): Int = if (compact) 92 else 72

/** Actual launcher dimensions, not icon-column counts, determine the layout. */
fun widgetLayoutPolicy(widthDp: Int, heightDp: Int, fontScale: Float): WidgetLayoutPolicy {
    val scale = fontScale.coerceAtLeast(1f)
    val short = heightDp < 250
    val compact = widthDp < 260 || short
    val header = WIDGET_HEADER_DP
    val row = widgetCourseRowDp(compact)
    return WidgetLayoutPolicy(compact, 1, 1,
        ((heightDp - header * scale) / (row * scale)).toInt().coerceIn(0, 6))
}

fun widgetCourseCapacity(heightDp: Int, fontScale: Float): Int = widgetLayoutPolicy(280, heightDp, fontScale).capacity
