package cn.nuist.pocket

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.nuist.pocket.data.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

internal data class TodoActions(
    val save: (TodoItem, (Boolean) -> Unit) -> Unit,
    val delete: (String, (Boolean) -> Unit) -> Unit,
    val toggle: (TodoItem) -> Unit,
)

@Composable internal fun TodoPreview(items: List<TodoItem>?, date: LocalDate, loadError: String? = null, onOpen: () -> Unit) {
    val day = remember(items, date) { items.orEmpty().filter { it.date == date }.sortedWith(todoOrder) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (day.isEmpty()) "待办" else "待办 · ${day.size}项", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = onOpen) { Text(if (items == null) "查看" else if (day.isEmpty()) "添加" else "全部") }
            }
            if (items == null) Text(if (loadError == null) "正在读取待办…" else "暂时无法读取待办，点“查看”重新读取。原有文件已保留。",
                color = if (loadError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, fontSize = 12.sp)
            day.take(2).forEach { item ->
                Text("${if (item.completed) "✓" else "○"}  ${item.time?.let { "$it  " }.orEmpty()}${item.title}",
                    Modifier.fillMaxWidth().clickable(onClick = onOpen), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (item.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (item.completed) TextDecoration.LineThrough else null, fontSize = 14.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TodoScreen(items: List<TodoItem>?, initialDate: LocalDate, today: LocalDate, startWithPending: Boolean, busy: Boolean,
                                    error: String?, onRetry: () -> Unit, actions: TodoActions, onClose: () -> Unit) {
    val context = LocalContext.current
    var dateText by rememberSaveable { mutableStateOf(initialDate.toString()) }
    var previousToday by rememberSaveable { mutableStateOf(today.toString()) }
    LaunchedEffect(today) {
        dateText = followToday(LocalDate.parse(dateText), LocalDate.parse(previousToday), today).toString()
        previousToday = today.toString()
    }
    val date = LocalDate.parse(dateText)
    var allPending by rememberSaveable { mutableStateOf(startWithPending) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val day = remember(items, date) { items.orEmpty().filter { it.date == date }.sortedWith(todoOrder) }
    val completedCount = remember(day) { day.count { it.completed } }
    val visible = remember(items, day, allPending, showCompleted) {
        if (allPending) items.orEmpty().filterNot { it.completed }.sortedWith(todoOrder)
        else if (showCompleted) day else day.filterNot { it.completed }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "关闭待办") }
                    Text("个人待办", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 21.sp)
                    TextButton(enabled = items != null && !busy, onClick = { editingId = null; editing = true }) { Text("添加") }
                }
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!allPending, onClick = { allPending = false }, label = { Text("按日期") })
                    FilterChip(allPending, onClick = { allPending = true }, label = { Text("全部未完成") })
                }
                if (!allPending) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { DatePickerDialog(context, { _, y, m, d -> dateText = LocalDate.of(y, m + 1, d).toString() }, date.year, date.monthValue - 1, date.dayOfMonth).show() }) { Text(dateText) }
                    TextButton(enabled = date != today, onClick = { dateText = today.toString() }) { Text("今天") }
                    TextButton(onClick = { showCompleted = !showCompleted }) { Text(if (showCompleted) "隐藏已完成" else "显示已完成") }
                }
                if (items != null) Text(if (allPending) "未完成 ${visible.size}项" else "未完成 ${day.size - completedCount}项 · 已完成 ${completedCount}项",
                    Modifier.padding(horizontal = 18.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                error?.let { Text(it, Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                if (items == null) {
                    TextButton(enabled = !busy, onClick = onRetry, modifier = Modifier.padding(horizontal = 18.dp)) { Text(if (busy) "正在读取…" else "重新读取待办") }
                } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (visible.isEmpty()) item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(when {
                                allPending -> "暂时没有未完成待办，给自己留一点空闲。"
                                completedCount > 0 -> "这一天的待办已全部完成。"
                                else -> "这一天还没有待办，点右上角添加。"
                            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!allPending && completedCount > 0) TextButton(onClick = { showCompleted = true }) { Text("查看已完成事项") }
                        }
                    }
                    items(visible, key = { it.id }) { item ->
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(item.completed, enabled = !busy, onCheckedChange = { actions.toggle(item) }, modifier = Modifier.semantics { contentDescription = item.title })
                                Column(Modifier.weight(1f).clickable(enabled = !busy) { editingId = item.id; editing = true }.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(item.title, fontWeight = FontWeight.Medium, textDecoration = if (item.completed) TextDecoration.LineThrough else null)
                                    Text("${item.date}${item.time?.let { " · $it" }.orEmpty()}${if (!item.completed && item.date < today) " · 已逾期" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                    if (item.note.isNotBlank()) Text(item.note, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (editing) TodoEditor(items.orEmpty().find { it.id == editingId }, date, busy, error, actions) { editing = false }
    }
}

@Composable private fun TodoEditor(item: TodoItem?, initialDate: LocalDate, busy: Boolean, error: String?, actions: TodoActions, onClose: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val id = rememberSaveable { item?.id ?: UUID.randomUUID().toString() }
    var title by rememberSaveable { mutableStateOf(item?.title.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(item?.note.orEmpty()) }
    var dateText by rememberSaveable { mutableStateOf((item?.date ?: initialDate).toString()) }
    var timeText by rememberSaveable { mutableStateOf(item?.time?.toString()) }
    var selectingTime by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (item == null) "添加待办" else "编辑待办", Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    TextButton(enabled = !busy, onClick = onClose) { Text("取消") }
                }
                OutlinedTextField(title, { if (it.length <= 120) title = it }, enabled = !busy, label = { Text("要做什么") }, supportingText = { Text("${title.length}/120") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                OutlinedButton(enabled = !busy, onClick = { val d = LocalDate.parse(dateText); DatePickerDialog(context, { _, y, m, day -> dateText = LocalDate.of(y, m + 1, day).toString() }, d.year, d.monthValue - 1, d.dayOfMonth).show() }, modifier = Modifier.fillMaxWidth()) { Text("日期：$dateText") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !busy, onClick = { focus.clearFocus(); keyboard?.hide(); selectingTime = true }, modifier = Modifier.weight(1f)) { Text(timeText?.let { "时间：$it" } ?: "全天 · 可选具体时间") }
                    if (timeText != null) TextButton(enabled = !busy, onClick = { timeText = null }) { Text("清除时间") }
                }
                OutlinedTextField(note, { if (it.length <= 500) note = it }, enabled = !busy, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                Button(enabled = title.isNotBlank() && !busy, onClick = {
                    actions.save(TodoItem(id, title.trim(), LocalDate.parse(dateText), timeText?.let(LocalTime::parse), note.trim(), item?.completed ?: false)) { if (it) onClose() }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在保存…" else "保存待办") }
                if (item != null) TextButton(enabled = !busy, onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("删除这条待办", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (selectingTime) TimeWheelDialog(timeText?.let(LocalTime::parse) ?: LocalTime.of(18, 0), onDismiss = { selectingTime = false },
            onConfirm = { timeText = it.toString(); selectingTime = false })
        if (confirmDelete) AlertDialog(onDismissRequest = { if (!busy) confirmDelete = false }, title = { Text("删除这条待办？") }, text = { Text("删除后无法恢复。") },
            confirmButton = { TextButton(enabled = !busy, onClick = { actions.delete(id) { if (it) { confirmDelete = false; onClose() } } }) { Text("删除") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmDelete = false }) { Text("取消") } })
    }
}
