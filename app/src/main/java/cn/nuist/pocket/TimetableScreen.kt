package cn.nuist.pocket

import android.graphics.Bitmap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import cn.nuist.pocket.data.*
import cn.nuist.pocket.data.Period
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val timetableBlue: Color @Composable get() = MaterialTheme.colorScheme.primary
private val timetableMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val courseColors = listOf(0xFF477CAB, 0xFFB27D32, 0xFF526AA6, 0xFFAD667E, 0xFF428874, 0xFF8764AB)
private val days = listOf("一", "二", "三", "四", "五", "六", "日")
private val timetableDateFormat = DateTimeFormatter.ofPattern("M/d")

@Composable
internal fun TimetableScreen(courses: List<CourseSlot>, periods: List<Period>, semester: Semester, today: LocalDate, demo: Boolean,
                             preferences: AppPreferences, onSettings: () -> Unit, settingsEnabled: Boolean,
                             wallpaper: Bitmap? = null, wallpaperFade: Float = 0.45f, suspendedDates: Set<LocalDate> = emptySet(),
                             settingsOpensDisplay: Boolean = false) {
    val termCourses = remember(courses, semester) { courses.filter { it.semester == semester.code } }
    val maxWeek = remember(termCourses) { maxOf(20, termCourses.flatMap { it.weeks }.maxOrNull() ?: 20) }
    val pager = key(semester.code, semester.firstMonday) {
        rememberPagerState(initialPage = semester.weekOn(today).coerceIn(1, maxWeek) - 1, pageCount = { maxWeek })
    }
    val week by remember(pager, maxWeek) { derivedStateOf { (pager.currentPage + 1).coerceIn(1, maxWeek) } }
    // Announce the proposed snap destination immediately, including a fling's remaining motion.
    // Waiting for settledPage leaves the heading one swipe behind during repeated quick swipes.
    val headingWeek by remember(pager, maxWeek) { derivedStateOf { (pager.targetPage + 1).coerceIn(1, maxWeek) } }
    val scope = rememberCoroutineScope()
    var previousToday by rememberSaveable(semester.code, semester.firstMonday.toString()) { mutableStateOf(today.toString()) }
    LaunchedEffect(today, pager) {
        val currentWeek = semester.weekOn(today).coerceIn(1, maxWeek)
        if (!pager.isScrollInProgress && week != currentWeek &&
            week == semester.weekOn(LocalDate.parse(previousToday)).coerceIn(1, maxWeek)) {
            pager.animateScrollToPage(currentWeek - 1, animationSpec = tween(260))
        }
        previousToday = today.toString()
    }
    var showInactive by rememberSaveable(settingsOpensDisplay) {
        mutableStateOf(if (settingsOpensDisplay) preferences.sharedTimetableShowInactive else preferences.timetableShowInactive)
    }
    var cardOpacity by rememberSaveable { mutableFloatStateOf(preferences.timetableCardOpacity.toFloat()) }
    var choosingWeek by rememberSaveable { mutableStateOf(false) }
    var choosingDisplay by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<TimetableBlock?>(null) }
    val dark = isSystemInDarkTheme()
    val gridLine = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
    val lastPeriod = maxOf(11, termCourses.mapNotNull { it.lastPeriod }.maxOrNull() ?: 11).coerceAtMost(12)
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val periodHeight = measuredTimetablePeriodHeight(termCourses, ((maxWidth - 38.dp) / 7).coerceAtLeast(1.dp))
      Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PocketPageHeader({ if (settingsOpensDisplay) choosingDisplay = true else onSettings() }, settingsEnabled, sideWidth = 48.dp, leading = {
            if (demo) Text("演示", Modifier.clickable(onClick = { choosingWeek = true }).padding(horizontal = 6.dp, vertical = 8.dp),
                fontSize = 11.sp, maxLines = 1, softWrap = false, color = timetableMuted)
            else IconButton(onClick = { choosingWeek = true }) { Icon(Icons.Outlined.DateRange, if (settingsOpensDisplay) "选择周次" else "周次与课表显示") }
        }) {
            TeachingWeekHeading(headingWeek, subtitle = null, modifier = Modifier.fillMaxWidth(), fontSize = 20.sp, onClick = { choosingWeek = true })
        }
        TimetableDates(pager, semester, today)
        Box(Modifier.fillMaxWidth().weight(1f)) {
          if (wallpaper != null) {
              Image(wallpaper.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
              Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.background.copy(alpha = wallpaperFade.coerceIn(0.2f, 0.9f))))
          }
          Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.width(38.dp).height((timetableTop(lastPeriod, periodHeight) + periodHeight).dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))) {
                (1..lastPeriod).forEach { number ->
                    Column(Modifier.offset(y = timetableTop(number, periodHeight).dp).fillMaxWidth().height(periodHeight.dp).border(0.3.dp, gridLine),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("$number", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        periods.find { it.number == number }?.let { period ->
                            Text(period.start.toString(), color = timetableMuted, fontSize = 8.sp)
                            Text(period.end.toString(), color = timetableMuted, fontSize = 8.sp)
                        }
                    }
                }
            }
            // One vertical scroller keeps the period position identical on both visible weeks.
            HorizontalPager(state = pager,
                modifier = Modifier.weight(1f).height((timetableTop(lastPeriod, periodHeight) + periodHeight).dp),
                beyondViewportPageCount = 1,
                reverseLayout = LocalLayoutDirection.current == LayoutDirection.Rtl,
                verticalAlignment = Alignment.Top,
                key = { it }) { page ->
              val pageWeek = page + 1
              val blocks = remember(termCourses, pageWeek, showInactive) {
                  (1..7).associateWith { timetableBlocks(termCourses, it, pageWeek, showInactive) }
              }
              Row(Modifier.fillMaxSize()) {
              (1..7).forEach { day ->
                Box(Modifier.weight(1f).height((timetableTop(lastPeriod, periodHeight) + periodHeight).dp)) {
                    (1..lastPeriod).forEach { number -> Box(Modifier.offset(y = timetableTop(number, periodHeight).dp).fillMaxWidth().height(periodHeight.dp).border(0.3.dp, gridLine)) }
                    listOf(4 to "午休", 8 to "晚休").forEach { (after, label) ->
                        Box(Modifier.offset(y = (timetableTop(after, periodHeight) + periodHeight).dp).fillMaxWidth().height(22.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
                            if (day == 4) Text(label, color = timetableMuted, fontSize = 9.sp)
                        }
                    }
                    val suspended = semester.dateOf(pageWeek, day) in suspendedDates
                    if (suspended) Text("停课", Modifier.align(Alignment.TopCenter).padding(top = 8.dp), color = timetableMuted, fontSize = 11.sp)
                    (if (suspended) emptyList() else blocks.getValue(day)).forEach { block ->
                        val group = block.groups.first()
                        val active = group.activePlacements(pageWeek)
                        val accent = if (active.isEmpty()) timetableMuted.copy(alpha = 0.5f) else
                            lerp(Color(courseColors[Math.floorMod((group.course.code + group.course.name).hashCode(), courseColors.size)]), Color.White, if (dark) 0.24f else 0f)
                        val color = if (active.isEmpty()) {
                            (if (dark) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant).copy(alpha = 0.94f)
                        } else {
                            (if (dark) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                                .copy(alpha = if (wallpaper == null) 1f else cardOpacity / 100f)
                        }
                        Surface(onClick = { selected = block }, color = color, shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.offset(y = timetableTop(block.first, periodHeight).dp).fillMaxWidth().height(timetableHeight(block.first, block.last, periodHeight).dp).padding(1.dp)
                                .semantics(mergeDescendants = true) { contentDescription = group.course.name + (if (active.isEmpty()) "，非本周课程" else "") + (if (block.groups.size > 1) "，共${block.groups.size}项安排" else "") }) {
                            Box(Modifier.fillMaxSize().clipToBounds()) {
                                Box(Modifier.fillMaxHeight().width(2.dp).background(accent))
                                Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp)) {
                                    Text(group.course.name,
                                        fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp,
                                        color = if (active.isEmpty()) timetableMuted else MaterialTheme.colorScheme.onSurface, softWrap = true)
                                    Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
                                        Text(overviewLocation(group, pageWeek), fontSize = 10.sp, lineHeight = 13.sp, color = timetableMuted,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (block.groups.size > 1) Text("另${block.groups.size - 1}项 ›", fontSize = 9.sp, lineHeight = 13.sp, color = timetableBlue, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
              }
              }
            }
          }
        }
        val pending = termCourses.count { !it.arranged }
        if (pending > 0) Text("另有${pending}条待排时间，请在日程查看", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)).padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 11.sp, color = timetableMuted)
      }
    }
    if (choosingWeek) TeachingWeekPicker(headingWeek, maxWeek, semester, today,
        onSelect = { chosen ->
            choosingWeek = false
            scope.launch { pager.animateScrollToPage(chosen - 1, animationSpec = tween(260)) }
        }, onDismiss = { choosingWeek = false }, extraContent = {
            if (!settingsOpensDisplay) {
                if (demo) Text("演示课表，课程均为虚构内容。", fontSize = 12.sp, color = timetableMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示非本周课程", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(showInactive, onCheckedChange = { showInactive = it; preferences.timetableShowInactive = it })
                }
                Text("左右拖动课表可预览相邻周，松手后自动吸附；滑动不足会回弹，灰色为非本周课程。课程全名自动换行，点课程可查看各周地点与教师。", fontSize = 12.sp, color = timetableMuted)
                Text("课程卡片底色 ${cardOpacity.toInt()}%", fontSize = 13.sp)
                Slider(value = cardOpacity, onValueChange = { cardOpacity = it }, valueRange = 80f..98f,
                    onValueChangeFinished = { preferences.timetableCardOpacity = cardOpacity.toInt() })
                Text("有壁纸时生效；底色越高，文字越清楚。壁纸在设置 → 外观与启动中调整。", fontSize = 12.sp, color = timetableMuted)
            }
        })
    if (choosingDisplay) AlertDialog(onDismissRequest = { choosingDisplay = false },
        title = { Text("课表显示") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示非本周课程", Modifier.weight(1f))
                    Switch(showInactive, onCheckedChange = { showInactive = it; preferences.sharedTimetableShowInactive = it })
                }
                Text("仅影响收到的课表。左右滑动切换周次，点击课程查看详细信息。", fontSize = 12.sp, color = timetableMuted)
            }
        }, confirmButton = { TextButton(onClick = { choosingDisplay = false }) { Text("完成") } })
    selected?.let { block ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(if (block.groups.size == 1) block.groups.first().course.name else "这个时段的安排") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                block.groups.forEach { group ->
                    Text(group.course.name, fontWeight = FontWeight.Bold)
                    Text("周${days[(group.course.weekday ?: 1) - 1]} · 第${group.course.firstPeriod}–${group.course.lastPeriod}节", fontSize = 13.sp)
                    group.placements.sortedByDescending { week in it.weeks }.forEach { placement ->
                        Surface(color = if (week in placement.weeks) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (week in placement.weeks) "本周上课" else "非本周安排", color = timetableBlue, fontSize = 12.sp)
                                Text(formatCourseWeeks(placement.weeks), fontSize = 12.sp)
                                Text(placement.room.ifBlank { "地点待定" }, fontSize = 13.sp)
                                Text(placement.teacher.ifBlank { "教师待定" }, fontSize = 12.sp)
                                val times = placement.times(periods)
                                Text("${times.first ?: "待定"}–${times.second ?: "待定"}", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("完成") } })
    }
}

