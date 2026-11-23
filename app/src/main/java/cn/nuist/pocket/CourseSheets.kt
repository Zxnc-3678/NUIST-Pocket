package cn.nuist.pocket

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.nuist.pocket.data.*
import cn.nuist.pocket.data.Period
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

internal class CourseEdits(
    val rules: CourseOverrides,
    val rawNames: Map<String, String>,
    val courses: List<CourseSlot>,
    val lastWeek: Int,
    val update: ((CourseOverrides) -> CourseOverrides) -> Boolean,
)

internal sealed interface CourseSheetPage {
    data class Details(val groups: List<OverviewCourseGroup>, val week: Int, val actionsFirst: Boolean = false) : CourseSheetPage
    data class Appearance(val course: CourseSlot) : CourseSheetPage
    data class Move(val course: CourseSlot, val week: Int, val origin: SessionOrigin) : CourseSheetPage
    data class Custom(val date: LocalDate, val first: Int, val last: Int, val existing: CustomCourse? = null) : CourseSheetPage
    data object Changes : CourseSheetPage
}

internal fun newCourseSheet(date: LocalDate, first: Int, periods: List<Period>): CourseSheetPage.Custom {
    val period = periods.minBy { kotlin.math.abs(it.number - first) }.number
    val twoPeriods = period != 4 && period != 8 && periods.any { it.number == period + 1 }
    return CourseSheetPage.Custom(date, period, if (twoPeriods) period + 1 else period)
}

private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val scheme @Composable get() = MaterialTheme.colorScheme

@Composable
internal fun CourseSheets(
    page: CourseSheetPage?, onPage: (CourseSheetPage?) -> Unit, edits: CourseEdits?,
    semester: Semester, periods: List<Period>, today: LocalDate, viewedWeek: Int,
) {
    val close = { onPage(null) }
    key(page) {
        when (page) {
            null -> Unit
            is CourseSheetPage.Details -> CourseDetails(page, edits, semester, periods, close, onPage)
            is CourseSheetPage.Appearance -> CourseAppearanceEditor(page.course, requireNotNull(edits), close)
            is CourseSheetPage.Move -> CourseTimingEditor(page, null, requireNotNull(edits), semester, periods, today, close)
            is CourseSheetPage.Custom -> CourseTimingEditor(null, page, requireNotNull(edits), semester, periods, today, close)
            CourseSheetPage.Changes -> ChangedSessions(requireNotNull(edits), viewedWeek, close)
        }
    }
}

