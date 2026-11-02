package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test

class WidgetLayoutTest {
    @Test fun narrowThreeCellWidgetUsesCompactTimeRow() {
        val policy = widgetLayoutPolicy(203, 337, 1f)
        assertTrue(policy.compact)
        assertEquals(1, policy.detailLines)
        assertTrue(policy.capacity >= 1)
    }
    @Test fun fourAndFiveCellWidgetsKeepCourseRowsCompact() {
        val four = widgetLayoutPolicy(276, 455, 1f)
        val five = widgetLayoutPolicy(349, 455, 1f)
        assertFalse(four.compact); assertFalse(five.compact)
        assertEquals(1, four.detailLines)
        assertEquals(1, five.detailLines)
        assertTrue(four.capacity >= 4)
        assertTrue(five.capacity >= 4)
    }
    @Test fun shortWideLandscapeWidgetKeepsOneCourseInsteadOfOverflowing() {
        val policy = widgetLayoutPolicy(554, 184, 1f)
        assertTrue(policy.compact)
        assertEquals(1, policy.nameLines)
        assertEquals(1, policy.capacity)
    }
    @Test fun actualXiaomiFourByFourDimensionsAllowFourCourseRows() {
        // AppWidgetManager reported 346.73526 × 374.73526 dp on the user's launcher.
        assertEquals(4, widgetLayoutPolicy(346, 374, 1f).capacity)
    }
    @Test fun increasingTextSizeReducesCapacityWithinTheSameAllocatedHeight() {
        assertTrue(widgetLayoutPolicy(349, 455, 1.5f).capacity < widgetLayoutPolicy(349, 455, 1f).capacity)
        assertEquals(0, widgetLayoutPolicy(180, 130, 1f).capacity)
    }
}
