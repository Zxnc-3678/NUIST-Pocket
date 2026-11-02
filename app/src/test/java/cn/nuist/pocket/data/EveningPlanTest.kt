package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class EveningPlanTest {
    private val semester = Semester("2026-2027-1", LocalDate.of(2026, 8, 31))
    private val tomorrow = LocalDate.of(2026, 10, 23)
    private fun course(name: String = "早课", first: Int = 1, week: Int = 8, day: Int = 5, room: String = "示例教学楼A101") =
        CourseSlot(name, name, name, "老师", room, day, first, first + 1, setOf(week), null, null, semester.code)
    private fun snapshot(vararg rows: CourseSlot) = WidgetSchedule(semester, rows.toList(), listOf(
        Period(1, LocalTime.of(8, 0), LocalTime.of(8, 45), true),
        Period(3, LocalTime.of(10, 10), LocalTime.of(10, 55), true)), "测试")

    @Test fun suspendedTomorrowIsNotAnnouncedAsAnEarlyClass() {
        val data = snapshot(course()).copy(suspendedDates = setOf(tomorrow))
        assertEquals("明日课表 · 已设置停课", eveningMessage(data, tomorrow)!!.title)
        assertTrue(eveningMessage(data.copy(suspendedDates = emptySet()), tomorrow)!!.title.startsWith("明天早八"))
    }

    @Test fun firstClassUsesActualTimeAndSelectedWeeks() {
        val message = eveningMessage(snapshot(course("晚", 3), course(), course("非本周", 1, 7)), tomorrow)!!
        assertTrue(message.title.startsWith("明天早八"))
        assertTrue(message.title.contains("2段"))
        assertTrue(message.body.startsWith("早课 · 示例教学楼A101"))
        assertEquals(tomorrow, message.date)
        assertTrue(eveningMessage(snapshot(course(first = 3)), tomorrow)!!.title.startsWith("明天早十"))
    }
    @Test fun currentWeekRoomIsUsedAndInactiveRoomIsNeverShown() {
        val data = snapshot(course(week = 7, room = "旧地点"), course(room = "本周地点"))
        val message = eveningMessage(data, tomorrow)!!
        assertTrue(message.body.contains("本周地点")); assertFalse(message.body.contains("旧地点"))
    }
    @Test fun noClassGreetingsRotateAndPendingRowsRemainExplicit() {
        val data = snapshot(course(day = 1))
        val messages = (0L..5L).map { eveningMessage(data, tomorrow.plusDays(it))!! }
        assertTrue(messages.first().title.contains("暂无已排"))
        val pending = course().copy(weekday = null, weeks = emptySet())
        assertTrue(eveningMessage(snapshot(pending), tomorrow)!!.body.contains("待排课程"))
        assertTrue(messages.map { it.body }.distinct().size > 3)
    }
    @Test fun unknownEmptyOrOutOfTermNeverClaimsDayOff() {
        assertNull(eveningMessage(snapshot(), tomorrow))
        assertNull(eveningMessage(snapshot(course()), semester.firstMonday.minusDays(1)))
        assertNull(eveningMessage(snapshot(course()), semester.firstMonday.plusWeeks(25)))
    }
    @Test fun expiredLoginStillUsesCacheAndExplainsIt() {
        assertTrue(eveningMessage(snapshot(course()).copy(loginRequired = true), tomorrow)!!.body.contains("学校连接待验证"))
    }
    @Test fun alarmWindowAndNextDateSuppressOvernightDelivery() {
        val time = LocalTime.of(21, 0)
        assertFalse(reminderWindow(tomorrow.atTime(20, 59), time))
        assertTrue(reminderWindow(tomorrow.atTime(21, 0), time))
        assertTrue(reminderWindow(tomorrow.atTime(23, 58), time))
        assertFalse(reminderWindow(tomorrow.atTime(0, 10), time))
        assertEquals(tomorrow.atTime(time), nextEvening(tomorrow.atTime(20, 59), time))
        assertEquals(tomorrow.plusDays(1).atTime(time), nextEvening(tomorrow.atTime(time), time))
    }
    @Test fun lastMinuteReminderIsValidUntilMidnight() {
        assertTrue(reminderWindow(tomorrow.atTime(23, 59, 30), LocalTime.of(23, 59)))
        assertFalse(reminderWindow(tomorrow.plusDays(1).atStartOfDay(), LocalTime.of(23, 59)))
    }
}


