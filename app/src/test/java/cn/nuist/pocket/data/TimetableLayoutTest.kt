package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test

class TimetableLayoutTest {
    private fun course(name: String, first: Int = 1, last: Int = 2, week: Int = 8, room: String = "明德") =
        CourseSlot(name, name, name, "老师", room, 1, first, last, setOf(week), null, null, "2026-2027-1")
    @Test fun inactiveAlternativesDoNotCoverActiveClass() {
        val block = timetableBlocks(listOf(course("其他周", week = 7), course("本周")), 1, 8, true).single()
        assertEquals("本周", block.groups.first().course.name)
        assertEquals(2, block.groups.size)
        assertEquals(1, timetableBlocks(listOf(course("其他周", week = 7), course("本周")), 1, 8, false).single().groups.size)
    }
    @Test fun differentRoomsAcrossWeeksRemainOneBlockAndOneGroup() {
        val block = timetableBlocks(listOf(course("同一课程", week = 7, room = "实验室"), course("同一课程", room = "教室")), 1, 8, true).single()
        assertEquals(1, block.groups.size)
        assertEquals("教室", overviewLocation(block.groups.single(), 8))
    }
    @Test fun overlappingIntervalsKeepEveryArrangementIncludingChainedOverlap() {
        val blocks = timetableBlocks(listOf(course("A", 1, 2), course("B", 2, 3), course("C", 3, 4), course("D", 5, 6)), 1, 8, true)
        assertEquals(listOf(3, 1), blocks.map { it.groups.size })
        assertEquals(1, blocks.first().first); assertEquals(4, blocks.first().last)
    }
    @Test fun rowGeometryKeepsLunchAndEveningGaps() {
        assertEquals(68, timetableTop(2) - timetableTop(1))
        assertEquals(90, timetableTop(5) - timetableTop(4))
        assertEquals(90, timetableTop(9) - timetableTop(8))
        assertEquals(136, timetableHeight(1, 2))
        assertEquals(158, timetableHeight(4, 5))
    }
}
