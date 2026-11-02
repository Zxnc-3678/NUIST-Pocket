package cn.nuist.pocket

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import cn.nuist.pocket.data.ThemeAccent

@Composable
internal fun PocketTheme(accent: Int, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = remember(accent, dark) {
      val chosen = Color(0xFF000000 or ThemeAccent.readable(accent).toLong())
      if (dark) {
        val primary = if (accent == ThemeAccent.DEFAULT) Color(0xFF82ABFF) else lerp(chosen, Color.White, 0.55f)
        val container = lerp(chosen, Color(0xFF1D222A), 0.68f)
        darkColorScheme(
          primary = primary, onPrimary = Color(0xFF101B30),
          background = Color(0xFF101317), onBackground = Color(0xFFF0F2F6),
          surface = Color(0xFF1D222A), onSurface = Color(0xFFF0F2F6),
          surfaceDim = Color(0xFF101317), surfaceBright = Color(0xFF343B45),
          surfaceContainerLowest = Color(0xFF0C0F12), surfaceContainerLow = Color(0xFF181D24),
          surfaceContainer = Color(0xFF20262E), surfaceContainerHigh = Color(0xFF282F38),
          surfaceContainerHighest = Color(0xFF313944),
          surfaceVariant = Color(0xFF2B323C), onSurfaceVariant = Color(0xFFB9C2CF),
          primaryContainer = container, onPrimaryContainer = Color(0xFFE4ECFF),
          secondary = primary, onSecondary = Color(0xFF101B30),
          secondaryContainer = container, onSecondaryContainer = Color(0xFFE4ECFF),
          tertiaryContainer = Color(0xFF3A3025), onTertiaryContainer = Color(0xFFFFE3B0),
          outline = Color(0xFF515B68), outlineVariant = Color(0xFF343C47),
          error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        )
      } else lightColorScheme(
        primary = chosen, onPrimary = Color.White,
        background = Color(0xFFF5F7FC), surface = Color.White,
        surfaceContainerLow = Color(0xFFF5F7FC), surfaceContainer = Color(0xFFF1F4FA),
        surfaceContainerHigh = Color(0xFFEDF1F9), surfaceContainerHighest = Color(0xFFE6ECF5),
        onSurface = Color(0xFF182641), onBackground = Color(0xFF182641),
        secondary = chosen, onSecondary = Color.White,
        primaryContainer = lerp(chosen, Color.White, 0.90f), onPrimaryContainer = Color(0xFF182641),
        secondaryContainer = lerp(chosen, Color.White, 0.90f), onSecondaryContainer = chosen,
        tertiaryContainer = Color(0xFFFFF4DE), onTertiaryContainer = Color(0xFF4A3213),
        outline = Color(0xFFDCE2EF),
      )
    }
    val typography = remember { Typography() }
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
