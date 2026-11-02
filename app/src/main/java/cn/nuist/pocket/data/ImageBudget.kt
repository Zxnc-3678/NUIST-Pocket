package cn.nuist.pocket.data

import kotlin.math.floor
import kotlin.math.sqrt

data class ImageSize(val width: Int, val height: Int)

/** Fit both edge length and decoded pixel count; source images never grow by default. */
fun imageSizeWithin(width: Int, height: Int, maxEdge: Int, maxPixels: Int, upscale: Boolean = false): ImageSize {
    require(width > 0 && height > 0 && maxEdge > 0 && maxPixels > 0)
    val edgeScale = maxEdge.toDouble() / maxOf(width, height)
    val pixelScale = sqrt(maxPixels.toDouble() / (width.toDouble() * height))
    val scale = minOf(edgeScale, pixelScale, if (upscale) Double.MAX_VALUE else 1.0)
    var fittedWidth = maxOf(1, floor(width * scale).toInt())
    var fittedHeight = maxOf(1, floor(height * scale).toInt())
    // Clamping an extremely thin image to one pixel can otherwise exceed the area budget.
    if (fittedWidth.toLong() * fittedHeight > maxPixels) {
        if (fittedWidth >= fittedHeight) fittedWidth = maxOf(1, maxPixels / fittedHeight)
        else fittedHeight = maxOf(1, maxPixels / fittedWidth)
    }
    return ImageSize(fittedWidth, fittedHeight)
}

/** Use at most one screen of pixels, below Android's 1.5-screen widget bitmap limit. */
fun widgetImagePixelBudget(screenWidth: Int, screenHeight: Int): Int =
    (screenWidth.coerceAtLeast(1).toLong() * screenHeight.coerceAtLeast(1)).coerceAtMost(1600L * 1600).toInt()
