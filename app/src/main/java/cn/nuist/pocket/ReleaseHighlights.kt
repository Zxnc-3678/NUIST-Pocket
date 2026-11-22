package cn.nuist.pocket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ReleaseHighlights(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("v${BuildConfig.VERSION_NAME} 更新小提示") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("• 修复课程卡片跨过午休、晚休的问题，同一门跨时段课程会分段显示。")
                Text("• 重叠课程按上午、下午、晚间分别归组，其他安排与详情入口更准确。")
                Text("• 调整分段卡片的文字空间，继续完整显示课程名称。")
                Text("以后可在“设置 → 本次更新”重看。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}
