package cn.nuist.pocket.data

import kotlin.math.pow

/** Keep white labels readable even when a custom color is very pale. */
object ThemeAccent {
    const val DEFAULT = 0x3155C6
    val presets = listOf(DEFAULT, 0x267561, 0x7B50A3, 0xB04F68, 0xA56722, 0x426D88)
    fun parse(value: String): Int? = value.trim().removePrefix("#")
        .takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toIntOrNull(16)
    fun hex(value: Int): String = "#%06X".format(value and 0xFFFFFF)
    fun whiteContrast(value: Int): Double {
        fun linear(channel: Int): Double {
            val s = channel / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val luminance = 0.2126 * linear(value shr 16 and 255) +
            0.7152 * linear(value shr 8 and 255) + 0.0722 * linear(value and 255)
        return 1.05 / (luminance + 0.05)
    }
    fun readable(value: Int): Int {
        var color = value and 0xFFFFFF
        while (whiteContrast(color) < 4.5) {
            color = ((color shr 16 and 255) * 0.94).toInt().shl(16) or
                ((color shr 8 and 255) * 0.94).toInt().shl(8) or ((color and 255) * 0.94).toInt()
        }
        return color
    }
}
