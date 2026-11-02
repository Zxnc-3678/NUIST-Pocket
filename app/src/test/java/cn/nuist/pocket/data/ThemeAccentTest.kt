package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test

class ThemeAccentTest {
    @Test fun paleAndSaturatedCustomColorsKeepWhiteLabelsReadable() {
        for (red in 0..255 step 17) for (green in 0..255 step 17) for (blue in 0..255 step 17) {
            val input = red.shl(16) or green.shl(8) or blue
            val result = ThemeAccent.readable(input)
            assertTrue(ThemeAccent.whiteContrast(result) >= 4.5)
            if (ThemeAccent.whiteContrast(input) >= 4.5) assertEquals(input, result)
        }
    }
    @Test fun customInputAcceptsOnlySixDigitRgb() {
        assertEquals(0x12ABEF, ThemeAccent.parse(" #12abef "))
        for (value in listOf("#123", "#11223344", "-12345", "red", "12345G", "")) assertNull(ThemeAccent.parse(value))
    }
}
