package cn.nuist.pocket

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

internal data class TimetableWallpaperImages(val original: Bitmap, val glass: ImageBitmap)

internal fun prepareTimetableWallpaper(source: Bitmap, gridWidthDp: Int, gridHeightDp: Int): TimetableWallpaperImages {
    val sampleScale = minOf(1f, 512f / maxOf(source.width, source.height))
    val width = (source.width * sampleScale).roundToInt().coerceAtLeast(1)
    val height = (source.height * sampleScale).roundToInt().coerceAtLeast(1)
    val small = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val colorMatrix = ColorMatrix().apply {
        setSaturation(1.10f)
        postConcat(ColorMatrix().apply { setScale(1.025f, 1.025f, 1.025f, 1f) })
    }
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(colorMatrix) }
    Canvas(small).drawBitmap(source, null, Rect(0, 0, width, height), paint)
    val pixels = IntArray(width * height)
    val scratch = IntArray(pixels.size)
    small.getPixels(pixels, 0, width, 0, 0, width, height)
    val cropScale = maxOf(gridWidthDp.toFloat() / width, gridHeightDp.toFloat() / height)
    val radius = (8f / cropScale).roundToInt().coerceIn(1, 16)
    // Three separable box passes approximate a Gaussian at the sampled resolution.
    repeat(3) {
        blurLines(pixels, scratch, height, width, width, 1, radius)
        blurLines(scratch, pixels, width, height, 1, width, radius)
    }
    small.setPixels(pixels, 0, width, 0, 0, width, height)
    return TimetableWallpaperImages(source, small.asImageBitmap())
}

private fun blurLines(
    input: IntArray, output: IntArray, lines: Int, length: Int,
    lineStride: Int, pixelStride: Int, radius: Int,
) {
    val diameter = radius * 2 + 1
    val last = length - 1
    repeat(lines) { line ->
        val base = line * lineStride
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val pixel = input[base + offset.coerceIn(0, last) * pixelStride]
            alpha += pixel ushr 24
            red += pixel shr 16 and 255
            green += pixel shr 8 and 255
            blue += pixel and 255
        }
        repeat(length) { position ->
            output[base + position * pixelStride] =
                ((alpha / diameter) shl 24) or ((red / diameter) shl 16) or
                    ((green / diameter) shl 8) or (blue / diameter)
            val leaving = input[base + (position - radius).coerceAtLeast(0) * pixelStride]
            val entering = input[base + (position + radius + 1).coerceAtMost(last) * pixelStride]
            alpha += (entering ushr 24) - (leaving ushr 24)
            red += (entering shr 16 and 255) - (leaving shr 16 and 255)
            green += (entering shr 8 and 255) - (leaving shr 8 and 255)
            blue += (entering and 255) - (leaving and 255)
        }
    }
}

internal class GlassViewport {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
}

@Composable
internal fun GlassCourseLayer(
    wallpaper: TimetableWallpaperImages?, viewport: GlassViewport, fill: Color, dark: Boolean,
    courseTint: Color? = null, scrollPosition: State<Pair<Float, Int>>? = null,
    pressScale: State<Float>? = null,
) {
    var tile by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val placement = if (wallpaper == null) Modifier else Modifier.onPlaced { tile = it }
    Box(Modifier.fillMaxSize().then(placement).drawWithCache {
        val reflection = Brush.linearGradient(
            0f to Color.White.copy(alpha = if (dark) 0.14f else 0.22f),
            0.44f to Color.White.copy(alpha = if (dark) 0.025f else 0.05f),
            0.76f to Color.Transparent,
            1f to Color.White.copy(alpha = if (dark) 0.04f else 0.07f),
        )
        onDrawBehind {
            if (wallpaper != null) {
                // These motions also move layer coordinates without remeasuring the cell.
                scrollPosition?.value
                pressScale?.value
                val root = viewport.coordinates
                val cell = tile
                if (root != null && root.isAttached && cell != null && cell.isAttached) {
                    val cellOrigin = root.localPositionOf(cell, Offset.Zero)
                    val cellEnd = root.localPositionOf(cell, Offset(size.width, size.height))
                    val scaleX = (cellEnd.x - cellOrigin.x) / size.width
                    val scaleY = (cellEnd.y - cellOrigin.y) / size.height
                    val source = wallpaper.original
                    val cropScale = maxOf(root.size.width.toFloat() / source.width, root.size.height.toFloat() / source.height)
                    val width = (source.width * cropScale).roundToInt()
                    val height = (source.height * cropScale).roundToInt()
                    val imageLeft = ((root.size.width - width) / 2f).roundToInt() - cellOrigin.x
                    val imageTop = ((root.size.height - height) / 2f).roundToInt() - cellOrigin.y
                    withTransform({
                        scale(1f / scaleX, 1f / scaleY, pivot = Offset.Zero)
                        translate(imageLeft, imageTop)
                    }) {
                        drawImage(wallpaper.glass, dstOffset = IntOffset.Zero, dstSize = IntSize(width, height))
                    }
                }
            }
            drawRect(fill)
            if (courseTint != null) drawRect(courseTint.copy(alpha = 0.12f))
            drawRect(reflection)
        }
    })
}
