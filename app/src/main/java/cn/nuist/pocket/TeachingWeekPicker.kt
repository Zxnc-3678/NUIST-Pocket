package cn.nuist.pocket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.nuist.pocket.data.Semester
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Equal space beside the text keeps the text itself centered, independently of the chevron. */
@Composable internal fun TeachingWeekHeading(week: Int, subtitle: String?, modifier: Modifier = Modifier,
                                            fontSize: TextUnit = 19.sp, onClick: () -> Unit) {
    Column(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "选择教学周", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(20.dp))
            Text("第${week}周", fontSize = fontSize, fontWeight = FontWeight.Bold)
            Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(20.dp))
        }
        subtitle?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable internal fun TeachingWeekPicker(selectedWeek: Int, maxWeek: Int, semester: Semester, today: LocalDate,
                                           onSelect: (Int) -> Unit, onDismiss: () -> Unit,
                                           extraContent: (@Composable ColumnScope.() -> Unit)? = null) {
    val currentWeek = semester.weekOn(today)
    val dateFormat = DateTimeFormatter.ofPattern("M月d日")
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = (selectedWeek - 1).coerceIn(0, maxWeek - 1))
    val cellWidth = (76 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择教学周") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(semester.code, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("第${selectedWeek}周 · ${semester.dateOf(selectedWeek, 1).format(dateFormat)} — ${semester.dateOf(selectedWeek, 7).format(dateFormat)}")
            if (currentWeek in 1..maxWeek) Text("本周是第${currentWeek}周，点选周次即可跳转。", fontSize = 12.sp)
            else Text("点选周次即可跳转。", fontSize = 12.sp)
            LazyVerticalGrid(columns = GridCells.Adaptive(cellWidth), state = gridState,
                modifier = Modifier.fillMaxWidth().heightIn(max = if (extraContent == null) 280.dp else 180.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((1..maxWeek).toList(), key = { it }) { candidate ->
                    FilterChip(selected = candidate == selectedWeek, onClick = { onSelect(candidate) },
                        label = { Text("第${candidate}周") }, modifier = Modifier.fillMaxWidth())
                }
            }
            extraContent?.invoke(this)
        }
    }, confirmButton = {
        if (currentWeek in 1..maxWeek) TextButton(onClick = { onSelect(currentWeek) }) { Text("返回本周") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
