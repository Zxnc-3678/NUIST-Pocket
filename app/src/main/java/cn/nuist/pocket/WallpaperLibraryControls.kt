package cn.nuist.pocket

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.nuist.pocket.data.WallpaperLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WallpaperLibraryControls(session: WallpaperSession) {
    val context = LocalContext.current
    val library = session.library
    val colors = MaterialTheme.colorScheme
    val enabled = library != null && !session.busy
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) session.change {
            add(context.contentResolver.openInputStream(uri) ?: error("无法读取图片"))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("壁纸库", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            repeat(WallpaperLibrary.LIMIT) { index ->
                val path = library?.paths?.getOrNull(index)
                val slot = Modifier.weight(1f).aspectRatio(0.78f)
                if (path == null) {
                    Surface(onClick = { picker.launch(arrayOf("image/*")) }, enabled = enabled,
                        modifier = slot, shape = PocketShapes.Control, color = colors.surfaceContainerLow,
                        border = BorderStroke(0.8.dp, colors.outlineVariant)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Add, "添加壁纸", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                        }
                    }
                } else key(path) {
                    WallpaperSlot(session.store, path, index + 1, path == library?.activeWallpaperPath, enabled, slot,
                        onSelect = { session.change { select(path) } },
                        onRemove = { session.change { remove(path) } })
                }
            }
        }
        if (library != null && library.paths.size >= 2) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("每次打开随机切换", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Switch(library.randomWallpaperOnLaunch, enabled = enabled, onCheckedChange = { checked ->
                    session.change { setRandomOnLaunch(checked) }
                })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("每次重新打开应用时从壁纸库中随机换一张", Modifier.weight(1f),
                    fontSize = 12.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant)
                TextButton(onClick = { session.change { randomize() } }, enabled = enabled) { Text("换一张") }
            }
        }
        if (session.busy && library != null) Text("正在处理壁纸…", color = colors.onSurfaceVariant, fontSize = 12.sp)
        session.error?.let { Text(it, color = colors.error, fontSize = 12.sp) }
    }
}

@Composable
private fun WallpaperSlot(
    store: WallpaperLibrary, path: String, position: Int, active: Boolean, enabled: Boolean, modifier: Modifier,
    onSelect: () -> Unit, onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val thumbnail by produceState<Bitmap?>(null, store, path) {
        value = withContext(Dispatchers.IO) { store.thumbnail(path) }
    }
    val image = remember(thumbnail) { thumbnail?.asImageBitmap() }
    Box(modifier) {
        Surface(onClick = onSelect, enabled = enabled, shape = PocketShapes.Control,
            modifier = Modifier.matchParentSize().semantics {
                contentDescription = "第 ${position} 张壁纸"
                selected = active
            }, color = colors.surfaceContainerLow,
            border = if (active) BorderStroke(2.dp, colors.primary) else BorderStroke(0.8.dp, colors.outlineVariant)) {
            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.align(Alignment.TopEnd).size(34.dp)) {
            Box(Modifier.size(22.dp).background(colors.surface.copy(alpha = 0.87f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, "删除第 ${position} 张壁纸", Modifier.size(14.dp), tint = colors.onSurface)
            }
        }
    }
}
