package cn.nuist.pocket

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import cn.nuist.pocket.data.CourseSlot
import cn.nuist.pocket.data.TodoItem
import java.time.LocalTime

internal sealed interface DayTimelineEntry {
    val time: LocalTime?
    val key: String

    data class Course(val slot: CourseSlot, val index: Int, override val time: LocalTime?) : DayTimelineEntry {
        override val key get() = "course:${slot.id}:$index"
    }

    data class Task(val item: TodoItem) : DayTimelineEntry {
        override val time get() = item.time
        override val key get() = "todo:${item.id}"
    }
}

@Composable
internal fun AllDayTodos(
    items: List<TodoItem>, busy: Boolean, loadError: String?, loaded: Boolean,
    onToggle: (TodoItem) -> Unit, onEdit: (TodoItem) -> Unit, onAdd: () -> Unit, onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PocketCard(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("全天待办", Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                TextButton(onClick = onAdd, enabled = loaded && !busy) { Text("添加") }
                TextButton(onClick = onOpen) { Text("全部待办") }
            }
            if (loadError != null) Text(loadError, Modifier.padding(start = 8.dp, bottom = 9.dp),
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            items.forEach { item -> key(item.id) {
                val press = rememberPocketPress()
                Row(press.modifier(Modifier.fillMaxWidth()), verticalAlignment = Alignment.CenterVertically) {
                    TodoCheckCircle(item, busy, onToggle, press)
                    TodoTimelineText(item, Modifier.weight(1f), busy, onEdit, press)
                }
            } }
        }
    }
}

@Composable
internal fun TimedTodoCard(item: TodoItem, busy: Boolean, onToggle: (TodoItem) -> Unit, onEdit: (TodoItem) -> Unit,
                           modifier: Modifier = Modifier) {
    val press = rememberPocketPress()
    PocketCard(modifier = press.modifier(modifier), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(66.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(item.time.toString(), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = tabularTextStyle())
                TodoCheckCircle(item, busy, onToggle, press)
            }
            TodoTimelineText(item, Modifier.weight(1f), busy, onEdit, press)
        }
    }
}

@Composable
internal fun TodoCheckCircle(item: TodoItem, busy: Boolean, onToggle: (TodoItem) -> Unit, press: PocketPress? = null) {
    val colors = MaterialTheme.colorScheme
    val scale = remember(item.id) { Animatable(1f) }
    var previous by remember(item.id) { mutableStateOf(item.completed) }
    LaunchedEffect(item.completed) {
        if (previous != item.completed) {
            previous = item.completed
            scale.snapTo(0.84f)
            scale.animateTo(1f, spring(dampingRatio = 0.46f, stiffness = 650f))
        }
    }
    val fill = animateColorAsState(if (item.completed) colors.primary else Color.Transparent, tween(160), label = "待办选中底色")
    val outline = animateColorAsState(if (item.completed) colors.primary else colors.outline, tween(160), label = "待办描边")
    val check = animateFloatAsState(if (item.completed) 1f else 0f, tween(135), label = "待办勾选")
    Box(Modifier.size(48.dp).semantics { contentDescription = item.title }
        .toggleable(item.completed, interactionSource = press?.interaction, indication = LocalIndication.current,
            enabled = !busy, role = Role.Checkbox, onValueChange = { onToggle(item) }),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(21.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .background(fill.value, CircleShape).border(1.dp, outline.value, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Check, null, Modifier.size(15.dp).graphicsLayer { alpha = check.value }, tint = colors.onPrimary)
        }
    }
}

@Composable
private fun TodoTimelineText(item: TodoItem, modifier: Modifier, busy: Boolean, onEdit: (TodoItem) -> Unit, press: PocketPress) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.clickable(interactionSource = press.interaction, indication = LocalIndication.current,
        enabled = !busy) { onEdit(item) }.heightIn(min = 42.dp).padding(vertical = 5.dp),
        verticalArrangement = Arrangement.Center) {
        TodoAnimatedTitle(item, fontSize = 14.sp, lineHeight = 19.sp)
        if (item.note.isNotBlank()) Text(item.note, fontSize = 12.sp, lineHeight = 16.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
    }
}

@Composable
internal fun TodoAnimatedTitle(item: TodoItem, fontSize: TextUnit = TextUnit.Unspecified, lineHeight: TextUnit = TextUnit.Unspecified) {
    val colors = MaterialTheme.colorScheme
    val color = animateColorAsState(if (item.completed) colors.onSurfaceVariant else colors.onSurface,
        tween(180), label = "待办文字")
    val strike = animateFloatAsState(if (item.completed) 1f else 0f, tween(180), label = "待办删除线")
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(item.title, modifier = Modifier.drawWithContent {
        drawContent()
        val fraction = strike.value
        val text = layout
        if (fraction > 0f && text != null) {
            for (line in 0 until text.lineCount) {
                val left = text.getLineLeft(line)
                val right = text.getLineRight(line)
                val top = text.getLineTop(line)
                val y = top + (text.getLineBottom(line) - top) * 0.54f
                drawLine(color.value, Offset(left, y), Offset(left + (right - left) * fraction, y), 1.dp.toPx())
            }
        }
    }, color = color.value, onTextLayout = { layout = it }, fontSize = fontSize, lineHeight = lineHeight, fontWeight = FontWeight.Medium)
}
