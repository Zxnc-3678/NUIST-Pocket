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
                Text("• 支持跟随系统深色模式，统一页面、课表、设置和弹窗配色。")
                Text("• 新增临时停课：在日程日期右侧菜单设置，课程展示和相关提醒同步调整。")
                Text("• 支持二维码与文件分享课表，生成后30分钟内可接收；收到的课表可独立查看、备注和删除。")
                Text("• 整理分享界面与页面切换动效，精简首次使用和帮助说明。")
                Text("以后可在“设置 → 本次更新”重看。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}
