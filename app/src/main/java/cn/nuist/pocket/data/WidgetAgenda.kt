package cn.nuist.pocket.data

import java.time.LocalDate
import java.time.LocalDateTime

enum class WidgetCourseState { WAITING, IMMINENT, ONGOING }

data class WidgetCourseEntry(
    val course: CourseSlot, val start: LocalDateTime?, val end: LocalDateTime?,
    val state: WidgetCourseState,
) {
    val countdownTarget: LocalDateTime? get() = when (state) {
        WidgetCourseState.IMMINENT -> start
        WidgetCourseState.ONGOING -> end
        WidgetCourseState.WAITING -> null
    }
}

data class WidgetAgenda(val date: LocalDate, val entries: List<WidgetCourseEntry>, val nextChange: LocalDateTime)

/** A course with an unknown end remains visible; never pretend it has already finished. */
fun widgetAgenda(schedule: WidgetSchedule, now: LocalDateTime): WidgetAgenda {
    fun entries(date: LocalDate): List<WidgetCourseEntry> = schedule.coursesOn(date)
        .mapNotNull { course ->
            val (startTime, endTime) = course.times(schedule.periods)
            val start = startTime?.let(date::atTime)
            val end = endTime?.let(date::atTime)
            if (end != null && !now.isBefore(end)) return@mapNotNull null
            val state = when {
                start != null && !now.isBefore(start) -> WidgetCourseState.ONGOING
                start != null && !now.isBefore(start.minusMinutes(15)) -> WidgetCourseState.IMMINENT
                else -> WidgetCourseState.WAITING
            }
            WidgetCourseEntry(course, start, end, state)
        }.sortedWith(compareBy<WidgetCourseEntry> { it.start ?: LocalDateTime.MAX }
            .thenBy { it.course.firstPeriod }.thenBy { it.course.name })

    val today = now.toLocalDate()
    val remainingToday = entries(today)
    val displayDate = if (remainingToday.isEmpty()) today.plusDays(1) else today
    val displayed = if (displayDate == today) remainingToday else entries(displayDate)
    val boundaries = displayed.flatMap { listOfNotNull(it.start?.minusMinutes(15), it.start, it.end) }
    val nextChange = (boundaries + today.plusDays(1).atStartOfDay()).filter { it.isAfter(now) }.minOrNull()!!
    return WidgetAgenda(displayDate, displayed, nextChange)
}

data class OverviewDay(val active: List<OverviewCourseGroup>, val inactive: List<OverviewCourseGroup>)

fun overviewDay(groups: List<OverviewCourseGroup>, week: Int, day: Int): OverviewDay {
    val slots = groups.filter { it.course.weekday == day }.mapNotNull { group ->
        val placements = group.placements.filter { it.visibleInTimetableWeek(week) }
        if (placements.isEmpty()) null else OverviewCourseGroup(placements)
    }
    return OverviewDay(slots.filter { week in it.weeks }, slots.filter { week !in it.weeks })
}