@Composable
private fun CourseSheetLayout(title: String?, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    PickerSheet(onClose) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold) }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseDetails(
    page: CourseSheetPage.Details, edits: CourseEdits?, semester: Semester, periods: List<Period>,
    onClose: () -> Unit, onPage: (CourseSheetPage) -> Unit,
) {
    CourseSheetLayout(if (page.groups.size > 1) "这个时段的安排" else null, onClose) {
        page.groups.forEachIndexed { index, group ->
            val course = group.course
            val active = group.activePlacements(page.week)
            val custom = edits?.rules?.customCourses?.firstOrNull { it.id == course.id && course.isCustom }
            val occurrences = active.mapNotNull { slot -> slot.originFor(page.week)?.let { it to slot } }
                .distinctBy { it.first }

            if (index > 0) HorizontalDivider(color = scheme.outlineVariant)
            Text(course.displayName, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp)
            if (course.shortName != null) Text(course.name, color = scheme.onSurfaceVariant, fontSize = 13.sp)
            Text("${weekdays[course.weekday!! - 1]} · 第 ${course.firstPeriod}–${course.lastPeriod} 节",
                color = scheme.onSurfaceVariant, style = tabularTextStyle())
            course.rescheduledFrom?.let { origin ->
                Text("由第 ${origin.week} 周${weekdays[origin.weekday - 1]}第 ${origin.firstPeriod}–${origin.lastPeriod} 节调入",
                    color = scheme.primary, fontSize = 13.sp, style = tabularTextStyle())
            }

            val actions: @Composable () -> Unit = {
                if (edits != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (custom != null) {
                        TextButton(onClick = {
                            onPage(CourseSheetPage.Custom(semester.dateOf(active.firstOrNull()?.weeks?.firstOrNull()
                                ?: custom.weeks.min(), custom.weekday), custom.firstPeriod, custom.lastPeriod, custom))
                        }) { Text("编辑") }
                        var confirmDelete by remember { mutableStateOf(false) }
                        TextButton(onClick = { confirmDelete = true }) { Text("删除", color = scheme.error) }
                        if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
                            title = { Text("删除这门手动课程？") },
                            text = { Text("这门课的所有排课周次都会移除。") },
                            confirmButton = { TextButton(onClick = {
                                if (edits.update { it.copy(customCourses = it.customCourses.filterNot { row -> row.id == custom.id }) }) onClose()
                            }) { Text("删除") } },
                            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
                    } else {
                        TextButton(onClick = { onPage(CourseSheetPage.Appearance(course)) }) { Text("改名与配色") }
                        occurrences.forEach { (origin, slot) ->
                            TextButton(onClick = { onPage(CourseSheetPage.Move(slot, page.week, origin)) }) { Text("临时调课") }
                            TextButton(onClick = {
                                val rule = SessionException(origin, SessionAction.SUSPEND)
                                if (edits.update { it.copy(sessions = it.sessions + (origin to rule)) }) onClose()
                            }) { Text("本周停课") }
                        }
                        val sessionKeys = occurrences.map { it.first }.filter { it in edits.rules.sessions }
                        if (course.courseKey in edits.rules.appearances || sessionKeys.isNotEmpty()) TextButton(onClick = {
                            if (edits.update { it.copy(appearances = it.appearances - course.courseKey, sessions = it.sessions - sessionKeys.toSet()) }) onClose()
                        }) { Text("恢复教务默认") }
                    }
                }
            }
            if (page.actionsFirst) actions()
            group.placements.sortedByDescending { page.week in it.weeks }.forEach { placement ->
                val times = placement.times(periods)
                Surface(shape = PocketShapes.Control,
                    color = if (page.week in placement.weeks) scheme.secondaryContainer else scheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val state = if (page.week in placement.weeks) "本周上课" else "非本周安排"
                        Text("$state · ${formatCourseWeeks(placement.weeks)}", fontSize = 12.sp, style = tabularTextStyle())
                        Text("${times.first ?: "待定"}–${times.second ?: "待定"}", fontSize = 13.sp, style = tabularTextStyle())
                        Text(placement.room.ifBlank { "地点待定" }, fontSize = 14.sp)
                        Text(placement.teacher.ifBlank { "教师待定" }, fontSize = 13.sp)
                        if (periods.any { !it.enabled && it.number in placement.firstPeriod!!..placement.lastPeriod!! }) {
                            Text("课程包含未启用节次，请核对学校安排。", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (!page.actionsFirst) actions()
        }
        if (edits != null) Text("小提示：在课表页长按课块也可直接编辑",
            color = scheme.onSurfaceVariant.copy(alpha = 0.76f), fontSize = 11.sp)
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("完成") }
    }
}

@Composable
private fun CourseAppearanceEditor(course: CourseSlot, edits: CourseEdits, onClose: () -> Unit) {
    var shortName by rememberSaveable { mutableStateOf(course.shortName.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(course.customColorHex) }
    CourseSheetLayout("改名与配色", onClose) {
        Text(course.name, color = scheme.onSurfaceVariant, fontSize = 13.sp)
        OutlinedTextField(shortName, { shortName = it }, label = { Text("显示简称") },
            placeholder = { Text("留空显示原名") }, modifier = Modifier.fillMaxWidth(), shape = PocketShapes.Control, singleLine = true)
        CourseColorPicker(color) { color = it }
        CourseFormActions(onClose, enabled = true, saveLabel = "保存") {
            val name = shortName.trim().takeIf { it.isNotEmpty() && it != course.name }
            if (edits.update {
                val key = course.courseKey
                val appearances = if (name == null && color == null) it.appearances - key
                    else it.appearances + (key to CourseAppearanceOverride(name, color))
                it.copy(appearances = appearances)
            }) onClose()
        }
    }
}

private val courseSwatches = listOf(0x6686A3, 0x658D82, 0x8B80AA, 0xAC778A, 0xAB845D, 0x889763, 0xB07064, 0x68839B)

@Composable
private fun CourseColorPicker(active: String?, onSelect: (String?) -> Unit) {
    var enteringHex by rememberSaveable { mutableStateOf(false) }
    Text("课程配色", fontWeight = FontWeight.Medium, fontSize = 14.sp)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(active == null, onClick = { onSelect(null) }, label = { Text("默认自动") }, shape = CircleShape)
        courseSwatches.forEach { value ->
            val hex = ThemeAccent.hex(value)
            Surface(onClick = { onSelect(hex) }, shape = CircleShape, color = Color(value or 0xFF000000.toInt()),
                modifier = Modifier.size(40.dp).semantics { contentDescription = hex; selected = active == hex }) {
                Box(contentAlignment = Alignment.Center) {
                    if (active == hex) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        val custom = active?.let(ThemeAccent::parse)?.takeIf { it !in courseSwatches }
        Surface(onClick = { enteringHex = true }, shape = CircleShape,
            color = custom?.let { Color(it or 0xFF000000.toInt()) } ?: scheme.surfaceContainerLow,
            border = BorderStroke(0.8.dp, scheme.outlineVariant), modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, "自定义 Hex 色号", tint = if (custom == null) scheme.primary else Color.White,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
    if (enteringHex) {
        var hex by rememberSaveable { mutableStateOf(active.orEmpty()) }
        val parsed = ThemeAccent.parse(hex)
        AlertDialog(onDismissRequest = { enteringHex = false }, title = { Text("自定义色号") },
            text = { OutlinedTextField(hex, { hex = it }, label = { Text("Hex 色号") },
                placeholder = { Text("#6686A3") }, shape = PocketShapes.Control, singleLine = true) },
            confirmButton = { TextButton(enabled = parsed != null, onClick = {
                onSelect(ThemeAccent.hex(requireNotNull(parsed)))
                enteringHex = false
            }) { Text("使用颜色") } },
            dismissButton = { TextButton(onClick = { enteringHex = false }) { Text("取消") } })
    }
}

private enum class WeekPattern(val label: String) { EVERY("每周"), ODD("单周"), EVEN("双周") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseTimingEditor(
    move: CourseSheetPage.Move?, customPage: CourseSheetPage.Custom?, edits: CourseEdits, semester: Semester,
    periods: List<Period>, today: LocalDate, onClose: () -> Unit,
) {
    val course = move?.course
    val custom = customPage?.existing
    val customId = if (customPage != null) rememberSaveable { custom?.id ?: UUID.randomUUID().toString() } else null
    val startingDate = customPage?.date ?: semester.dateOf(move!!.week, course!!.weekday!!)
    val available = periods.map { it.number }
    val intendedFirst = course?.firstPeriod ?: customPage!!.first
    val intendedLast = course?.lastPeriod ?: customPage!!.last
    val first = available.minBy { kotlin.math.abs(it - intendedFirst) }
    val last = available.minBy { kotlin.math.abs(it - intendedLast) }
    val lessonLength = intendedLast - intendedFirst
    val maxWeek = edits.lastWeek
    var dateText by rememberSaveable { mutableStateOf(startingDate.toString()) }
    val date = LocalDate.parse(dateText)
    val week = semester.weekOn(date)
    val dateValid = week in 1..maxWeek
    var name by rememberSaveable { mutableStateOf(custom?.name.orEmpty()) }
    var room by rememberSaveable {
        mutableStateOf(custom?.room ?: move?.let { edits.rules.sessions[it.origin]?.targetRoom }.orEmpty())
    }
    var teacher by rememberSaveable { mutableStateOf(custom?.teacher.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(custom?.customColorHex) }
    var repeat by rememberSaveable { mutableStateOf(custom != null && custom.weeks.size > 1) }
    var weekFrom by rememberSaveable { mutableIntStateOf(custom?.weeks?.min() ?: semester.weekOn(startingDate).coerceIn(1, maxWeek)) }
    var weekThrough by rememberSaveable { mutableIntStateOf(custom?.weeks?.max() ?: maxWeek) }
    val previousWeeks = custom?.weeks.orEmpty()
    val originalPattern = when {
        previousWeeks.size < 2 -> WeekPattern.EVERY
        previousWeeks.all { it % 2 == 1 } -> WeekPattern.ODD
        previousWeeks.all { it % 2 == 0 } -> WeekPattern.EVEN
        else -> WeekPattern.EVERY
    }
    var pattern by rememberSaveable { mutableStateOf(originalPattern) }
    val patternWeeks = (weekFrom..weekThrough).filter { pattern == WeekPattern.EVERY ||
        it % 2 == if (pattern == WeekPattern.ODD) 1 else 0 }.toSet()
    val repeatWeeks = if (previousWeeks.size > 1 && weekFrom == previousWeeks.min() && weekThrough == previousWeeks.max() &&
        pattern == originalPattern) previousWeeks else patternWeeks
    var firstPeriod by rememberSaveable { mutableIntStateOf(first) }
    var lastPeriod by rememberSaveable { mutableIntStateOf(last) }
    val preciseStart = custom?.start ?: course?.takeIf { it.isRescheduled }?.start
    val preciseEnd = custom?.end ?: course?.takeIf { it.isRescheduled }?.end
    var exact by rememberSaveable { mutableStateOf(preciseStart != null && preciseEnd != null) }
    var startText by rememberSaveable { mutableStateOf(preciseStart?.toString()) }
    var endText by rememberSaveable { mutableStateOf(preciseEnd?.toString()) }
    val periodStart = periods.first { it.number == firstPeriod }.start
    val periodEnd = periods.first { it.number == lastPeriod }.end
    val start = if (exact) startText?.let(LocalTime::parse) ?: periodStart else periodStart
    val end = if (exact) endText?.let(LocalTime::parse) ?: periodEnd else periodEnd
    val validTime = !exact || end > start
    val occupied = if (move == null && exact && validTime) periodsForTimes(start, end, periods) else firstPeriod..lastPeriod
    val weekday = date.dayOfWeek.value
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableIntStateOf(0) }

    val conflicts = if (move != null && dateValid && validTime) coursesOnDate(edits.courses, semester, date).filter { slot ->
        val (otherStart, otherEnd) = slot.times(periods)
        val intersects = if (otherStart != null && otherEnd != null) start < otherEnd && otherStart < end
            else slot.firstPeriod!! <= occupied.last && slot.lastPeriod!! >= occupied.first
        slot.originFor(week) != move.origin && intersects
    }.map { it.displayName }.distinct() else emptyList()

    CourseSheetLayout(if (move != null) "临时调课" else if (custom == null) "添加课程" else "编辑手动课程", onClose) {
        if (move != null) {
            Text(move.course.displayName, fontWeight = FontWeight.Medium)
        } else {
            OutlinedTextField(name, { name = it }, label = { Text("课程名") }, shape = PocketShapes.Control,
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!repeat, onClick = { repeat = false }, label = { Text("单次") })
                FilterChip(repeat, onClick = { repeat = true }, label = { Text("多周重复") })
            }
        }

        if (move == null && repeat) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                weekdays.forEachIndexed { index, label ->
                    FilterChip(weekday == index + 1, onClick = { dateText = semester.dateOf(weekFrom, index + 1).toString() },
                        label = { Text(label) }, shape = CircleShape)
                }
            }
            Surface(shape = PocketShapes.Control, color = scheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth()) {
                    WeekMenu("起始周", weekFrom, 1..maxWeek, Modifier.weight(1f)) {
                        weekFrom = it
                        weekThrough = maxOf(it, weekThrough)
                    }
                    WeekMenu("结束周", weekThrough, weekFrom..maxWeek, Modifier.weight(1f)) { weekThrough = it }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WeekPattern.entries.forEach { option ->
                    FilterChip(pattern == option, onClick = { pattern = option }, label = { Text(option.label) })
                }
            }
            if (repeatWeeks.isEmpty()) Text("所选周次没有符合条件的排课。", color = scheme.error, fontSize = 12.sp)
        } else {
            OutlinedButton(onClick = { pickingDate = true }, shape = PocketShapes.Control, modifier = Modifier.fillMaxWidth()) {
                Text(if (dateValid) "$date · 第 ${week} 周 · ${weekdays[weekday - 1]}" else date.toString(), style = tabularTextStyle())
            }
            if (!dateValid) Text("请选择本学期第 1–${maxWeek} 周内的日期。", color = scheme.error, fontSize = 12.sp)
        }

        if (move != null || !exact) Surface(shape = PocketShapes.Control, color = scheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth()) {
                PeriodMenu("开始节次", firstPeriod, available, Modifier.weight(1f)) { selected ->
                    firstPeriod = selected
                    lastPeriod = if (move != null) available.last { it <= selected + lessonLength } else maxOf(lastPeriod, selected)
                }
                PeriodMenu("结束节次", lastPeriod, available.filter { it >= firstPeriod }, Modifier.weight(1f)) { lastPeriod = it }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自定义精确起止时间", modifier = Modifier.weight(1f), fontSize = 14.sp)
            Switch(exact, onCheckedChange = { exact = it })
        }
        if (exact) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickingTime = 1 }, shape = PocketShapes.Control, modifier = Modifier.weight(1f)) {
                    Text("开始 $start", style = tabularTextStyle())
                }
                OutlinedButton(onClick = { pickingTime = 2 }, shape = PocketShapes.Control, modifier = Modifier.weight(1f)) {
                    Text("结束 $end", style = tabularTextStyle())
                }
            }
            if (!validTime) Text("结束时间须晚于开始时间。", color = scheme.error, fontSize = 12.sp)
            if (move == null && validTime) Text("网格占位 · 第 ${occupied.first}–${occupied.last} 节", fontSize = 12.sp,
                color = scheme.onSurfaceVariant, style = tabularTextStyle())
        }

        OutlinedTextField(room, { room = it }, label = { Text(if (move != null) "新教室（选填）" else "地点（选填）") },
            placeholder = if (move != null) ({ Text("留空沿用原教室") }) else null, shape = PocketShapes.Control,
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (move == null) {
            OutlinedTextField(teacher, { teacher = it }, label = { Text("教师（选填）") }, shape = PocketShapes.Control,
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            CourseColorPicker(color) { color = it }
        }
        if (conflicts.isNotEmpty()) Text("目标时段已有 ${conflicts.joinToString("、")}，仍可保存。",
            color = scheme.primary, fontSize = 13.sp)
        val validDate = if (move == null && repeat) repeatWeeks.isNotEmpty() else dateValid
        CourseFormActions(onClose, validDate && validTime && (move != null || name.isNotBlank()),
            if (conflicts.isEmpty()) "保存" else "仍然保存") {
            val exactStart = start.takeIf { exact }
            val exactEnd = end.takeIf { exact }
            if (move != null) {
                val rule = SessionException(move.origin, SessionAction.RESCHEDULE, week, weekday, firstPeriod, lastPeriod,
                    exactStart, exactEnd, room.trim().ifEmpty { null })
                if (edits.update { it.copy(sessions = it.sessions + (move.origin to rule)) }) onClose()
            } else {
                val value = CustomCourse(requireNotNull(customId), name.trim(), room.trim(), teacher.trim(),
                    if (repeat) repeatWeeks else setOf(week), weekday, occupied.first, occupied.last, exactStart, exactEnd, color)
                if (edits.update { it.copy(customCourses = it.customCourses.filterNot { row -> row.id == value.id } + value) }) onClose()
            }
        }
    }
    if (pickingDate) PocketDatePicker(date, today, onDismiss = { pickingDate = false }) {
        dateText = it.toString()
        pickingDate = false
    }
    if (pickingTime != 0) TimeWheelSheet(if (pickingTime == 1) start else end, onDismiss = { pickingTime = 0 }) {
        if (pickingTime == 1) startText = it.toString() else endText = it.toString()
        pickingTime = 0
    }
}

@Composable
private fun WeekMenu(title: String, value: Int, weeks: IntRange, modifier: Modifier, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$title · 第 ${value} 周", style = tabularTextStyle())
        }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 300.dp)) {
            weeks.forEach { week ->
                DropdownMenuItem(text = { Text("第 ${week} 周", style = tabularTextStyle()) }, onClick = {
                    onSelect(week)
                    open = false
                })
            }
        }
    }
}

