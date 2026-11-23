package cn.nuist.pocket

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

internal val RadiusDialog = 28.dp
internal val RadiusCard = 20.dp
internal val RadiusControl = 12.dp
internal val RadiusCompact = 8.dp

internal object PocketShapes {
    val Card = RoundedCornerShape(RadiusCard)
    val Hero = Card
    val Control = RoundedCornerShape(RadiusControl)
    val Compact = RoundedCornerShape(RadiusCompact)
    val Dialog = RoundedCornerShape(RadiusDialog)
    val Sheet = RoundedCornerShape(topStart = RadiusDialog, topEnd = RadiusDialog)
}

internal val LocalPocketImmersive = compositionLocalOf { false }

@Composable
private fun immersiveEdge(): Color = Color.White.copy(alpha = if (LocalPocketDark.current) 0.15f else 0.50f)

private val courseColors = listOf(0xFF477CAB, 0xFFB27D32, 0xFF526AA6, 0xFFAD667E, 0xFF428874, 0xFF8764AB)

internal fun courseAccentColor(code: String, name: String, dark: Boolean, customColorHex: String? = null): Color {
    val color = if (customColorHex == null) Color(courseColors[Math.floorMod((code + name).hashCode(), courseColors.size)])
        else Color(android.graphics.Color.parseColor(customColorHex))
    return if (dark) lerp(color, Color.White, 0.24f) else color
}

@Composable
internal fun PocketCard(
    modifier: Modifier = Modifier,
    shape: Shape = PocketShapes.Card,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    Surface(modifier = modifier, shape = shape, color = pocketCardSurface(color), border = pocketCardBorder(), content = content)
}

@Composable
internal fun PocketCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = PocketShapes.Card,
    color: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke? = pocketCardBorder(),
    content: @Composable () -> Unit,
) {
    val press = rememberPocketPress()
    Surface(onClick = onClick, modifier = press.modifier(modifier), shape = shape, color = pocketCardSurface(color),
        border = border, interactionSource = press.interaction, content = content)
}

@Composable
private fun pocketCardSurface(color: Color): Color =
    if (LocalPocketImmersive.current) color.copy(alpha = if (LocalPocketDark.current) 0.62f else 0.72f) else color

@Composable
internal fun pocketCardBorder(): BorderStroke {
    if (LocalPocketImmersive.current) return BorderStroke(0.8.dp, immersiveEdge())
    val alpha = if (LocalPocketDark.current) 0.45f else 0.75f
    return BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha))
}

@Composable
internal fun tabularTextStyle(base: TextStyle = LocalTextStyle.current): TextStyle =
    base.copy(fontFeatureSettings = "tnum")
