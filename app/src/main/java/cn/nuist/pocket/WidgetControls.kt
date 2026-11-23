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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
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
import cn.nuist.pocket.data.WidgetTone
import cn.nuist.pocket.data.ThemeAccent
import cn.nuist.pocket.data.WallpaperStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun WidgetControls(onAdd: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { WallpaperStore.widget(context) }
    val preferences = remember { WidgetAppearance(context) }
    var imageRevision by remember { mutableIntStateOf(0) }
    var color by remember { mutableIntStateOf(preferences.color) }
    var fade by remember { mutableFloatStateOf(preferences.fade.toFloat()) }
    var tone by remember { mutableStateOf(preferences.tone) }
    var followAppAccent by remember { mutableStateOf(preferences.followAppAccent) }
    var independentAccent by remember { mutableIntStateOf(preferences.independentAccent) }
    var cardOpacity by remember { mutableIntStateOf(preferences.cardOpacity) }
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
    val solidBackground = if (tone == WidgetTone.DARK) tone.veil else color
    val accent = if (followAppAccent) MaterialTheme.colorScheme.primary.toArgb()
        else ThemeAccent.primary(independentAccent, tone == WidgetTone.DARK)
    val accentText = Color(tone.accentText(accent, solidBackground, wallpaper != null, fade.toInt(), cardOpacity))
    val ink = Color(tone.ink)
    val cardSurface = Color(tone.cardColor(cardOpacity))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSection {
            Text("小组件预览", fontWeight = FontWeight.Medium)
            val innerShape = RoundedCornerShape(dimensionResource(R.dimen.widget_inner_radius))
            Surface(shape = RoundedCornerShape(dimensionResource(R.dimen.widget_outer_radius))) {
                Box(Modifier.fillMaxWidth().height(200.dp).background(Color(solidBackground))) {
                    wallpaper?.let {
                        Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Color(tone.veilColor(fade.toInt()))))
                    }
                    Column(Modifier.fillMaxSize().padding(dimensionResource(R.dimen.widget_padding)),
                        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.widget_content_gap))) {
                        Surface(color = cardSurface, shape = innerShape, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("今天 · 今日课表", Modifier.weight(1f), color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Refresh, null, Modifier.size(22.dp), tint = accentText)
                                }
                            }
                        }
                        Surface(color = cardSurface, shape = innerShape, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("10:10", color = accentText, fontWeight = FontWeight.Bold, style = tabularTextStyle())
                                Box(Modifier.width(3.dp).height(28.dp).background(Color(accent), CircleShape))
                                Text("示例课程", color = ink, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (showTodos) Surface(color = cardSurface, shape = innerShape, modifier = Modifier.fillMaxWidth()) {
                            Text("今天待办 · 2项  ›", Modifier.padding(10.dp), color = accentText, fontSize = 12.sp, style = tabularTextStyle())
                        }
                    }
                }
            }
            Button(shape = PocketShapes.Control, onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("添加今日课表到桌面") }
            Text("长按桌面可手动添加，长按小组件可调整大小。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        SettingsSection {
            Text("小组件外观", fontWeight = FontWeight.Medium)
            AppearanceChoices(WidgetTone.entries, tone, WidgetTone::title) {
                tone = it
                preferences.tone = it
                refresh()
            }
            Text("卡片不透明度", fontSize = 13.sp)
            AppearanceChoices(WidgetAppearance.cardOpacities, cardOpacity, { "$it%" }) {
                cardOpacity = it
                preferences.cardOpacity = it
                refresh()
            }
            Text("小组件强调色", fontWeight = FontWeight.Medium)
            AppearanceChoices(listOf(true, false), followAppAccent, { if (it) "跟随 App 主题色" else "独立配色" }) {
                followAppAccent = it
                preferences.followAppAccent = it
                refresh()
            }
            if (!followAppAccent) {
                ThemeColorSwatches(ThemeAccent.presets, independentAccent, "小组件强调色") {
                    independentAccent = it
                    preferences.independentAccent = it
                    refresh()
                }
            }
            HorizontalDivider()
            Text("小组件背景", fontWeight = FontWeight.Medium)
            if (tone == WidgetTone.LIGHT) {
                Text("纯色背景", fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val names = listOf("默认", "浅蓝", "浅绿", "浅紫", "暖橙")
                    WidgetAppearance.colors.forEachIndexed { index, candidate ->
                        Surface(onClick = { if (color != candidate) { color = candidate; preferences.color = candidate; refresh() } },
                            color = Color(candidate), shape = CircleShape,
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "小组件背景${names[index]}"; selected = color == candidate }) {
                            Box(contentAlignment = Alignment.Center) { if (color == candidate) Icon(Icons.Outlined.Check, null, tint = accentText) }
                        }
                    }
                }
            }
            Text("有图片时优先显示图片；移除图片后使用所选纯色。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            OutlinedButton(shape = PocketShapes.Control, enabled = !saving, onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "正在处理图片…" else if (wallpaper == null) "选择背景图片" else "更换背景图片") }
            error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            if (wallpaper != null && maxOf(wallpaper!!.width, wallpaper!!.height) <= 320) {
                Text("当前图片分辨率较低；若来自旧版本，请重新选择原图，更新无法恢复已经缩小的细节。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (wallpaper != null) {
                Text("图片淡化 ${fade.toInt()}%", fontSize = 13.sp, style = tabularTextStyle())
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
    }
}
