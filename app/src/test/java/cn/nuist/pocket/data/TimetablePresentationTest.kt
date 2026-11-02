package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test

class TimetablePresentationTest {
    @Test fun shortNamesKeepTheOriginalMinimumRowHeight() {
        assertEquals(68, timetableRequiredPeriodHeight(1, 2, 85f))
        assertEquals(68, timetableRequiredPeriodHeight(1, 1, 40f))
    }
    @Test fun fullLongTextGrowsItsSlotRatherThanTruncatingTheName() {
        val row = timetableRequiredPeriodHeight(1, 2, 173.5f)
        assertEquals(87, row)
        assertTrue(timetableHeight(1, 2, row) >= 173.5f)
    }
    @Test fun everyMeasuredSpanFitsAcrossBreaksAndFontSizedHeights() {
        for (first in 1..11) for (last in first..12) for (height in listOf(48f, 132f, 173.5f, 260f, 533f)) {
            val row = timetableRequiredPeriodHeight(first, last, height)
            assertTrue(row >= 68)
            assertTrue("$first–$last @ $height", timetableHeight(first, last, row) >= height)
        }
    }
    @Test fun longerTextCannotReduceTheSharedRowHeight() {
        for (first in 1..11) for (last in first..12) {
            assertTrue(timetableRequiredPeriodHeight(first, last, 300f) >= timetableRequiredPeriodHeight(first, last, 150f))
        }
    }
    @Test fun adaptiveRowsPreserveBothRestBreaks() {
        val row = 93
        assertEquals(row, timetableTop(2, row) - timetableTop(1, row))
        assertEquals(row + 22, timetableTop(5, row) - timetableTop(4, row))
        assertEquals(row + 22, timetableTop(9, row) - timetableTop(8, row))
    }
    @Test fun invalidMeasurementCannotCreateNegativeOrInvertedRows() {
        assertThrows(IllegalArgumentException::class.java) { timetableRequiredPeriodHeight(0, 2, 100f) }
        assertThrows(IllegalArgumentException::class.java) { timetableRequiredPeriodHeight(3, 2, 100f) }
        assertThrows(IllegalArgumentException::class.java) { timetableRequiredPeriodHeight(1, 2, Float.NaN) }
    }
}
