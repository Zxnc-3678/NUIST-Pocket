package cn.nuist.pocket.data

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import kotlin.math.pow

/** Keep white labels readable even when a custom color is very pale. */
object ThemeAccent {
    const val DEFAULT = 0x3155C6
    val presets = listOf(DEFAULT, 0x267561, 0x7B50A3, 0xB04F68, 0xA56722, 0x426D88)
    fun parse(value: String): Int? = value.trim().removePrefix("#")
        .takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toIntOrNull(16)
    fun hex(value: Int): String = "#%06X".format(value and 0xFFFFFF)
    private fun luminance(value: Int): Double {
        fun linear(channel: Int): Double {
            val s = channel / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(value shr 16 and 255) +
            0.7152 * linear(value shr 8 and 255) + 0.0722 * linear(value and 255)
    }
    fun whiteContrast(value: Int): Double = 1.05 / (luminance(value) + 0.05)
    fun readable(value: Int): Int {
        var color = value and 0xFFFFFF
        while (whiteContrast(color) < 4.5) {
            color = ((color shr 16 and 255) * 0.94).toInt().shl(16) or
                ((color shr 8 and 255) * 0.94).toInt().shl(8) or ((color and 255) * 0.94).toInt()
        }
        return color
    }

    fun primary(value: Int, dark: Boolean): Int {
        val base = readable(value)
        val opaque = base or 0xFF000000.toInt()
        return when {
            !dark -> opaque
            base == DEFAULT -> 0xFF82ABFF.toInt()
            else -> lerp(Color(opaque), Color.White, 0.55f).toArgb()
        }
    }

    internal fun widgetForeground(value: Int, background: Int, dark: Boolean): Int {
        val surface = luminance(background) + 0.05
        val color = Color(value)
        val toward = if (dark) Color.White else Color.Black
        for (step in 0..9) {
            val candidate = lerp(color, toward, step / 10f).toArgb()
            val text = luminance(candidate) + 0.05
            if (maxOf(surface, text) / minOf(surface, text) >= 4.5) return candidate
        }
        return toward.toArgb()
    }

    internal fun wallpaperColors(bitmap: Bitmap): List<Int> {
        val palette = Palette.from(bitmap).generate()
        return (listOfNotNull(palette.vibrantSwatch, palette.mutedSwatch, palette.darkVibrantSwatch,
            palette.lightVibrantSwatch, palette.dominantSwatch) + palette.swatches.sortedByDescending { it.population })
            .map { readable(it.rgb) }.distinct().take(4)
    }
}
