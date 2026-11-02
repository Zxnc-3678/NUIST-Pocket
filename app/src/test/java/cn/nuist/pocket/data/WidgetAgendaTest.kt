package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class WidgetAgendaTest {
    private val date = LocalDate.of(2026, 10, 22)
    private val semester = Semester("2026-2027-1", LocalDate.of(2026, 8, 31))
    private fun course(day: Int = 4, name: String = "课程", start: String? = "10:10", end: String? = "11:50") =
        CourseSlot(name, name, name, "教师", "教室", day, 3, 4, setOf(8), start?.let(LocalTime::parse), end?.let(LocalTime::parse), semester.code)
    private fun snapshot(vararg courses: CourseSlot) = WidgetSchedule(semester, courses.toList(), emptyList(), "测试")
    private fun at(time: String) = date.atTime(LocalTime.parse(time))

    @Test fun suspendedDayIsOmittedFromTheWidgetAndRestoringItBringsCoursesBack() {
        val normal = snapshot(course(), course(day = 5, name = "明天"))
        val stopped = normal.copy(suspendedDates = setOf(date))
        assertEquals("明天", widgetAgenda(stopped, at("08:00")).entries.single().course.name)
        assertEquals("课程", widgetAgenda(normal, at("08:00")).entries.single().course.name)
    }

    @Test fun imminentStartsExactlyFifteenMinutesBeforeClass() {
        val data = snapshot(course())
        assertEquals(WidgetCourseState.WAITING, widgetAgenda(data, at("09:54:59")).entries.single().state)
        val result = widgetAgenda(data, at("09:55"))
        assertEquals(WidgetCourseState.IMMINENT, result.entries.single().state)
        assertEquals(at("10:10"), result.entries.single().countdownTarget)
        assertEquals(at("10:10"), result.nextChange)
    }
    @Test fun ongoingCountdownTargetsTheActualEndOfTheCourseBlock() {
        val result = widgetAgenda(snapshot(course()), at("10:10"))
        assertEquals(WidgetCourseState.ONGOING, result.entries.single().state)
        assertEquals(at("11:50"), result.entries.single().countdownTarget)
        assertEquals(at("11:50"), result.nextChange)
        assertEquals(WidgetCourseState.ONGOING, widgetAgenda(snapshot(course()), at("11:49:59")).entries.single().state)
    }
    @Test fun finishedCourseIsRemovedAtItsEndAndAfternoonRemains() {
        val result = widgetAgenda(snapshot(course(), course(name = "下午", start = "13:45", end = "15:25")), at("11:50"))
        assertEquals(date, result.date)
        assertEquals(listOf("下午"), result.entries.map { it.course.name })
        assertEquals(at("13:30"), result.nextChange)
    }
    @Test fun finalEndImmediatelyShowsTomorrowAndTomorrowWeekNumberUsesItsDate() {
        val result = widgetAgenda(snapshot(course(), course(day = 5, name = "明天")), at("11:50"))
        assertEquals(date.plusDays(1), result.date)
        assertEquals("明天", result.entries.single().course.name)
        assertEquals(WidgetCourseState.WAITING, result.entries.single().state)
        assertEquals(date.plusDays(1).atStartOfDay(), result.nextChange)
    }
    @Test fun noCoursesTodayAlsoPreviewsTomorrowAndNoCoursesTomorrowIsAnEmptyPreview() {
        val withTomorrow = widgetAgenda(snapshot(course(day = 5)), at("08:00"))
        assertEquals(date.plusDays(1), withTomorrow.date)
        assertEquals(1, withTomorrow.entries.size)
        val empty = widgetAgenda(snapshot(course()), at("18:00"))
        assertEquals(date.plusDays(1), empty.date)
        assertTrue(empty.entries.isEmpty())
    }
    @Test fun unknownEndIsRetainedInsteadOfIncorrectlyShowingTomorrow() {
        val result = widgetAgenda(snapshot(course(end = null)), at("18:00"))
        assertEquals(date, result.date)
        assertEquals(WidgetCourseState.ONGOING, result.entries.single().state)
        assertNull(result.entries.single().countdownTarget)
    }
    @Test fun timingFallsBackToPeriodDictionary() {
        val data = snapshot(course(start = null, end = null)).copy(periods = listOf(
            Period(3, LocalTime.of(10, 10), LocalTime.of(10, 55), true),
            Period(4, LocalTime.of(11, 5), LocalTime.of(11, 50), true)))
        assertEquals(at("11:50"), widgetAgenda(data, at("10:30")).entries.single().countdownTarget)
    }
    @Test fun beforeAndAfterMidnightSelectCorrectActualDay() {
        val data = snapshot(course(), course(day = 5, name = "周五"))
        val before = widgetAgenda(data, at("23:59:59"))
        val after = widgetAgenda(data, LocalDateTime.of(2026, 10, 23, 0, 0))
        assertEquals(before.date, after.date)
        assertEquals("周五", after.entries.single().course.name)
        assertEquals(LocalDateTime.of(2026, 10, 23, 9, 55), after.nextChange)
    }
    @Test fun nextWeekPreviewDoesNotUsePreviousWeeksMask() {
        val sunday = LocalDateTime.of(2026, 10, 25, 18, 0)
        val mondayCourse = course(day = 1).copy(weeks = setOf(9))
        val result = widgetAgenda(snapshot(mondayCourse), sunday)
        assertEquals(sunday.toLocalDate().plusDays(1), result.date)
        assertEquals(1, result.entries.size)
        assertEquals(9, semester.weekOn(result.date))
    }
    @Test fun onlyNextBoundaryIsChosenEvenWithOverlappingClasses() {
        val data = snapshot(course(), course(name = "另一课程", start = "10:15", end = "12:00"))
        val result = widgetAgenda(data, at("10:10"))
        assertEquals(2, result.entries.size)
        assertEquals(at("10:15"), result.nextChange)
    }
    @Test fun manyOneOffInactiveCoursesOnlyContributeToTheCollapsedCount() {
        val active = course()
        val inactive = (1..20).map { course(name = "一次性课程$it").copy(weeks = setOf(9)) }
        val day = overviewDay(overviewGroups(inactive + active), 8, 4)
        assertEquals(1, day.active.size)
        assertEquals(20, day.inactive.size)
        assertEquals("课程", day.active.single().course.name)
    }
    @Test fun expiredLoginDoesNotPreventOfflineTimetableCalculation() {
        val data = snapshot(course()).copy(loginRequired = true, updatedAt = "2026-10-01T00:00:00Z")
        assertEquals(WidgetCourseState.ONGOING, widgetAgenda(data, at("10:30")).entries.single().state)
    }
}
