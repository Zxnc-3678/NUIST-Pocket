package cn.nuist.pocket

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
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
private val days = listOf("一", "二", "三", "四", "五", "六", "日")
private val timetableDateFormat = DateTimeFormatter.ofPattern("M/d")

internal val timetableBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

internal fun Modifier.timetableWallpaperBlur(radius: Float): Modifier =
    if (timetableBlurSupported && radius > 0f) blur(radius.dp) else this

internal class TimetableOpacity(private val preferences: AppPreferences) {
    var alpha by mutableFloatStateOf(preferences.timetableCardOpacity / 100f)
    var lightGlassAlpha by mutableFloatStateOf(preferences.lightGlassOpacity / 100f)

    fun save(lightGlass: Boolean) {
        if (lightGlass) preferences.lightGlassOpacity = (lightGlassAlpha * 100).roundToInt()
        else preferences.timetableCardOpacity = (alpha * 100).roundToInt()
        reload()
    }

    fun reload() {
        alpha = preferences.timetableCardOpacity / 100f
        lightGlassAlpha = preferences.lightGlassOpacity / 100f
    }

    fun surfaceAlpha(style: CourseCardStyle, dark: Boolean, hasWallpaper: Boolean): Float {
        val value = if (hasWallpaper) alpha else 1f
        return when {
            style != CourseCardStyle.LiquidGlass -> value
            !dark -> lightGlassAlpha
            else -> darkGlassSurfaceAlpha(value)
        }
    }
}

private fun darkGlassSurfaceAlpha(opacity: Float): Float =
    0.12f + (opacity.coerceIn(0.50f, 0.98f) - 0.50f) / 0.48f * 0.22f

@Composable
private fun glassTextShadow(liquid: Boolean, dark: Boolean): Shadow? {
    if (!liquid) return null
    val color = if (dark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.65f)
    return Shadow(color, blurRadius = with(LocalDensity.current) { 1.5.dp.toPx() })
}

@Composable
internal fun TimetableOpacitySlider(opacity: TimetableOpacity, style: CourseCardStyle) {
    val liquid = style == CourseCardStyle.LiquidGlass
    val dark = LocalPocketDark.current
    val lightGlass = liquid && !dark
    val shownAlpha = opacity.surfaceAlpha(style, dark, hasWallpaper = true)
    Text("${if (liquid) "玻璃面不透明度" else "课块不透明度"} ${(shownAlpha * 100).roundToInt()}%",
        fontSize = 13.sp, style = tabularTextStyle())
    val range = LIGHT_GLASS_OPACITY_RANGE
    Slider(
        value = if (lightGlass) opacity.lightGlassAlpha else opacity.alpha,
        onValueChange = { if (lightGlass) opacity.lightGlassAlpha = it else opacity.alpha = it },
        valueRange = if (lightGlass) range.first / 100f..range.last / 100f else 0.50f..0.98f,
        onValueChangeFinished = { opacity.save(lightGlass) },
    )
}

@Composable
internal fun BoxScope.TimetableWallpaper(wallpaper: Bitmap?, fade: Float, blur: Float, modifier: Modifier = Modifier) {
    if (wallpaper != null) {
        val image = remember(wallpaper) { wallpaper.asImageBitmap() }
        Box(Modifier.matchParentSize().then(modifier)) {
            Image(image, null, Modifier.matchParentSize().timetableWallpaperBlur(blur), contentScale = ContentScale.Crop)
            if (fade > 0f) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.background.copy(alpha = fade)))
        }
    }
}

private fun Modifier.timetableGridBorder(style: TimetableGridStyle, outline: Color): Modifier =
    if (style == TimetableGridStyle.HIDDEN) this else border(0.3.dp, outline.copy(alpha = style.alpha))

