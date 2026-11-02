package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TodoTest {
    private val today = LocalDate.of(2026, 10, 23)
    private fun task(id: String, date: LocalDate = today, time: LocalTime? = null, done: Boolean = false) =
        TodoItem(id, "作业 $id", date, time, "备注\n可以换行", done)

    @Test fun unicodeAndOptionalTimesSurviveRoundTrip() {
        val items = listOf(task("全天"), task("晚间", time = LocalTime.of(18, 30)), task("已完成", done = true))
        assertEquals(items, TodoCodec.decode(TodoCodec.encode(items)))
    }
    @Test fun completedTasksNeverAppearOnWidget() {
        assertTrue(widgetTodos(listOf(task("a", done = true)), today).isEmpty())
    }
    @Test fun overdueAndTodayStayVisibleWhenCoursesHaveFinished() {
        val overdue = task("overdue", today.minusDays(2))
        val current = task("today")
        assertEquals(listOf(overdue, current), widgetTodos(listOf(task("tomorrow", today.plusDays(1)), current, overdue), today))
    }
    @Test fun tomorrowAppearsOnlyAfterCurrentWorkIsCompleted() {
        val tomorrow = task("tomorrow", today.plusDays(1))
        assertEquals(listOf(tomorrow), widgetTodos(listOf(task("done", done = true), tomorrow, task("later", today.plusDays(2))), today))
    }
    @Test fun rolloverKeepsUnfinishedWorkInsteadOfDeletingIt() {
        val item = task("a")
        assertEquals(listOf(item), widgetTodos(listOf(item), today.plusDays(1)))
    }
    @Test fun timedTasksAreSortedBeforeAllDayWithinDate() {
        val early = task("early", time = LocalTime.of(8, 0))
        val late = task("late", time = LocalTime.of(20, 0))
        assertEquals(listOf(early, late, task("all")), widgetTodos(listOf(task("all"), late, early), today))
    }
    @Test fun emptyUpcomingDaysDoNotPullInDistantTasks() {
        assertTrue(widgetTodos(listOf(task("far", today.plusDays(3))), today).isEmpty())
    }
    @Test fun corruptOrUnsupportedStorageIsRejectedAsAWhole() {
        listOf("{", "{\"version\":2,\"items\":[]}", "{\"version\":1,\"items\":[{}]}",
            TodoCodec.encode(listOf(task("a"))).replace("2026-10-23", "2026-02-30")).forEach { json ->
            assertThrows(Exception::class.java) { TodoCodec.decode(json) }
        }
    }
    @Test fun duplicateIdsAndOversizedContentCannotOverwriteStore() {
        assertThrows(IllegalArgumentException::class.java) { TodoCodec.encode(listOf(task("a"), task("a"))) }
        assertThrows(IllegalArgumentException::class.java) { TodoCodec.encode(listOf(task("a").copy(title = " "))) }
        assertThrows(IllegalArgumentException::class.java) { TodoCodec.encode(listOf(task("a").copy(note = "a".repeat(501)))) }
        assertThrows(IllegalArgumentException::class.java) { TodoCodec.encode((0..1000).map { task(it.toString()) }) }
    }
    @Test fun byteLimitAlsoAppliesToOtherwiseValidChineseContent() {
        val items = (0 until 1000).map { task(it.toString()).copy(title = "课".repeat(120), note = "备".repeat(500)) }
        assertThrows(IllegalArgumentException::class.java) { TodoCodec.encode(items) }
    }
    @Test fun absentTasksKeepExistingFourRowCapacity() {
        assertEquals(4, widgetTodoLayout(346, 374, 1f, 4, 0).courseCapacity)
        assertFalse(widgetTodoLayout(346, 374, 1f, 4, 0).showSummary)
    }
    @Test fun largeWidgetFitsFourCoursesAndTwoTasks() {
        val layout = widgetTodoLayout(276, 455, 1f, 4, 3)
        assertEquals(4, layout.courseCapacity)
        assertEquals(2, layout.todoRows)
        assertTrue(layout.showSummary)
    }
    @Test fun shortWidgetsKeepOneClassAndTaskSummary() {
        val layout = widgetTodoLayout(554, 202, 1f, 3, 3)
        assertEquals(1, layout.courseCapacity)
        assertEquals(0, layout.todoRows)
        assertTrue(layout.showSummary)
        val smaller = widgetTodoLayout(554, 184, 1f, 3, 3)
        assertEquals(1, smaller.courseCapacity)
        assertFalse(smaller.showSummary)
    }
    @Test fun layoutBudgetsFitAcrossSizesAndLargeFonts() {
        for (width in listOf(180, 203, 276, 349, 554)) for (height in listOf(120, 160, 184, 250, 337, 374, 455, 600)) {
            for (scale in listOf(1f, 1.3f, 1.8f, 2f)) {
                val layout = widgetTodoLayout(width, height, scale, 5, 4)
                val row = widgetCourseRowDp(widgetLayoutPolicy(width, height, scale).compact)
                val used = minOf(5, layout.courseCapacity) * row + if (layout.showSummary) 28 + 28 * layout.todoRows else 0
                assertTrue("$width x $height @$scale", used <= (height / scale - WIDGET_HEADER_DP).coerceAtLeast(0f))
            }
        }
    }
    @Test fun todoLaunchDoesNotChangeOtherLaunchDestinations() {
        val requests = LaunchRequests()
        val todo = requests.open(0, today.toString(), todos = true)
        assertTrue(todo.todos); assertTrue(requests.consume(todo)); assertFalse(requests.consume(todo))
        assertFalse(requests.open(3).todos)
    }
}
