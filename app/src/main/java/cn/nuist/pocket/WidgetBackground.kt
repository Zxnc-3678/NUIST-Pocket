package cn.nuist.pocket

import android.graphics.*
import android.content.Context
import android.os.Build
import cn.nuist.pocket.data.*

/** Decode once per refresh, cap memory for the current screen, and share across every size. */
internal fun loadWidgetBackground(context: Context): Bitmap? {
    val source = WallpaperStore.widget(context).load() ?: return null
    val metrics = context.resources.displayMetrics
    return prepareWidgetBackground(source, metrics.widthPixels, metrics.heightPixels)
}

internal fun prepareWidgetBackground(source: Bitmap, screenWidth: Int, screenHeight: Int): Bitmap {
    val size = imageSizeWithin(source.width, source.height, 1600, widgetImagePixelBudget(screenWidth, screenHeight))
    val bounded = if (source.width == size.width && source.height == size.height) source
        else Bitmap.createScaledBitmap(source, size.width, size.height, true).also { source.recycle() }
    if (Build.VERSION.SDK_INT < 31) return bounded
    // This is a dedicated decode, not a bitmap already displayed in Compose or an ImageView.
    return bounded.asShared().also { if (it !== bounded) bounded.recycle() }
}

/** Android 11 doesn't read clipToOutline from XML. Bake just its two size variants,
 * splitting the screen-sized memory budget between the two rounded backgrounds. */
@JvmOverloads
internal fun legacyWidgetBackground(source: Bitmap, width: Int, height: Int, fade: Int, maxPixels: Int = 450_000): Bitmap {
    val size = imageSizeWithin(width, height, 900, maxPixels, upscale = true)
    val ratio = minOf(size.width.toFloat() / width, size.height.toFloat() / height)
    val output = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val bounds = RectF(0f, 0f, output.width.toFloat(), output.height.toFloat())
    val radius = 24f * ratio
    val scale = maxOf(bounds.width() / source.width, bounds.height() / source.height)
    val matrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate((bounds.width() - source.width * scale) / 2f, (bounds.height() - source.height * scale) / 2f)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { it.setLocalMatrix(matrix) }
    }
    Canvas(output).apply {
        drawRoundRect(bounds, radius, radius, paint)
        paint.shader = null; paint.color = Color.argb(fade.coerceIn(WidgetAppearance.MIN_FADE, WidgetAppearance.MAX_FADE) * 255 / 100, 255, 255, 255)
        drawRoundRect(bounds, radius, radius, paint)
    }
    return output
}
