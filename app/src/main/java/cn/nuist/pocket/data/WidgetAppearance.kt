package cn.nuist.pocket.data

import android.content.Context

class WidgetAppearance(context: Context) {
    private val prefs = context.getSharedPreferences("widget_appearance", Context.MODE_PRIVATE)
    companion object {
        const val MIN_FADE = 40
        const val MAX_FADE = 95
        val colors = listOf(0xFFF5F7FC.toInt(), 0xFFEEF4FF.toInt(), 0xFFEDF7F1.toInt(), 0xFFF5EFFF.toInt(), 0xFFFFF4E8.toInt())
    }
    var color: Int
        get() = prefs.getInt("color", colors.first()).takeIf { it in colors } ?: colors.first()
        set(value) { require(value in colors); prefs.edit().putInt("color", value).apply() }
    var fade: Int
        get() = prefs.getInt("fade", 70).coerceIn(MIN_FADE, MAX_FADE)
        set(value) { prefs.edit().putInt("fade", value.coerceIn(MIN_FADE, MAX_FADE)).apply() }
    var showTodos: Boolean
        get() = prefs.getBoolean("show_todos", true)
        set(value) { prefs.edit().putBoolean("show_todos", value).apply() }
}
