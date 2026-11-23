package cn.nuist.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ReleaseHighlights(onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val press = rememberPocketPress()
    PickerSheet(onDismiss) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
            .padding(start = 22.dp, end = 22.dp, bottom = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 23.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("欢迎来到 v${BuildConfig.VERSION_NAME}", fontSize = 27.sp, lineHeight = 35.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, style = tabularTextStyle())
                Text("NUIST Pocket 正式版焕新", fontSize = 13.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
                ReleaseFeature(Icons.Outlined.Star, "壁纸铺满，四张轮换", "通栏壁纸最多存四张，能随机换图、关闭淡化，液态玻璃和四套风格随手试。")
                ReleaseFeature(Icons.Outlined.Edit, "课表长按就能改", "改简称、换颜色、停一节或跨周调课，长按空格加课，再同步也都在。")
                ReleaseFeature(Icons.Outlined.CheckCircle, "待办跟着时间走", "定时待办和课程排进同一条时间轴，做完在日程里轻轻一勾。")
                ReleaseFeature(Icons.Outlined.DateRange, "细节都带点弹性", "圆角抽屉、纱带滚轮，切页轻轻一淡，小组件的卡片也能一起调透。")
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDismiss, shape = CircleShape, interactionSource = press.interaction,
                modifier = press.modifier(Modifier.fillMaxWidth().heightIn(min = 50.dp))) { Text("开始体验") }
            Text("以后可在「设置 → 本次更新」随时重看", Modifier.fillMaxWidth().padding(top = 10.dp),
                color = colors.onSurfaceVariant.copy(alpha = 0.82f), textAlign = TextAlign.Center, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ReleaseFeature(icon: ImageVector, title: String, detail: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top) {
        Surface(modifier = Modifier.size(40.dp), color = colors.primaryContainer, shape = PocketShapes.Control) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = colors.primary) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp)
            Text(detail, color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}