@Composable
private fun CourseFormActions(onClose: () -> Unit, enabled: Boolean, saveLabel: String, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = onClose) { Text("取消") }
        Button(onClick = onSave, enabled = enabled, shape = PocketShapes.Control) { Text(saveLabel) }
    }
}

@Composable
private fun ChangedSessions(edits: CourseEdits, viewedWeek: Int, onClose: () -> Unit) {
    CourseSheetLayout("临时调整", onClose) {
        val changes = edits.rules.sessions.values.sortedWith(compareBy<SessionException> { it.origin.week != viewedWeek }
            .thenBy { it.origin.week }.thenBy { it.origin.weekday }.thenBy { it.origin.firstPeriod })
        if (changes.isEmpty()) Text("暂无停课或调课记录。", color = scheme.onSurfaceVariant)
        changes.forEach { change ->
            val origin = change.origin
            PocketCard {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(edits.rawNames[origin.courseKey] ?: origin.courseKey.substringAfter(':'), fontWeight = FontWeight.Medium)
                    Text("第 ${origin.week} 周 · ${weekdays[origin.weekday - 1]} · ${origin.firstPeriod}–${origin.lastPeriod} 节",
                        fontSize = 13.sp, style = tabularTextStyle())
                    Text(if (change.action == SessionAction.SUSPEND) "已停课" else
                        "调至第 ${change.targetWeek} 周 · ${weekdays[change.targetWeekday - 1]} · ${change.targetFirstPeriod}–${change.targetLastPeriod} 节",
                        color = scheme.onSurfaceVariant, fontSize = 12.sp, style = tabularTextStyle())
                    TextButton(onClick = { edits.update { it.copy(sessions = it.sessions - origin) } },
                        modifier = Modifier.align(Alignment.End)) { Text("恢复原排课") }
                }
            }
        }
    }
}
