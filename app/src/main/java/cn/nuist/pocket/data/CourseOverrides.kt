package cn.nuist.pocket.data

import java.text.Normalizer
import java.time.LocalTime

internal data class CourseAppearanceOverride(
    val shortName: String? = null,
    val customColorHex: String? = null,
)

data class SessionOrigin(
    val courseKey: String, val week: Int, val weekday: Int,
    val firstPeriod: Int, val lastPeriod: Int,
)

internal enum class SessionAction { SUSPEND, RESCHEDULE }

internal data class SessionException(
    val origin: SessionOrigin,
    val action: SessionAction,
    val targetWeek: Int = origin.week,
    val targetWeekday: Int = origin.weekday,
    val targetFirstPeriod: Int = origin.firstPeriod,
    val targetLastPeriod: Int = origin.lastPeriod,
    val targetStart: LocalTime? = null,
    val targetEnd: LocalTime? = null,
    val targetRoom: String? = null,
)

internal data class CustomCourse(
    val id: String, val name: String, val room: String, val teacher: String,
    val weeks: Set<Int>, val weekday: Int, val firstPeriod: Int, val lastPeriod: Int,
    val start: LocalTime? = null, val end: LocalTime? = null, val customColorHex: String? = null,
)

internal data class CourseOverrides(
    val appearances: Map<String, CourseAppearanceOverride> = emptyMap(),
    val sessions: Map<SessionOrigin, SessionException> = emptyMap(),
    val customCourses: List<CustomCourse> = emptyList(),
)

internal const val DEFAULT_LOCAL_SEMESTER = "2026-2027-1"

internal fun teachingWeekLimit(courses: List<CourseSlot>): Int =
    maxOf(20, courses.maxOfOrNull { it.weeks.maxOrNull() ?: 0 } ?: 0)

internal val CourseSlot.courseKey: String
    get() = when {
        isCustom -> "custom:$id"
        code.isNotBlank() -> "code:${code.trim()}"
        else -> "name:" + Normalizer.normalize(name.trim(), Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ").lowercase()
    }

internal fun CourseSlot.originFor(week: Int): SessionOrigin? {
    val day = weekday ?: return null
    if (isCustom || !occurs(week, day)) return null
    return rescheduledFrom ?: SessionOrigin(courseKey, week, day, firstPeriod!!, lastPeriod!!)
}

internal fun CourseSlot.visibleInTimetableWeek(week: Int): Boolean =
    week !in locallyRemovedWeeks && (!isRescheduled || week in weeks)

internal fun applyCourseOverrides(
    raw: List<CourseSlot>, semester: String, overrides: CourseOverrides,
): List<CourseSlot> = buildList {
    val sessionsByCourse = overrides.sessions.values.groupBy { it.origin.courseKey }
    raw.filter { it.semester == semester }.forEach { original ->
        val appearance = overrides.appearances[original.courseKey]
        val course = original.copy(shortName = appearance?.shortName, customColorHex = appearance?.customColorHex)
        val changes = sessionsByCourse[course.courseKey].orEmpty().filter { rule ->
            val origin = rule.origin
            course.occurs(origin.week, origin.weekday) &&
                course.firstPeriod == origin.firstPeriod && course.lastPeriod == origin.lastPeriod
        }
        val removedWeeks = changes.map { it.origin.week }.toSet()
        val remainingWeeks = course.weeks - removedWeeks
        if (!course.arranged || remainingWeeks.isNotEmpty()) {
            add(course.copy(weeks = remainingWeeks, locallyRemovedWeeks = removedWeeks))
        }
        changes.filter { it.action == SessionAction.RESCHEDULE }.forEach { rule ->
            val origin = rule.origin
            add(course.copy(
                id = "${course.id}/rescheduled/${origin.week}/${origin.weekday}/${origin.firstPeriod}/${origin.lastPeriod}",
                weeks = setOf(rule.targetWeek), weekday = rule.targetWeekday,
                firstPeriod = rule.targetFirstPeriod, lastPeriod = rule.targetLastPeriod,
                start = rule.targetStart, end = rule.targetEnd, room = rule.targetRoom ?: course.room,
                rescheduledFrom = origin,
            ))
        }
    }
    overrides.customCourses.forEach { custom ->
        add(CourseSlot(custom.id, "", custom.name, custom.teacher, custom.room, custom.weekday,
            custom.firstPeriod, custom.lastPeriod, custom.weeks, custom.start, custom.end, semester,
            customColorHex = custom.customColorHex, isCustom = true))
    }
}

/** Exact times occupy intersecting teaching periods. Times wholly in a gap use the nearest period. */
internal fun periodsForTimes(start: LocalTime, end: LocalTime, periods: List<Period>): IntRange {
    val overlap = periods.filter { start < it.end && end > it.start }
    if (overlap.isNotEmpty()) return overlap.first().number..overlap.last().number
    val closest = periods.minBy { period ->
        minOf(kotlin.math.abs(start.toSecondOfDay() - period.end.toSecondOfDay()),
            kotlin.math.abs(end.toSecondOfDay() - period.start.toSecondOfDay()))
    }.number
    return closest..closest
}
