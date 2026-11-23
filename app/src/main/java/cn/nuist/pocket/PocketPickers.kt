package cn.nuist.pocket

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = PocketShapes.Sheet,
    ) {
        CompositionLocalProvider(LocalPocketImmersive provides false) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp), content = content)
        }
    }
}

private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PocketDatePicker(
    initial: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    mondaysOnly: Boolean = false,
    onConfirm: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.utcMillis())
    val shortcuts = listOf(
        "今天" to today,
        "明天" to today.plusDays(1),
        "后天" to today.plusDays(2),
        "下周" to today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
    )
    PickerSheet(onDismiss) {
        Text(if (mondaysOnly) "选择第一教学周周一" else "选择日期",
            Modifier.padding(horizontal = 24.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            shortcuts.filter { !mondaysOnly || it.second.dayOfWeek == DayOfWeek.MONDAY }.forEach { (label, date) ->
                val millis = date.utcMillis()
                PocketChoiceChip(selected = state.selectedDateMillis == millis, onClick = {
                    state.selectedDateMillis = millis
                    state.displayedMonthMillis = date.withDayOfMonth(1).utcMillis()
                }, label = { Text(label) })
            }
        }
        DatePicker(state = state, modifier = Modifier.fillMaxWidth(), title = null, headline = null, showModeToggle = false)
        PickerActions(onDismiss, enabled = state.selectedDateMillis != null) {
            state.selectedDateMillis?.let {
                onConfirm(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate())
            }
        }
    }
}

@Composable
internal fun TimeWheelSheet(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val hourState = rememberLazyListState(wheelStart(24, initial.hour))
    val minuteState = rememberLazyListState(wheelStart(60, initial.minute))
    val hourIndex = centeredWheelIndex(hourState)
    val minuteIndex = centeredWheelIndex(minuteState)
    val colors = MaterialTheme.colorScheme
    val rowHeight = with(LocalDensity.current) { maxOf(44.dp, 36.sp.toDp()) }
    val edge = colors.outlineVariant.copy(alpha = 0.65f)

    PickerSheet(onDismiss) {
        Text("选择时间", Modifier.padding(horizontal = 24.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(rowHeight * 5)) {
            Box(Modifier.align(Alignment.Center).fillMaxWidth().height(rowHeight)
                .clip(PocketShapes.Control)
                .background(colors.primary.copy(alpha = 0.08f))
                .drawBehind {
                    val stroke = 0.5.dp.toPx()
                    val line = Brush.horizontalGradient(0f to Color.Transparent, 0.5f to edge, 1f to Color.Transparent)
                    drawLine(line, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
                    drawLine(line, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
                })
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                TimeWheel("小时", 24, hourState, hourIndex, rowHeight, Modifier.weight(1f))
                Text(":", color = colors.onSurface, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, style = tabularTextStyle())
                TimeWheel("分钟", 60, minuteState, minuteIndex, rowHeight, Modifier.weight(1f))
            }
        }
        PickerActions(onDismiss) { onConfirm(LocalTime.of(hourIndex % 24, minuteIndex % 60)) }
    }
}

@Composable
private fun PickerActions(onDismiss: () -> Unit, enabled: Boolean = true, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = onDismiss) { Text("取消") }
        Button(shape = PocketShapes.Control, onClick = onConfirm, enabled = enabled) { Text("确定") }
    }
}

// Start near the middle of the repeated range so both directions wrap at midnight.
private fun wheelStart(count: Int, value: Int): Int = (Int.MAX_VALUE / 2 / count) * count + value

@Composable
private fun centeredWheelIndex(state: LazyListState): Int {
    val index by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: state.firstVisibleItemIndex
        }
    }
    return index
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimeWheel(label: String, count: Int, state: LazyListState, index: Int, rowHeight: Dp, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentIndex by rememberUpdatedState(index)
    LaunchedEffect(state, haptics) {
        snapshotFlow { currentIndex }.drop(1).collect {
            if (state.isScrollInProgress) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    LazyColumn(
        modifier.fillMaxHeight().semantics { contentDescription = label },
        state = state,
        contentPadding = PaddingValues(vertical = rowHeight * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        flingBehavior = rememberSnapFlingBehavior(state),
    ) {
        items(Int.MAX_VALUE) { itemIndex ->
            val active = itemIndex == index
            val size by animateFloatAsState(if (active) 1f else 0.75f, label = "滚轮字号")
            val opacity by animateFloatAsState(if (active) 1f else 0.35f, label = "滚轮淡化")
            Box(
                Modifier.fillMaxWidth().height(rowHeight).semantics { selected = active }
                    .clickable { scope.launch { state.animateScrollToItem(itemIndex) } },
                contentAlignment = Alignment.Center,
            ) {
                Text((itemIndex % count).toString().padStart(2, '0'),
                    Modifier.graphicsLayer { scaleX = size; scaleY = size; alpha = opacity },
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 24.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, style = tabularTextStyle())
            }
        }
    }
}
