package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ClassReminderPlanTest {
    private val date = LocalDate.of(2026, 10, 28)
    private val semester = Semester("2026-2027-1", LocalDate.of(2026, 8, 31))
    private val track = StudentTrack.UNDERGRADUATE
    private fun at(hour: Int, minute: Int, second: Int = 0) = date.atTime(hour, minute, second).atZone(ClassZone).toInstant().toEpochMilli()
    private fun course(name: String = "虚构课程", room: String = "测试楼101", code: String = "TEST",
                       start: LocalTime? = LocalTime.of(8, 0), end: LocalTime? = LocalTime.of(9, 40)) =
        CourseSlot("test", code, name, "虚构教师", room, 3, 1, 2, setOf(9), start, end, semester.code)
    private fun schedule(vararg rows: CourseSlot, periods: List<Period> = emptyList()) = WidgetSchedule(semester, rows.toList(), periods, "虚构测试")
    private fun event() = classEvents(schedule(course()), track, date).single()

    @Test fun reminderIsTwentyMinutesBeforeTheActualClass() {
        val event = event()
        assertEquals(at(7, 40), event.remindAt)
        assertEquals(at(8, 0), event.startsAt); assertEquals(at(9, 40), event.endsAt)
    }
    @Test fun aSuspendedDayRemovesItsClassesAndExistingReservationsWithoutChangingOtherDays() {
        val original = schedule(course())
        val stopped = original.copy(suspendedDates = setOf(date))
        val saved = event().reservation(QuietMode.SILENT)
        assertEquals(1, original.coursesOn(date).size)
        assertTrue(stopped.coursesOn(date).isEmpty())
        assertTrue(classEvents(stopped, track, date).isEmpty())
        assertTrue(validReservations(listOf(saved), classEvents(stopped, track, date), at(7, 30)).isEmpty())
        assertEquals(1, classEvents(original.copy(suspendedDates = setOf(date.plusDays(1))), track, date).size)
        assertEquals(1, classEvents(stopped.copy(suspendedDates = emptySet()), track, date).size)
    }
    @Test fun completeNamesAndCurrentWeekRoomsArePreserved() {
        val full = "虚构的超长课程名称".repeat(12)
        val rows = schedule(course(full), course(full, "旧地点").copy(weeks = setOf(8)))
        val event = classEvents(rows, track, date).single()
        assertEquals(full, event.title); assertEquals("测试楼101", event.room)
    }
    @Test fun duplicatePlacementsProduceOneEventAndKeepAllCurrentRooms() {
        val events = classEvents(schedule(course(), course(), course(room = "测试楼202")), track, date)
        assertEquals(1, events.size); assertTrue(events.single().room.contains("202"))
    }
    @Test fun changingRoomsOrTeachersKeepsOccurrenceIdentity() {
        val original = event()
        val updated = classEvents(schedule(course(room = "新地点").copy(teacher = "另一位虚构教师")), track, date).single()
        assertEquals(original.key, updated.key)
        assertEquals(1, validReservations(listOf(original.reservation(QuietMode.SILENT)), listOf(updated), at(7, 40)).size)
    }
    @Test fun changedTimesInvalidatePreviouslyBookedOccurrence() {
        val updated = classEvents(schedule(course(start = LocalTime.of(8, 10))), track, date).single()
        assertNotEquals(event().key, updated.key)
        assertTrue(validReservations(listOf(event().reservation(QuietMode.SILENT)), listOf(updated), at(7, 40)).isEmpty())
    }
    @Test fun tracksAndDatesHaveIndependentKeys() {
        val graduate = classEvents(schedule(course()), StudentTrack.GRADUATE, date).single()
        assertNotEquals(event().key, graduate.key)
        assertTrue(validReservations(listOf(event().reservation(QuietMode.SILENT)), listOf(graduate), at(7, 40)).isEmpty())
        assertNotEquals(event().key, classEvents(schedule(course()), track, date.plusWeeks(1)).firstOrNull()?.key)
    }
    @Test fun switchingAwayAndBackOrStartingANewLoginInvalidatesOldAppointments() {
        val original = event()
        val afterSessionChange = classEvents(schedule(course()), track, date, accountGeneration = 1).single()
        assertNotEquals(original.key, afterSessionChange.key)
        assertTrue(validReservations(listOf(original.reservation(QuietMode.SILENT)), listOf(afterSessionChange), at(7, 40)).isEmpty())
    }
    @Test fun identitySeparatorsCannotCollapseDifferentCourses() {
        val events = classEvents(schedule(course("甲|乙", code = "丙"), course("乙", code = "甲|丙")), track, date)
        assertEquals(2, events.size); assertEquals(2, events.map { it.key }.distinct().size)
    }
    @Test fun unarrangedWrongWeekWrongDayAndWrongSemesterNeverNotify() {
        val source = schedule(course().copy(weekday = null), course().copy(weeks = setOf(8)),
            course().copy(weekday = 4), course().copy(semester = "2025-2026-1"))
        assertTrue(classEvents(source, track, date).isEmpty())
    }
    @Test fun missingOrInvertedTimesNeverCreateReservations() {
        assertTrue(classEvents(schedule(course(start = null), course(end = null),
            course(end = LocalTime.of(7, 0))), track, date).isEmpty())
    }
    @Test fun periodTimesAreUsedWhenTheCourseHasNoExplicitTimes() {
        val periods = listOf(Period(1, LocalTime.of(8, 0), LocalTime.of(8, 45), true),
            Period(2, LocalTime.of(8, 55), LocalTime.of(9, 40), true))
        assertEquals(event().startsAt, classEvents(schedule(course(start = null, end = null), periods = periods), track, date).single().startsAt)
    }
    @Test fun quietStartsOneMinuteEarlyAndEndsOneMinuteLateAtStrictBoundaries() {
        val saved = event().reservation(QuietMode.SILENT)
        assertEquals(at(7, 59), saved.activatesAt); assertEquals(at(9, 41), saved.restoresAt)
        assertNull(activeQuietWindow(listOf(saved), at(7, 58, 59)))
        assertNotNull(activeQuietWindow(listOf(saved), at(7, 59)))
        assertNotNull(activeQuietWindow(listOf(saved), at(9, 40, 59)))
        assertNull(activeQuietWindow(listOf(saved), at(9, 41)))
    }
    @Test fun bookingAtReminderTimeDoesNotStartQuietImmediately() {
        assertNull(activeQuietWindow(listOf(event().reservation(QuietMode.SILENT)), at(7, 40)))
        assertNull(bookingProblem(event(), emptyList(), QuietMode.SILENT, at(7, 40)))
    }
    @Test fun reservationRemainsValidDuringTheAfterClassMargin() {
        val list = listOf(event().reservation(QuietMode.SILENT))
        assertEquals(list, validReservations(list, listOf(event()), at(9, 40, 30)))
        assertTrue(validReservations(list, listOf(event()), at(9, 41)).isEmpty())
    }
    @Test fun overlappingBookingsStayQuietUntilTheLastPaddedEnd() {
        val first = event().reservation(QuietMode.SILENT)
        val second = first.copy(key = "b".repeat(64), title = "虚构第二课", startsAt = at(9, 0), endsAt = at(10, 30))
        val window = activeQuietWindow(listOf(first, second), at(9, 41))!!
        assertEquals(at(10, 31), window.endsAt); assertEquals(2, window.keys.size)
    }
    @Test fun TwoMinuteGapHasTouchingMarginsAndNoAudibleGap() {
        val first = event().reservation(QuietMode.SILENT)
        val second = first.copy(key = "b".repeat(64), startsAt = at(9, 42), endsAt = at(10, 30))
        assertEquals(at(10, 31), activeQuietWindow(listOf(second, first), at(9, 41))!!.endsAt)
    }
    @Test fun longerGapsRestoreBetweenCourses() {
        val first = event().reservation(QuietMode.SILENT)
        val second = first.copy(key = "b".repeat(64), startsAt = at(9, 44), endsAt = at(10, 30))
        assertNull(activeQuietWindow(listOf(first, second), at(9, 42)))
    }
    @Test fun differentModesAreRejectedWhenTheirMarginsOverlap() {
        val first = event().reservation(QuietMode.SILENT)
        val second = event().copy(key = "b".repeat(64), startsAt = at(9, 41), endsAt = at(10, 30))
        assertNotNull(bookingProblem(second, listOf(first), QuietMode.DND, at(7, 40)))
        assertNull(bookingProblem(second, listOf(first), QuietMode.SILENT, at(7, 40)))
    }
    @Test fun pastBookingsAndCapacityAreRejectedButAnExistingBookingCanBeReplaced() {
        assertNotNull(bookingProblem(event(), emptyList(), QuietMode.SILENT, at(8, 0)))
        val full = (0 until MAX_QUIET_RESERVATIONS).map { event().reservation(QuietMode.SILENT).copy(key = it.toString()) }
        assertNotNull(bookingProblem(event(), full, QuietMode.SILENT, at(7, 40)))
        assertNull(bookingProblem(event().copy(key = "0"), full, QuietMode.SILENT, at(7, 40)))
    }
    @Test fun removedCoursesCancelReservations() {
        assertTrue(validReservations(listOf(event().reservation(QuietMode.DND)), emptyList(), at(7, 40)).isEmpty())
    }
    @Test fun nextAlarmUsesTheQuietMarginAndNeverSchedulesInThePast() {
        val event = event(); val booked = event.reservation(QuietMode.SILENT)
        assertEquals(at(7, 40), nextClassWake(listOf(event), emptyList(), emptyList(), at(7, 30)))
        assertEquals(at(7, 59), nextClassWake(listOf(event), listOf(booked), listOf(ClassDelivery(event.key, event.endsAt)), at(7, 40)))
        assertEquals(at(9, 41), nextClassWake(listOf(event), listOf(booked), listOf(ClassDelivery(event.key, event.endsAt)), at(8, 0)))
        assertTrue(nextClassWake(emptyList(), emptyList(), emptyList(), at(23, 59)) > at(23, 59))
    }
    @Test fun snapshotRoundTripsReservationsDeliveryAndRecoveryOwnership() {
        val state = ClassReminderState(listOf(event().reservation(QuietMode.SILENT)),
            listOf(ClassDelivery(event().key, event().endsAt, true)), QuietLease(QuietMode.SILENT, at(9, 41), RINGER_VIBRATE), at(9, 41), "测试状态")
        assertEquals(state, decodeClassReminderState(encodeClassReminderState(state)))
        val dnd = state.copy(lease = QuietLease(QuietMode.DND, at(9, 41), ruleId = "owned-rule"))
        assertEquals(dnd, decodeClassReminderState(encodeClassReminderState(dnd)))
    }
    @Test fun afternoonReminderIsAlreadyArmedWithoutRunningTheMorningAlarm() {
        val morning = event()
        val afternoon = morning.copy(key = "a".repeat(64), startsAt = at(15, 55), endsAt = at(17, 35))
        val night = morning.copy(key = "b".repeat(64), startsAt = at(18, 45), endsAt = at(20, 25))
        val plan = classWakeTimes(listOf(morning, afternoon, night), emptyList(), emptyList(), at(7, 0))
        assertTrue(plan.contains(at(7, 40)))
        assertTrue(plan.contains(at(15, 35)))
        assertTrue(plan.contains(at(18, 25)))
        assertEquals(plan.distinct().sorted(), plan)
    }
    @Test fun deliveredRemindersAreNotRearmedButCleanupAndBookedBoundariesRemain() {
        val course = event()
        val plan = classWakeTimes(listOf(course, course), listOf(course.reservation(QuietMode.DND)),
            listOf(ClassDelivery(course.key, course.endsAt)), at(7, 30))
        assertFalse(plan.contains(at(7, 40)))
        assertTrue(plan.contains(at(7, 59)))
        assertTrue(plan.contains(at(8, 0)))
        assertTrue(plan.contains(at(9, 41)))
        assertEquals(plan.size, plan.distinct().size)
    }
    @Test fun futureWakePlanIsBoundedAndAlwaysKeepsMidnightRollover() {
        val now = at(0, 0)
        val crowded = (1..500).map { event().copy(key = it.toString(), startsAt = now + it * 60_000L,
            endsAt = now + (it + 1) * 60_000L) }
        val plan = classWakeTimes(crowded, emptyList(), emptyList(), now)
        assertEquals(MAX_CLASS_WAKE_TIMES, plan.size)
        assertTrue(plan.all { it > now })
        assertTrue(plan.contains(date.plusDays(1).atStartOfDay(ClassZone).toInstant().toEpochMilli() + 1000))
    }
    @Test fun holidaysStillHaveADailyWakeAndPastTasksAreNeverRearmed() {
        val now = at(23, 59)
        val plan = classWakeTimes(listOf(event()), listOf(event().reservation(QuietMode.SILENT)), emptyList(), now)
        assertEquals(listOf(date.plusDays(1).atStartOfDay(ClassZone).toInstant().toEpochMilli() + 1000), plan)
    }
    @Test fun corruptedOversizedAndUnknownSnapshotsAreRejected() {
        val text = encodeClassReminderState(ClassReminderState())
        assertThrows(Exception::class.java) { decodeClassReminderState("{") }
        assertThrows(Exception::class.java) { decodeClassReminderState(text.replace("\"version\":1", "\"version\":2")) }
        assertThrows(Exception::class.java) { decodeClassReminderState(" ".repeat(256 * 1024 + 1)) }
        val lease = encodeClassReminderState(ClassReminderState(lease = QuietLease(QuietMode.SILENT, at(9, 41), 17)))
        assertThrows(Exception::class.java) { decodeClassReminderState(lease) }
    }
}