/** Dates use the grid's exact page offset, without a second pager or gesture owner. */
@Composable private fun TimetableDates(pager: PagerState, semester: Semester, today: LocalDate) {
    // A refreshed snapshot can shorten the term before the pager's next measure clamps its page.
    val currentPage = pager.currentPage.coerceIn(0, pager.pageCount - 1)
    val pages = (maxOf(0, currentPage - 1)..minOf(pager.pageCount - 1, currentPage + 1)).toList()
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 4.dp)) {
        Spacer(Modifier.width(38.dp))
        Layout(modifier = Modifier.weight(1f).clipToBounds(), content = {
            pages.forEach { page ->
                key(page) {
                    val semantics = if (page == currentPage) Modifier else Modifier.clearAndSetSemantics { }
                    Row(Modifier.fillMaxWidth().then(semantics)) {
                        (1..7).forEach { day ->
                            val date = semester.dateOf(page + 1, day)
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                val color = if (date == today) timetableBlue else timetableMuted
                                Text("周${days[day - 1]}", color = color,
                                    fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp)
                                Text(date.format(timetableDateFormat), color = color, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }) { measurables, constraints ->
            val rows = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
            layout(constraints.maxWidth, rows.maxOf { it.height }) {
                rows.forEachIndexed { index, row ->
                    // Reading the fractional offset in placement avoids text measurement per frame.
                    val x = ((pages[index] - pager.currentPage - pager.currentPageOffsetFraction) * constraints.maxWidth).roundToInt()
                    row.place(x, 0)
                }
            }
        }
    }
}

/** Measures full text at the real column width, keeping all days aligned and stable across weeks. */
@Composable private fun measuredTimetablePeriodHeight(courses: List<CourseSlot>, dayWidth: Dp): Int {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val nameStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
    val locationStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 10.sp, lineHeight = 13.sp)
    val countStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 9.sp, lineHeight = 13.sp)
    // Weighted columns can differ by one physical pixel after rounding; measure the narrower case.
    val width = with(density) { ((dayWidth - 10.dp).toPx().toInt() - 1).coerceAtLeast(1) }
    return remember(courses, width, density.density, density.fontScale, measurer, nameStyle, locationStyle, countStyle) {
        val locationHeight = with(density) { measurer.measure("地点\n待定", locationStyle, constraints = Constraints(maxWidth = width)).size.height.toDp().value }
        val countHeight = with(density) { measurer.measure("另10项 ›", countStyle).size.height.toDp().value }
        (1..7).flatMap { timetableBlocks(courses, it, 1, true) }.maxOfOrNull { block ->
            val footer = locationHeight + if (block.groups.size > 1) countHeight + 4 else 0f
            block.groups.maxOf { group ->
                val nameHeight = with(density) { measurer.measure(group.course.name, nameStyle, constraints = Constraints(maxWidth = width)).size.height.toDp().value }
                timetableRequiredPeriodHeight(group.course.firstPeriod!!, group.course.lastPeriod!!, nameHeight + footer + 20)
            }
        } ?: 68
    }
}
