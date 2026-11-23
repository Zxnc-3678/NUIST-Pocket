package cn.nuist.pocket.data

import android.content.Context
import android.content.res.Configuration
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

internal enum class WidgetTone(val title: String, val surface: Int, val ink: Int, val muted: Int, val veil: Int) {
    LIGHT("浅色底深字", 0xFFFFFFFF.toInt(), 0xFF182641.toInt(), 0xFF5E6B80.toInt(), 0xFFFFFFFF.toInt()),
    DARK("深色底浅字", 0xFF1D222A.toInt(), 0xFFF0F2F6.toInt(), 0xFFB9C2CF.toInt(), 0xFF101317.toInt());

    fun cardColor(opacity: Int): Int = surface.withOpacity(opacity)
    fun veilColor(fade: Int): Int = veil.withOpacity(fade)

    fun accentText(accent: Int, solidBackground: Int, hasWallpaper: Boolean, fade: Int, cardOpacity: Int): Int {
        val dark = this == DARK
        val background = if (hasWallpaper) {
            val wallpaperExtreme = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            ColorUtils.compositeColors(veilColor(fade), wallpaperExtreme)
        } else solidBackground
        val card = ColorUtils.compositeColors(cardColor(cardOpacity), background)
        return ThemeAccent.widgetForeground(accent, card, dark)
    }
}

private fun Int.withOpacity(percent: Int): Int =
    ColorUtils.setAlphaComponent(this, (percent * 255 / 100f).roundToInt())

class WidgetAppearance(private val context: Context) {
    private val prefs = context.getSharedPreferences("widget_appearance", Context.MODE_PRIVATE)
    companion object {
        const val MIN_FADE = 40
        const val MAX_FADE = 95
        val colors = listOf(0xFFF5F7FC.toInt(), 0xFFEEF4FF.toInt(), 0xFFEDF7F1.toInt(), 0xFFF5EFFF.toInt(), 0xFFFFF4E8.toInt())
        internal val cardOpacities = listOf(50, 70, 85, 100)
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
    internal var followAppAccent: Boolean
        get() = prefs.getBoolean("follow_app_accent", true)
        set(value) { prefs.edit().putBoolean("follow_app_accent", value).apply() }
    internal var independentAccent: Int
        get() = prefs.getInt("independent_accent", ThemeAccent.DEFAULT)
        set(value) { prefs.edit().putInt("independent_accent", value).apply() }
    internal var tone: WidgetTone
        get() = if (prefs.getBoolean("dark_tone", false)) WidgetTone.DARK else WidgetTone.LIGHT
        set(value) { prefs.edit().putBoolean("dark_tone", value == WidgetTone.DARK).apply() }
    internal var cardOpacity: Int
        get() = when (val saved = prefs.getInt("card_opacity", 100)) {
            65 -> 70
            else -> saved
        }
        set(value) { prefs.edit().putInt("card_opacity", value).apply() }

    private val savedAppPrimary: Int get() = prefs.getInt("app_primary", 0)

    internal fun syncAppPrimary(primary: Int): Boolean {
        if (savedAppPrimary == primary) return false
        prefs.edit().putInt("app_primary", primary).apply()
        return true
    }

    internal fun appPrimary(preferences: AppPreferences = AppPreferences(context)): Int {
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return ThemeAccent.primary(preferences.themeAccent, preferences.themeMode.isDark(systemDark))
    }

    internal val accent: Int
        get() = if (followAppAccent) {
            savedAppPrimary.takeIf { it != 0 } ?: appPrimary()
        } else ThemeAccent.primary(independentAccent, tone == WidgetTone.DARK)
}
