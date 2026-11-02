package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test

class ImageBudgetTest {
    @Test fun highResolutionPhoneKeeps1600PixelWallpaper() {
        assertEquals(ImageSize(1200,1600), imageSizeWithin(1200,1600,1600,widgetImagePixelBudget(1440,3200)))
    }
    @Test fun smallerScreenLimitsDecodedArea() {
        val pixels = widgetImagePixelBudget(720,1280)
        val size = imageSizeWithin(1600,1600,1600,pixels)
        assertTrue(size.width.toLong()*size.height <= pixels)
        assertTrue(size.width > 900)
    }
    @Test fun sourceIsNotUpscaledAndAspectRatioIsKept() {
        assertEquals(ImageSize(320,180), imageSizeWithin(320,180,1600,2_560_000))
        assertEquals(ImageSize(1600,800), imageSizeWithin(2400,1200,1600,2_560_000))
    }
    @Test fun legacyPortraitAndLandscapeShareBudget() {
        val budget = widgetImagePixelBudget(720,1280)
        val portrait = imageSizeWithin(276,455,900,budget/2,true)
        val landscape = imageSizeWithin(455,276,900,budget/2,true)
        assertTrue(portrait.width.toLong()*portrait.height+landscape.width.toLong()*landscape.height <= budget)
        assertTrue(portrait.height > 800)
    }
    @Test fun extremeAspectRatiosAndLargeIntegerInputsStayBounded() {
        for ((w,h) in listOf(1 to Int.MAX_VALUE,Int.MAX_VALUE to 1,Int.MAX_VALUE to Int.MAX_VALUE,1 to 1)) {
            for (budget in listOf(1,100,1000,2_560_000)) {
                val size=imageSizeWithin(w,h,1600,budget,true)
                assertTrue(size.width in 1..1600 && size.height in 1..1600)
                assertTrue(size.width.toLong()*size.height <= budget)
            }
        }
    }
    @Test fun invalidDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { imageSizeWithin(0,1,1600,100) }
        assertThrows(IllegalArgumentException::class.java) { imageSizeWithin(1,1,1600,0) }
        assertEquals(2_560_000,widgetImagePixelBudget(Int.MAX_VALUE,Int.MAX_VALUE))
    }
}
