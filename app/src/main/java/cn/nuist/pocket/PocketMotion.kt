package cn.nuist.pocket

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal class PocketPress(val interaction: MutableInteractionSource, val scale: State<Float>) {
    fun modifier(modifier: Modifier = Modifier): Modifier = modifier.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

@Composable
internal fun rememberPocketPress(): PocketPress {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed.value) 0.975f else 1f,
        animationSpec = if (pressed.value) tween(75) else spring(dampingRatio = 0.72f, stiffness = 540f),
        label = "按压反馈",
    )
    return remember(interaction, scale) { PocketPress(interaction, scale) }
}

@Composable
internal fun PocketChoiceChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    val press = rememberPocketPress()
    FilterChip(selected, onClick, label, modifier = press.modifier(), shape = CircleShape,
        interactionSource = press.interaction)
}

@Composable
internal fun PocketNavigationIcon(icon: ImageVector, label: String, selected: Boolean) {
    val scale = animateFloatAsState(if (selected) 1f else 0.80f,
        spring(dampingRatio = 0.58f, stiffness = 490f), label = "导航胶囊")
    val color = animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        tween(if (selected) 140 else 115), label = "导航底色",
    )
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.requiredSize(64.dp, 32.dp).graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }.drawBehind { drawRoundRect(color.value, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)) })
        Icon(icon, contentDescription = label)
    }
}
