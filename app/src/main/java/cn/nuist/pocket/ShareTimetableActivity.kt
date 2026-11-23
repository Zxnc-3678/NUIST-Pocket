package cn.nuist.pocket

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import cn.nuist.pocket.data.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.time.LocalDate
import java.time.ZoneId
import java.io.File

private fun qrBitmap(text: String): Bitmap {
    val grid = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 800, 800,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2))
    val pixels = IntArray(grid.width * grid.height) { index ->
        if (grid[index % grid.width, index / grid.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, grid.width, grid.height, Bitmap.Config.ARGB_8888)
}

class ShareTimetableActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences = remember { AppPreferences(this) }
            val own = remember { loadLocalSchedule(this) }
            val store = remember { getSharedPreferences("shared_timetable", MODE_PRIVATE) }
            var received by remember { mutableStateOf(store.getString("payload", null)?.let(::decodeSharedTimetable)) }
            var receivedName by remember { mutableStateOf(store.getString("name", "")!!) }
            val collector = remember { TimetableShareCollector() }
            var progress by remember { mutableStateOf("") }
            var screen by remember { mutableStateOf("home") }
            var page by remember { mutableIntStateOf(0) }
            var frames by remember { mutableStateOf<List<String>>(emptyList()) }
            var confirmDelete by remember { mutableStateOf(false) }
            var editingName by remember { mutableStateOf(false) }
            var nameDraft by remember { mutableStateOf("") }
            fun receiveFrame(frame: String) {
                val payload = collector.accept(frame)
                progress = if (collector.expired) "分享已过期，请对方重新生成。" else "已接收 ${collector.received}/${collector.total} 部分"
                if (payload != null) {
                    store.edit().putString("payload", payload).remove("name").apply()
                    received = decodeSharedTimetable(payload)
                    receivedName = ""
                    progress = "课表已接收，保存在本机。"
                }
            }
            val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val frame = ScanContract().parseResult(result.resultCode, result.data).contents!!
                    receiveFrame(frame)
                }
            }
            val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val uri = result.data!!.data!!
                    val frame = contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    receiveFrame(frame)
                }
            }
            fun scan() {
                val options = ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("扫描对方分享的课表二维码").setBeepEnabled(false)
                scanner.launch(ScanContract().createIntent(this, options))
            }
            BackHandler(enabled = screen != "home") { screen = "home" }
            PocketTheme(preferences.themeAccent, preferences.themeMode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (screen == "home") finish() else screen = "home" }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                            }
                            Text(if (screen == "view") receivedName.ifBlank { "收到的课表" } else "面对面分享课表",
                                modifier = Modifier.weight(1f), fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        AnimatedContent(targetState = screen,
                            transitionSpec = { fadeIn(tween(170)) togetherWith fadeOut(tween(100)) }, label = "课表分享页面切换") { displayedScreen ->
                        when (displayedScreen) {
                            "view" -> received?.let { schedule ->
                                TimetableScreen(schedule.courses, schedule.periods, schedule.semester,
                                    LocalDate.now(ZoneId.of("Asia/Shanghai")), false, preferences, {}, true,
                                    suspendedDates = schedule.suspendedDates, settingsOpensDisplay = true)
                            }
                            "show" -> {
                                val image = remember(frames, page) { qrBitmap(frames[page]) }
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("请让对方在 NUIST Pocket 中扫码", fontWeight = FontWeight.Medium)
                                    Text("课表包含课程、地点、教师和上课时间，请只分享给你信任的人。")
                                    Text("二维码自生成起 30 分钟内有效；过期后返回并重新生成。", fontSize = 13.sp, style = tabularTextStyle())
                                    PocketCard(shape = PocketShapes.Hero) {
                                        Image(image.asImageBitmap(), "课表二维码", Modifier.fillMaxWidth().padding(12.dp).aspectRatio(1f))
                                    }
                                    Text("第 ${page + 1} 张，共 ${frames.size} 张", fontWeight = FontWeight.Medium, style = tabularTextStyle())
                                    if (frames.size > 1) Text("请依次扫描全部二维码；对方可在每次扫码后继续扫描下一张。", fontSize = 13.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        OutlinedButton(shape = PocketShapes.Control, enabled = page > 0, onClick = { page-- }) { Text("上一张") }
                                        Button(shape = PocketShapes.Control, enabled = page + 1 < frames.size, onClick = { page++ }) { Text("下一张") }
                                    }
                                }
                            }
                            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("分享与接收", fontSize = 27.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold)
                                Text("收到的课表独立保存，不影响你的课程和提醒。", fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                PocketCard(shape = PocketShapes.Hero) {
                                  Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("分享我的课表", fontWeight = FontWeight.Medium, fontSize = 17.sp)
                                    Text("面对面出示二维码，或传送一份课表文件。", fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Button(shape = PocketShapes.Control, enabled = own != null, onClick = {
                                    frames = timetableShareFrames(encodeSharedTimetable(own!!))
                                    page = 0; screen = "show"
                                }, modifier = Modifier.fillMaxWidth()) { Text("生成我的课表二维码") }
                                    OutlinedButton(shape = PocketShapes.Control, enabled = own != null, onClick = {
                                    val folder = File(cacheDir, "timetable-share").apply { mkdirs() }
                                    val file = File(folder, "NUIST-Pocket-课表.txt")
                                    file.writeText(timetableShareFile(encodeSharedTimetable(own!!)), Charsets.UTF_8)
                                    val uri = FileProvider.getUriForFile(this@ShareTimetableActivity, "$packageName.timetable-share", file)
                                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    startActivity(Intent.createChooser(intent, "分享课表文件"))
                                }, modifier = Modifier.fillMaxWidth()) { Text("通过系统分享课表文件") }
                                    Text(if (own == null) "请先同步或导入自己的课表。" else "二维码和文件在生成后 30 分钟内可导入。", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = tabularTextStyle())
                                  }
                                }
                                PocketCard(shape = PocketShapes.Hero) {
                                  Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("接收别人的课表", fontWeight = FontWeight.Medium, fontSize = 17.sp)
                                    OutlinedButton(shape = PocketShapes.Control, onClick = ::scan, modifier = Modifier.fillMaxWidth()) { Text("扫描对方的课表") }
                                    OutlinedButton(shape = PocketShapes.Control, onClick = {
                                    filePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
                                }, modifier = Modifier.fillMaxWidth()) { Text("导入收到的课表文件") }
                                    if (progress.isNotEmpty()) Text(progress, fontSize = 13.sp, style = tabularTextStyle())
                                  }
                                }
                                if (received != null) {
                                    PocketCard(shape = PocketShapes.Hero) {
                                      Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("已接收", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                        Text(receivedName.ifBlank { "收到的课表" }, fontWeight = FontWeight.Medium, fontSize = 17.sp)
                                        Text(received!!.semester.code, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, style = tabularTextStyle())
                                        Button(shape = PocketShapes.Control, onClick = { screen = "view" }, modifier = Modifier.fillMaxWidth()) { Text("查看课表") }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            TextButton(onClick = { nameDraft = receivedName; editingName = true }) { Text("修改名称或备注") }
                                            TextButton(onClick = { confirmDelete = true }) { Text("删除") }
                                        }
                                        Text("再次接收会替换这份课表并重置备注。", fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                      }
                                    }
                                }
                            }
                        }
                        }
                    }
                    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
                        title = { Text("删除收到的课表？") }, text = { Text("只删除这份收到的课表，不影响你自己的课程。") },
                        confirmButton = { TextButton(onClick = {
                            store.edit().remove("payload").remove("name").apply(); received = null; receivedName = ""; progress = "已删除收到的课表"; confirmDelete = false
                        }) { Text("删除") } },
                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
                    if (editingName) AlertDialog(onDismissRequest = { editingName = false },
                        title = { Text("课表名称或备注") }, text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = nameDraft, onValueChange = { nameDraft = it },
                                    shape = PocketShapes.Control, label = { Text("例如：小明的课表") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Text("仅保存在本机；留空显示“收到的课表”。", fontSize = 12.sp)
                            }
                        }, confirmButton = { TextButton(onClick = {
                            receivedName = nameDraft.trim(); store.edit().putString("name", receivedName).apply(); editingName = false
                        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editingName = false }) { Text("取消") } })
                }
            }
        }
    }
}
