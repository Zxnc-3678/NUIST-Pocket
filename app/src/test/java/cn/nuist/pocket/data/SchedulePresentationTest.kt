package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SchedulePresentationTest {
    private val semester = Semester("2026-2027-1", LocalDate.of(2026, 8, 31))
    private fun course(weeks: Set<Int> = setOf(1, 3, 5)) = CourseSlot("id", "code", "课程", "老师", "教室",
        4, 3, 4, weeks, null, null, semester.code)

    @Test fun dailyCollapseStartsAtTheExactEndTime() {
        val date = LocalDate.of(2026, 10, 22)
        val row = course().copy(start = LocalTime.of(10, 10), end = LocalTime.of(11, 50))
        assertFalse(courseHasEndedToday(row, emptyList(), date, date.atTime(11, 49, 59)))
        assertTrue(courseHasEndedToday(row, emptyList(), date, date.atTime(11, 50)))
        assertTrue(courseHasEndedToday(row, emptyList(), date, date.atTime(23, 59)))
    }
    @Test fun browsingPastAndFutureDatesKeepsCoursesVisible() {
        val now = LocalDateTime.of(2026, 10, 23, 20, 0)
        val row = course().copy(end = LocalTime.of(11, 50))
        assertFalse(courseHasEndedToday(row, emptyList(), now.toLocalDate().minusDays(1), now))
        assertFalse(courseHasEndedToday(row, emptyList(), now.toLocalDate().plusDays(1), now))
    }
    @Test fun dailyCollapseUsesExplicitEndBeforePeriodFallbackAndKeepsUnknownTimes() {
        val now = LocalDateTime.of(2026, 10, 22, 12, 0)
        val periods = listOf(Period(4, LocalTime.of(11, 5), LocalTime.of(11, 50), true))
        assertTrue(courseHasEndedToday(course(), periods, now.toLocalDate(), now))
        assertFalse(courseHasEndedToday(course().copy(end = LocalTime.of(13, 0)), periods, now.toLocalDate(), now))
        assertFalse(courseHasEndedToday(course(), emptyList(), now.toLocalDate(), now))
    }
    @Test fun overnightCourseIsNotHiddenBeforeItsNextDayEnd() {
        val now = LocalDateTime.of(2026, 10, 22, 23, 30)
        val row = course().copy(start = LocalTime.of(23, 0), end = LocalTime.of(0, 30))
        assertFalse(courseHasEndedToday(row, emptyList(), now.toLocalDate(), now))
    }

    @Test fun exactWeeksSurviveOddEvenLabels() {
        assertEquals("单周 · 第1、3、7周", formatCourseWeeks(setOf(7, 1, 3)))
        assertEquals("双周 · 第2、4、8周", formatCourseWeeks(setOf(8, 4, 2)))
        assertEquals("单周 · 第1–15周", formatCourseWeeks((1..15 step 2).toSet()))
        assertEquals("双周 · 第2–16周", formatCourseWeeks((2..16 step 2).toSet()))
        assertEquals("第2–3、6–7周", formatCourseWeeks(setOf(2, 3, 6, 7)))
        assertEquals("第14周", formatCourseWeeks(setOf(14)))
        assertEquals("周次待定", formatCourseWeeks(emptySet()))
    }
    @Test fun mergeOnlyIdenticalPlacements() {
        val a = course(setOf(1, 3)); val b = a.copy(id = "other", weeks = setOf(5))
        val result = overviewCourses(listOf(a, b, a.copy(room = "别的教室"), a.copy(teacher = "另一位老师")))
        assertEquals(3, result.size)
        assertEquals(setOf(1, 3, 5), result.single { it.room == a.room && it.teacher == a.teacher }.weeks)
    }
    @Test fun inactiveCoursesRemainAvailableToOverview() {
        val courses = overviewCourses(listOf(course()))
        assertEquals(1, courses.size)
        assertFalse(courses.single().occurs(8, 4))
        assertTrue(courses.single().occurs(3, 4))
    }
    @Test fun overviewCombinesLabAndTheoryRoomsButKeepsExactPlacements() {
        val theory = course(setOf(1, 3))
        val lab = course(setOf(2, 4)).copy(room = "实验室", teacher = "实验教师")
        val group = overviewGroups(listOf(theory, lab)).single()
        assertEquals(2, group.placements.size)
        assertEquals(setOf(1, 2, 3, 4), group.weeks)
        assertEquals(theory.room, overviewLocation(group, 3))
        assertEquals(lab.room, overviewLocation(group, 4))
        assertEquals(listOf(lab), group.activePlacements(4))
        assertEquals("地点随周次变化 · 查看详情", overviewLocation(group, 8))
    }
    @Test fun differentDaysAndPeriodsStaySeparateInOverview() {
        val base = course()
        assertEquals(3, overviewGroups(listOf(base, base.copy(weekday = 3), base.copy(firstPeriod = 5, lastPeriod = 6))).size)
    }
    @Test fun concurrentDifferentRoomsAreNotSilentlySelected() {
        val group = overviewGroups(listOf(course(), course().copy(room = "另一教室"))).single()
        assertEquals("本周2处安排 · 查看详情", overviewLocation(group, 3))
        assertEquals(2, group.activePlacements(3).size)
    }
    @Test fun compactSummaryNeverImpliesContinuousWeeksForSparseSchedules() {
        val weeks = (1..31 step 2).filter { it != 11 }.toSet()
        assertEquals("单周 · 第1–31周 · 非连续", courseWeeksSummary(weeks))
        assertFalse(formatCourseWeeks(weeks).contains("11"))
    }
    @Test fun todayWidgetExcludesInactiveOtherDaysOtherTermsAndUnarranged() {
        val active = course(setOf(8))
        val rows = listOf(active, course(), active.copy(weekday = 3), active.copy(semester = "other"), active.copy(firstPeriod = null))
        assertEquals(listOf(active), coursesOnDate(rows, semester, LocalDate.of(2026, 10, 22)))
        assertTrue(coursesOnDate(rows, semester, LocalDate.of(2026, 10, 23)).isEmpty())
    }
    @Test fun widgetUsesExplicitTimesBeforePeriodFallback() {
        val periods = listOf(Period(3, LocalTime.of(10, 10), LocalTime.of(10, 55), true),
            Period(4, LocalTime.of(11, 5), LocalTime.of(11, 50), true))
        assertEquals(LocalTime.of(10, 10) to LocalTime.of(11, 50), course().times(periods))
        assertEquals(LocalTime.of(10, 0), course().copy(start = LocalTime.of(10, 0)).times(periods).first)
    }
    @Test fun widgetBeforeSemesterDoesNotShowFirstWeekCourses() {
        assertTrue(coursesOnDate(listOf(course(setOf(1))), semester, semester.firstMonday.minusDays(4)).isEmpty())
    }
    @Test fun capacityRespectsSmallWidgetsAndLargeText() {
        assertEquals(0, widgetCourseCapacity(150, 1f))
        assertEquals(3, widgetCourseCapacity(360, 1f))
        assertTrue(widgetCourseCapacity(360, 1.5f) < widgetCourseCapacity(360, 1f))
        assertEquals(6, widgetCourseCapacity(2000, 1f))
    }
}
