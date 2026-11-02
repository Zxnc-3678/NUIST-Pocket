package cn.nuist.pocket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Equal side slots center the title independently of the actions' visual weight. */
@Composable internal fun PocketPageHeader(
    onSettings: () -> Unit,
    settingsEnabled: Boolean,
    modifier: Modifier = Modifier,
    sideWidth: Dp = 96.dp,
    leading: @Composable BoxScope.() -> Unit = {},
    action: (@Composable RowScope.() -> Unit)? = null,
    title: @Composable BoxScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).heightIn(min = 52.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(sideWidth), contentAlignment = Alignment.CenterStart, content = leading)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center, content = title)
        Row(Modifier.width(sideWidth), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            action?.invoke(this)
            IconButton(enabled = settingsEnabled, onClick = onSettings) {
                Icon(Icons.Outlined.Settings, "设置", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
