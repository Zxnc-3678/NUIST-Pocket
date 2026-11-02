package cn.nuist.pocket

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.nuist.pocket.data.WidgetAppearance
import cn.nuist.pocket.data.WallpaperStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun WidgetControls() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { WallpaperStore.widget(context) }
    val preferences = remember { WidgetAppearance(context) }
    var imageRevision by remember { mutableIntStateOf(0) }
    var color by remember { mutableIntStateOf(preferences.color) }
    var fade by remember { mutableFloatStateOf(preferences.fade.toFloat()) }
    var showTodos by remember { mutableStateOf(preferences.showTodos) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val wallpaper by produceState<Bitmap?>(null, imageRevision) { value = withContext(Dispatchers.IO) { store.loadPreview() } }
    fun refresh() { TodayWidgetProvider.requestRefresh(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            saving = true; error = null
            try { withContext(Dispatchers.IO) {
                store.import(context.contentResolver.openInputStream(uri) ?: error("无法读取图片"))
            } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = if (failure is IllegalArgumentException) failure.message else "图片读取失败，原背景已保留，请重试。" }
            finally { saving = false; imageRevision++; refresh() }
        }
    }
    Text("小组件背景", fontWeight = FontWeight.Bold)
    val innerShape = RoundedCornerShape(dimensionResource(R.dimen.widget_inner_radius))
    Surface(shape = RoundedCornerShape(dimensionResource(R.dimen.widget_outer_radius))) {
        Box(Modifier.fillMaxWidth().height(200.dp).background(Color(color))) {
            wallpaper?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = fade / 100f)))
            }
            Column(Modifier.fillMaxSize().padding(dimensionResource(R.dimen.widget_padding)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = Color(0xD9FFFFFF), shape = innerShape, modifier = Modifier.fillMaxWidth()) {
                    Text("今天 · 今日课表", Modifier.padding(10.dp), color = Color(0xFF182641), fontWeight = FontWeight.Bold)
                }
                Surface(color = Color.White, shape = innerShape, modifier = Modifier.fillMaxWidth()) { Text("10:10  示例课程", Modifier.padding(12.dp), color = Color(0xFF182641)) }
                Spacer(Modifier.weight(1f))
                if (showTodos) Surface(color = Color(0xD9FFFFFF), shape = innerShape, modifier = Modifier.fillMaxWidth()) {
                    Text("今天待办 · 2项  ›", Modifier.padding(10.dp), color = Color(0xFF3155C6), fontSize = 12.sp)
                }
            }
        }
    }
    Text("纯色背景", fontSize = 13.sp)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val names = listOf("默认", "浅蓝", "浅绿", "浅紫", "暖橙")
        WidgetAppearance.colors.forEachIndexed { index, candidate ->
            Surface(onClick = { if (color != candidate) { color = candidate; preferences.color = candidate; refresh() } }, color = Color(candidate), shape = CircleShape,
                modifier = Modifier.size(44.dp).semantics { contentDescription = "小组件背景${names[index]}"; selected = color == candidate }) {
                Box(contentAlignment = Alignment.Center) { if (color == candidate) Icon(Icons.Outlined.Check, null, tint = Color(0xFF3155C6)) }
            }
        }
    }
    Text("有图片时优先显示图片；移除图片后使用所选纯色。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    OutlinedButton(enabled = !saving, onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "正在处理图片…" else if (wallpaper == null) "选择背景图片" else "更换背景图片") }
    error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    if (wallpaper != null && maxOf(wallpaper!!.width, wallpaper!!.height) <= 320) {
        Text("当前图片分辨率较低；若来自旧版本，请重新选择原图，更新无法恢复已经缩小的细节。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (wallpaper != null) {
        Text("图片淡化 ${fade.toInt()}%", fontSize = 13.sp)
        Slider(value = fade, onValueChange = { fade = it }, valueRange = WidgetAppearance.MIN_FADE.toFloat()..WidgetAppearance.MAX_FADE.toFloat(),
            onValueChangeFinished = { preferences.fade = fade.toInt(); refresh() })
        TextButton(enabled = !saving, onClick = { scope.launch { saving = true; try { withContext(Dispatchers.IO) { store.clear() }; imageRevision++; refresh() } finally { saving = false } } }) { Text("移除背景图片") }
    }
    Text("与App内课程表壁纸分别保存。图片仅存本机，最大16MB，保留最长边1600像素并按屏幕限制内存；调低淡化可减少发白。设置应用于全部今日课表小组件。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("在小组件底部显示待办", Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(showTodos, onCheckedChange = { showTodos = it; preferences.showTodos = it; refresh() })
    }
    Text("优先显示今天和逾期未完成事项，都完成后预览明天。较大的小组件最多显示两条，空间不足时只显示项数；点待办区域进入全部未完成列表。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
}