@Composable
private fun TimetableBreak(label: String?, hasWallpaper: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val edge = colors.outlineVariant.copy(alpha = 0.45f)
    Box(modifier.fillMaxWidth().height(22.dp)
        .background(colors.surfaceVariant.copy(alpha = if (hasWallpaper) 0.45f else 0.88f))
        .drawBehind {
            val stroke = 0.5.dp.toPx()
            drawLine(edge, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
            drawLine(edge, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
        }, contentAlignment = Alignment.Center) {
        if (label != null) Text(label, color = timetableMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

private data class CourseTileColors(val fill: Color, val title: Color, val accent: Color)

private fun ColorScheme.courseTileColors(
    style: CourseCardStyle, code: String, name: String, dark: Boolean, active: Boolean, opacity: Float,
    customColorHex: String? = null,
): CourseTileColors {
    val accent = if (active) courseAccentColor(code, name, dark, customColorHex) else onSurfaceVariant.copy(alpha = 0.5f)
    val morandi = active && style == CourseCardStyle.MorandiFill
    val fill = when {
        style == CourseCardStyle.LiquidGlass && !dark -> Color.White
        !active -> if (dark) surface else surfaceVariant
        morandi -> lerp(accent, surface, if (dark) 0.72f else 0.84f)
        dark -> surfaceVariant
        else -> surface
    }
    val title = when {
        !active && style == CourseCardStyle.LiquidGlass -> lerp(onSurfaceVariant, onSurface, 0.40f)
        !active -> onSurfaceVariant
        morandi -> lerp(accent, onSurface, if (dark) 0.48f else 0.66f)
        else -> onSurface
    }
    return CourseTileColors(fill.copy(alpha = opacity), title, accent)
}

private fun CourseCardStyle.tileBorder(dark: Boolean, tint: Color? = null): BorderStroke? = when (this) {
    CourseCardStyle.GlassOutline -> BorderStroke(0.8.dp,
        (tint?.let { lerp(Color.White, it, 0.55f) } ?: Color.White).copy(alpha = if (dark) 0.18f else 0.60f))
    CourseCardStyle.LiquidGlass -> BorderStroke(1.dp, Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.55f else 0.85f),
        0.5f to Color.White.copy(alpha = if (dark) 0.10f else 0.20f),
        1f to Color.White.copy(alpha = if (dark) 0.25f else 0.45f),
    ))
    else -> null
}

@Composable
private fun CourseAccentBar(style: CourseCardStyle, accent: Color) {
    if (style != CourseCardStyle.MorandiFill) {
        val width = when (style) {
            CourseCardStyle.ClassicBar -> 3.dp
            CourseCardStyle.LiquidGlass -> 1.dp
            else -> 2.dp
        }
        val color = if (style == CourseCardStyle.LiquidGlass) accent.copy(alpha = accent.alpha * 0.72f) else accent
        Box(Modifier.fillMaxHeight().width(width).background(color, CircleShape))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimetableScreen(courses: List<CourseSlot>, periods: List<Period>, semester: Semester, today: LocalDate, demo: Boolean,
                             preferences: AppPreferences, onSettings: () -> Unit, settingsEnabled: Boolean,
                             wallpaper: TimetableWallpaperImages? = null, wallpaperFade: Float = 0.45f, suspendedDates: Set<LocalDate> = emptySet(),
                             settingsOpensDisplay: Boolean = false, appearanceRevision: Int = 0,
                             opacity: TimetableOpacity = remember(preferences) { TimetableOpacity(preferences) },
                             edits: CourseEdits? = null, immersiveViewport: GlassViewport? = null) {
    val termCourses = remember(courses, semester) { courses.filter { it.semester == semester.code } }
    val maxWeek = edits?.lastWeek ?: teachingWeekLimit(termCourses)
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
    var choosingWeek by rememberSaveable { mutableStateOf(false) }
    var choosingDisplay by rememberSaveable { mutableStateOf(false) }
    var courseSheet by remember(semester.code) { mutableStateOf<CourseSheetPage?>(null) }
    val dark = LocalPocketDark.current
    val colors = MaterialTheme.colorScheme
    val cardStyle = remember(appearanceRevision) { preferences.courseCardStyle }
    val gridStyle = remember(appearanceRevision) { preferences.timetableGridStyle }
    val wallpaperBlur = remember(appearanceRevision) { preferences.timetableWallpaperBlur }
    val gridBorder = Modifier.timetableGridBorder(gridStyle, colors.outlineVariant)
    val courseBorder = remember(cardStyle, dark) { cardStyle.tileBorder(dark) }
    val liquid = cardStyle == CourseCardStyle.LiquidGlass
    val fillOpacity = opacity.surfaceAlpha(cardStyle, dark, hasWallpaper = wallpaper != null)
    val immersive = immersiveViewport != null
    val railColor = colors.surface.copy(alpha = if (immersive) if (dark) 0.62f else 0.66f else 0.94f)
    val textShadow = glassTextShadow(liquid, dark)
    val locationColor = if (liquid) colors.onSurface.copy(alpha = 0.85f) else timetableMuted
    val lastPeriod = maxOf(11, termCourses.mapNotNull { it.lastPeriod }.maxOrNull() ?: 11).coerceAtMost(12)
    val scroll = rememberScrollState()
    val localViewport = remember { GlassViewport() }
    val glassViewport = immersiveViewport ?: localViewport
    val scrollPosition = remember(pager, scroll) {
        derivedStateOf { (pager.currentPage + pager.currentPageOffsetFraction) to scroll.value }
    }
    val glassPlacement = if (liquid && wallpaper != null) Modifier.onPlaced { localViewport.coordinates = it } else Modifier
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val periodHeight = measuredTimetablePeriodHeight(termCourses, ((maxWidth - 38.dp) / 7).coerceAtLeast(1.dp))
      Column(Modifier.fillMaxSize().background(if (immersive) Color.Transparent else colors.surface)) {
        PocketPageHeader({ if (settingsOpensDisplay) choosingDisplay = true else onSettings() }, settingsEnabled, sideWidth = 48.dp, leading = {
            if (demo) Text("演示", Modifier.clickable(onClick = { choosingWeek = true }).padding(horizontal = 6.dp, vertical = 8.dp),
                fontSize = 11.sp, maxLines = 1, softWrap = false, color = timetableMuted)
            else IconButton(onClick = { choosingWeek = true }) { Icon(Icons.Outlined.DateRange, if (settingsOpensDisplay) "选择周次" else "周次与课表显示") }
        }) {
            TeachingWeekHeading(headingWeek, subtitle = null, modifier = Modifier.fillMaxWidth(), fontSize = 20.sp, onClick = { choosingWeek = true })
        }
        TimetableDates(pager, semester, today)
        Box(Modifier.fillMaxWidth().weight(1f)) {
          if (!immersive) TimetableWallpaper(wallpaper?.original, wallpaperFade, wallpaperBlur, glassPlacement)
          Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.width(38.dp).height((timetableTop(lastPeriod, periodHeight) + periodHeight).dp).background(railColor)) {
                (1..lastPeriod).forEach { number ->
                    Column(Modifier.offset(y = timetableTop(number, periodHeight).dp).fillMaxWidth().height(periodHeight.dp).then(gridBorder),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("$number", fontWeight = FontWeight.Medium, fontSize = 13.sp, style = tabularTextStyle())
                        periods.find { it.number == number }?.let { period ->
                            Text(period.start.toString(), color = timetableMuted, fontSize = 8.sp, style = tabularTextStyle())
                            Text(period.end.toString(), color = timetableMuted, fontSize = 8.sp, style = tabularTextStyle())
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
                  val visible = termCourses.filter { it.visibleInTimetableWeek(pageWeek) }
                  (1..7).associateWith { timetableBlocks(visible, it, pageWeek, showInactive) }
              }
              Row(Modifier.fillMaxSize()) {
              (1..7).forEach { day ->
                Box(Modifier.weight(1f).height((timetableTop(lastPeriod, periodHeight) + periodHeight).dp)) {
                    val date = semester.dateOf(pageWeek, day)
                    val suspended = date in suspendedDates
                    val dayBlocks = if (suspended) emptyList() else blocks.getValue(day)
                    (1..lastPeriod).forEach { number ->
                        val addModifier = if (edits != null && !suspended && dayBlocks.none { number in it.first..it.last }) {
                            val addCourse = { courseSheet = newCourseSheet(date, number, periods) }
                            Modifier.pointerInput(date, number, periods) { detectTapGestures(onLongPress = { addCourse() }) }
                                .semantics { onLongClick(label = "添加自定义课程") { addCourse(); true } }
                        } else Modifier
                        Box(Modifier.offset(y = timetableTop(number, periodHeight).dp).fillMaxWidth().height(periodHeight.dp)
                            .then(gridBorder).then(addModifier))
                    }
                    listOf(4 to "午休", 8 to "晚休").forEach { (after, label) ->
                        TimetableBreak(label.takeIf { day == 4 }, wallpaper != null,
                            Modifier.offset(y = (timetableTop(after, periodHeight) + periodHeight).dp))
                    }
                    if (suspended) Text("停课", Modifier.align(Alignment.TopCenter).padding(top = 8.dp), color = timetableMuted, fontSize = 11.sp)
                    dayBlocks.forEach { block ->
                        val group = block.groups.first()
                        val course = group.course
                        val active = group.activePlacements(pageWeek).isNotEmpty()
                        val tile = colors.courseTileColors(cardStyle, course.code, course.name, dark, active, fillOpacity, course.customColorHex)
                        val tint = tile.accent.takeIf { active && course.customColorHex != null }
                        val border = if (tint != null && cardStyle == CourseCardStyle.GlassOutline) cardStyle.tileBorder(dark, tint) else courseBorder
                        val badge = if (active) courseBadge(course) else null
                        val detailColor = if (liquid) tile.title.copy(alpha = 0.88f) else timetableMuted
                        val press = rememberPocketPress()
                        Surface(color = if (liquid) Color.Transparent else tile.fill,
                            shape = PocketShapes.Compact, border = border,
                            modifier = Modifier.offset(y = timetableTop(block.first, periodHeight).dp).fillMaxWidth().height(timetableHeight(block.first, block.last, periodHeight).dp).padding(1.dp)
                                .then(press.modifier()).clip(PocketShapes.Compact).combinedClickable(
                                    interactionSource = press.interaction, indication = LocalIndication.current,
                                    onClick = { courseSheet = CourseSheetPage.Details(block.groups, pageWeek) },
                                    onLongClick = if (edits == null) null else ({
                                        courseSheet = CourseSheetPage.Details(block.groups, pageWeek, actionsFirst = true)
                                    }), onLongClickLabel = "课程操作")
                                .semantics(mergeDescendants = true) { contentDescription = course.displayName + (if (!active) "，非本周课程" else "") + (if (block.groups.size > 1) "，共${block.groups.size}项安排" else "") }) {
                            Box(Modifier.fillMaxSize().clipToBounds()) {
                                if (liquid) GlassCourseLayer(wallpaper, glassViewport, tile.fill, dark, tint, scrollPosition, press.scale)
                                CourseAccentBar(cardStyle, tile.accent)
                                Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp)) {
                                    if (badge != null) Text(badge, Modifier.align(Alignment.End), fontSize = 9.sp, lineHeight = 10.sp,
                                        fontWeight = FontWeight.Medium, color = detailColor)
                                    Text(course.displayName,
                                        fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp,
                                        color = tile.title, softWrap = true, style = LocalTextStyle.current.copy(shadow = textShadow))
                                    Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
                                        Text(overviewLocation(group, pageWeek), fontSize = 10.sp, lineHeight = 13.sp, color = locationColor,
                                            fontWeight = if (liquid) FontWeight.Medium else null,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis, style = tabularTextStyle().copy(shadow = textShadow))
                                        if (block.groups.size > 1) Text("另${block.groups.size - 1}项 ›", fontSize = 9.sp, lineHeight = 13.sp,
                                            color = if (liquid) detailColor else timetableBlue, maxLines = 1, style = tabularTextStyle())
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
        if (pending > 0) Text("另有${pending}条待排时间，请在日程查看", Modifier.fillMaxWidth().background(railColor).padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 11.sp, color = timetableMuted, style = tabularTextStyle())
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
                Text(if (edits == null) "左右滑动切周，灰色是非本周课程。点课块可查看各周地点与教师。"
                    else "左右滑动切周。点课块看详情，长按课块修改，长按空白格添加课程。",
                    fontSize = 12.sp, color = timetableMuted)
                TimetableOpacitySlider(opacity, cardStyle)
                Text("有壁纸时生效。壁纸在设置 → 外观与启动中调整。", fontSize = 12.sp, color = timetableMuted)
                if (edits != null) {
                    TextButton(onClick = {
                        choosingWeek = false
                        val date = today.takeIf { semester.weekOn(it) == headingWeek } ?: semester.dateOf(headingWeek, 1)
                        courseSheet = newCourseSheet(date, 1, periods)
                    }) { Text("添加自定义课程") }
                    if (edits.rules.sessions.isNotEmpty()) TextButton(onClick = {
                        choosingWeek = false
                        courseSheet = CourseSheetPage.Changes
                    }) { Text("临时调整") }
                }
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
    CourseSheets(courseSheet, { courseSheet = it }, edits, semester, periods, today, week)
}

@Composable
internal fun TimetableAppearancePreview(
    wallpaper: TimetableWallpaperImages?, fade: Float, blur: Float,
    style: CourseCardStyle, grid: TimetableGridStyle, opacity: TimetableOpacity,
) {
    val colors = MaterialTheme.colorScheme
    val fillAlpha = opacity.surfaceAlpha(style, LocalPocketDark.current, hasWallpaper = wallpaper != null)
    val glassViewport = remember { GlassViewport() }
    val glassPlacement = if (style == CourseCardStyle.LiquidGlass && wallpaper != null)
        Modifier.onPlaced { glassViewport.coordinates = it } else Modifier
    Box(Modifier.fillMaxWidth().height(168.dp).clip(PocketShapes.Control).background(colors.surface)) {
        TimetableWallpaper(wallpaper?.original, fade, blur, glassPlacement)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(24.dp).background(colors.surface), verticalAlignment = Alignment.CenterVertically) {
                listOf("周一", "周二", "周三").forEach { day ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(day, color = timetableMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            PreviewCourseRow(0, "气象资料分析", "明德 N208", style, grid, fillAlpha, wallpaper, glassViewport, Modifier.weight(1f))
            TimetableBreak("午休", wallpaper != null)
            PreviewCourseRow(2, "天气学原理", "尚贤 606", style, grid, fillAlpha, wallpaper, glassViewport, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PreviewCourseRow(
    courseColumn: Int, title: String, room: String,
    style: CourseCardStyle, grid: TimetableGridStyle, opacity: Float,
    wallpaper: TimetableWallpaperImages?, glassViewport: GlassViewport, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalPocketDark.current
    val tile = colors.courseTileColors(style, "preview", title, dark, active = true, opacity = opacity)
    val liquid = style == CourseCardStyle.LiquidGlass
    val textShadow = glassTextShadow(liquid, dark)
    Row(modifier.fillMaxWidth()) {
        repeat(3) { column ->
            Box(Modifier.weight(1f).fillMaxHeight().timetableGridBorder(grid, colors.outlineVariant)) {
                if (column == courseColumn) {
                    Surface(Modifier.fillMaxSize().padding(2.dp), shape = PocketShapes.Compact,
                        color = if (liquid) Color.Transparent else tile.fill, border = style.tileBorder(dark)) {
                        Box(Modifier.fillMaxSize().clipToBounds()) {
                            if (liquid) GlassCourseLayer(wallpaper, glassViewport, tile.fill, dark)
                            CourseAccentBar(style, tile.accent)
                            Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(title, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                                    color = tile.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = LocalTextStyle.current.copy(shadow = textShadow))
                                Text(room, fontSize = 10.sp, lineHeight = 13.sp,
                                    color = if (liquid) colors.onSurface.copy(alpha = 0.85f) else timetableMuted,
                                    fontWeight = if (liquid) FontWeight.Medium else null,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = tabularTextStyle().copy(shadow = textShadow))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Dates use the grid's exact page offset, without a second pager or gesture owner. */
@Composable private fun TimetableDates(pager: PagerState, semester: Semester, today: LocalDate) {
    // A refreshed snapshot can shorten the term before the pager's next measure clamps its page.
    val currentPage = pager.currentPage.coerceIn(0, pager.pageCount - 1)
    val pages = (maxOf(0, currentPage - 1)..minOf(pager.pageCount - 1, currentPage + 1)).toList()
    val fill = if (LocalPocketImmersive.current) Color.Transparent else MaterialTheme.colorScheme.surface
    Row(Modifier.fillMaxWidth().background(fill).padding(vertical = 4.dp)) {
        Spacer(Modifier.width(38.dp))
        Layout(modifier = Modifier.weight(1f).clipToBounds(), content = {
            pages.forEach { page ->
                key(page) {
                    val semantics = if (page == currentPage) Modifier else Modifier.clearAndSetSemantics { }
                    Row(Modifier.fillMaxWidth().then(semantics)) {
                        (1..7).forEach { day ->
                            val date = semester.dateOf(page + 1, day)
                            val isToday = date == today
                            val dateColors = MaterialTheme.colorScheme
                            val color = if (isToday) dateColors.primary else timetableMuted
                            val weight = if (isToday) FontWeight.Bold else FontWeight.Medium
                            val capsule = if (isToday) Modifier.background(dateColors.primaryContainer, CircleShape) else Modifier
                            Column(Modifier.weight(1f).padding(horizontal = 2.dp).then(capsule).padding(vertical = 3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("周${days[day - 1]}", color = color,
                                    fontWeight = weight, fontSize = 12.sp, lineHeight = 16.sp)
                                Text(date.format(timetableDateFormat), color = color, fontSize = 10.sp, lineHeight = 13.sp,
                                    fontWeight = weight, style = tabularTextStyle())
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
    val locationStyle = tabularTextStyle(MaterialTheme.typography.bodyLarge).copy(fontSize = 10.sp, lineHeight = 13.sp)
    val countStyle = tabularTextStyle(MaterialTheme.typography.bodyLarge).copy(fontSize = 9.sp, lineHeight = 13.sp)
    // Weighted columns can differ by one physical pixel after rounding; measure the narrower case.
    val width = with(density) { ((dayWidth - 10.dp).toPx().toInt() - 1).coerceAtLeast(1) }
    return remember(courses, width, density.density, density.fontScale, measurer, nameStyle, locationStyle, countStyle) {
        val locationHeight = with(density) { measurer.measure("地点\n待定", locationStyle, constraints = Constraints(maxWidth = width)).size.height.toDp().value }
        val countHeight = with(density) { measurer.measure("另10项 ›", countStyle).size.height.toDp().value }
        (1..7).flatMap { timetableBlocks(courses, it, 1, true) }.maxOfOrNull { block ->
            val footer = locationHeight + if (block.groups.size > 1) countHeight + 4 else 0f
            block.groups.maxOf { group ->
                val nameHeight = with(density) {
                    measurer.measure(group.course.displayName, nameStyle, constraints = Constraints(maxWidth = width)).size.height.toDp().value +
                        if (courseBadge(group.course) == null) 0f else 10.sp.toDp().value
                }
                timetableRequiredPeriodHeight(maxOf(block.first, group.course.firstPeriod!!),
                    minOf(block.last, group.course.lastPeriod!!), nameHeight + footer + 20)
            }
        } ?: 68
    }
}

private fun courseBadge(course: CourseSlot): String? = when {
    course.isRescheduled -> "调"
    course.isCustom -> "自"
    else -> null
}
