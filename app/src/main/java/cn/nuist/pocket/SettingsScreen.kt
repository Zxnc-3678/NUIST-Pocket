package cn.nuist.pocket

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import cn.nuist.pocket.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal data class SettingsStatus(
    val connected: Boolean, val busy: Boolean, val importing: Boolean,
    val cacheLabel: String?, val hasImported: Boolean, val firstMonday: LocalDate, val preview: Boolean,
    val track: StudentTrack = StudentTrack.UNDERGRADUATE, val termCode: String? = null,
    val calendarDate: LocalDate = firstMonday,
)
internal data class SettingsActions(
    val close: () -> Unit, val login: () -> Unit, val sync: () -> Unit, val logout: () -> Unit,
    val widget: () -> Unit, val chooseImport: () -> Unit, val calendar: (LocalDate) -> Unit,
    val clearImport: () -> Unit, val preview: () -> Unit, val schoolWeb: () -> Unit,
    val permission: () -> Unit, val reminder: () -> Unit, val appearance: () -> Unit,
)
internal enum class SettingsPage(val title: String) {
    HOME("设置"), ACCOUNT("学校账号"), APPEARANCE("外观与启动"), REMINDERS("课程与提醒"),
    WIDGET("桌面小组件"), DATA("课表数据"), PERMISSIONS("权限与后台"), HELP("使用指南"),
}

@Composable
internal fun SettingsScreen(preferences: AppPreferences, status: SettingsStatus, actions: SettingsActions,
                            notificationRevision: Int, wallpapers: WallpaperSession, opacity: TimetableOpacity, today: LocalDate,
                            initialPage: SettingsPage, onTrackChange: (StudentTrack) -> Unit) {
    val settingsContext = LocalContext.current
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var backPage by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var showReleaseNotes by rememberSaveable { mutableStateOf(false) }
    var choosingCalendar by rememberSaveable { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun back() { if (page == SettingsPage.HOME) actions.close() else { page = backPage; backPage = SettingsPage.HOME } }
    fun openHelpSetting(target: SettingsPage) { backPage = SettingsPage.HELP; page = target }
    Dialog(onDismissRequest = actions.close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PocketSystemBars((LocalView.current.parent as DialogWindowProvider).window)
        BackHandler { back() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, when {
                            page == SettingsPage.HOME -> "关闭设置"
                            backPage == SettingsPage.HELP -> "返回使用指南"
                            backPage == SettingsPage.REMINDERS -> "返回课程与提醒"
                            else -> "返回设置"
                        })
                    }
                    Text(page.title, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 29.sp, modifier = Modifier.weight(1f))
                    if (page != SettingsPage.HOME) TextButton(onClick = actions.close) { Text("完成") }
                }
                AnimatedContent(targetState = page,
                    transitionSpec = { fadeIn(tween(170)) togetherWith fadeOut(tween(100)) }, label = "设置页面切换") { displayedPage ->
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (displayedPage == SettingsPage.HOME) {
                            item { SettingsGroup("账号与数据") {
                                SettingsEntry("学校账号", if (status.connected) "已连接 · 同步课表与考试" else if (status.cacheLabel != null) "连接待验证 · 已保留缓存" else "登录与同步", Icons.Outlined.Person) { page = SettingsPage.ACCOUNT }
                                SettingsEntryDivider()
                                SettingsEntry("课表数据", "手动导入、学期起始日与演示", Icons.AutoMirrored.Outlined.List) { page = SettingsPage.DATA }
                                SettingsEntryDivider()
                                SettingsEntry("面对面分享课表", "扫码分享或查看收到的独立课表", Icons.Outlined.Share) { settingsContext.startActivity(Intent(settingsContext, ShareTimetableActivity::class.java)) }
                            } }
                            item { SettingsGroup("外观与提醒") {
                                SettingsEntry("外观与启动", "深浅模式、主题色、课表装潢和默认页面", Icons.Outlined.Edit) { page = SettingsPage.APPEARANCE }
                                SettingsEntryDivider()
                                SettingsEntry("桌面小组件", "背景图片、纯色与待办展示", Icons.Outlined.DateRange) { page = SettingsPage.WIDGET }
                                SettingsEntryDivider()
                                SettingsEntry("课程与提醒", "课前提醒、静音预约与晚间安排", Icons.Outlined.Notifications) { page = SettingsPage.REMINDERS }
                                SettingsEntryDivider()
                                SettingsEntry("权限与后台", "通知、准时提醒、声音与锁屏设置", Icons.Outlined.Settings) { page = SettingsPage.PERMISSIONS }
                            } }
                            item { SettingsGroup("指南与关于") {
                                SettingsEntry("使用指南", "入门步骤、功能用法与常见问题", Icons.Outlined.Info) { page = SettingsPage.HELP }
                                SettingsEntryDivider()
                                SettingsEntry("本次更新", "v${BuildConfig.VERSION_NAME} · 更新小提示", Icons.Outlined.Info) { showReleaseNotes = true }
                            } }
                            item {
                                Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("NUIST Pocket ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = muted)
                                    Text("作者：仲夏南城  ·  开发协作：ChatGPT、Gemini", fontSize = 12.sp, color = muted)
                                    Text("非学校官方应用 · ${status.track.title} / 校本部", fontSize = 11.sp, color = muted)
                                }
                            }
                        } else if (displayedPage == SettingsPage.HELP) {
                            item { SettingsCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                QuickStartSteps()
                                OutlinedButton(shape = PocketShapes.Control, onClick = { openHelpSetting(SettingsPage.ACCOUNT) }, modifier = Modifier.fillMaxWidth()) { Text("去连接学校账号") }
                                TextButton(onClick = { openHelpSetting(SettingsPage.PERMISSIONS) }, modifier = Modifier.fillMaxWidth()) { Text("检查提醒权限") }
                                Text("不想现在登录也没事，可以先看演示数据；本地待办随时都能直接记。", color = muted, fontSize = 12.sp, lineHeight = 18.sp)
                            } } }
                            item { SettingsCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("页面速览", fontWeight = FontWeight.Medium, fontSize = 17.sp)
                                HelpFeature("日程", "按时间看当天课程和待办，直接勾选完成。点顶部周次可跳周。", Icons.Outlined.Home)
                                HelpFeature("课程表", "左右滑动切周。点课块看详情，长按课块编辑，长按空白格加课。", Icons.Outlined.DateRange)
                                if (status.track.supportsRooms) HelpFeature("教室", "选好教学楼和节次，找空教室自习一目了然。", Icons.Outlined.Place)
                                HelpFeature("考试", "集中查看考试时间、考场和座位号，考前不用再去教务系统翻。", Icons.AutoMirrored.Outlined.List)
                            } } }
                            item { SettingsCard {
                                Text("按习惯设置", Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 4.dp), fontWeight = FontWeight.Medium, fontSize = 17.sp)
                                SettingsEntry("外观与启动", "选择主题、课表壁纸和默认页面", Icons.Outlined.Edit) { openHelpSetting(SettingsPage.APPEARANCE) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                SettingsEntry("课程与提醒", "课前20分钟、静音预约与晚间提醒", Icons.Outlined.Notifications) { openHelpSetting(SettingsPage.REMINDERS) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                SettingsEntry("桌面小组件", "添加到桌面，设置独立背景与待办", Icons.Outlined.DateRange) { openHelpSetting(SettingsPage.WIDGET) }
                            } }
                            item { SettingsCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("常见问题", fontWeight = FontWeight.Medium, fontSize = 17.sp)
                                HelpAnswer("学校登录到期怎么办？", "在「学校账号」先试着恢复连接并同步。需要重新认证时，用学校原版页面登录，再点「验证登录」。重新认证前可继续查看本机缓存。")
                                HelpAnswer("收不到课前或晚间提醒，锁屏也不显示？", "先在「课程与提醒」确认对应开关已开，再到「权限与后台」检查通知和锁屏显示。课前提醒还需要准时提醒权限。晚间提醒要有本机课表。")
                                HelpAnswer("临时调课、手动加课会被重新同步冲掉吗？", "平时同步会保留本地调整。退出学校账号或主动重新登录时，会清理当前培养类型的调整。")
                                HelpAnswer("桌面小组件没有自动刷新怎么办？", "先在 App 同步课表，再点小组件右上角的刷新图标。自动换课有延迟时，可以到「权限与后台」看看系统的电池限制。")
                                HelpAnswer("我的课表和待办数据保存在哪里？", "都保存在本机。待办独立保存，退出学校账号后仍可查看。课表仅在你主动分享时发给别人。")
                            } } }
                        } else {
                            when (displayedPage) {
                                SettingsPage.ACCOUNT -> {
                                    item { SettingsSection {
                                        Text("培养类型", fontWeight = FontWeight.Medium)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            StudentTrack.entries.forEach { choice ->
                                                FilterChip(selected = status.track == choice, shape = PocketShapes.Compact,
                                                    enabled = !status.busy && !status.importing, onClick = { onTrackChange(choice) },
                                                    label = { Text(choice.title) }, modifier = Modifier.weight(1f))
                                            }
                                        }
                                        Text("本科与研究生数据独立保存，切换后需重新登录验证；研究生模式隐藏教室入口。", color = muted, fontSize = 12.sp)
                                    } }
                                    item { SettingsSection {
                                        Text(if (status.connected) "学校账号已连接" else if (status.cacheLabel != null) "学校连接待验证" else "连接学校账号", fontWeight = FontWeight.Medium)
                                        Text("在学校原版页面完成登录，再点“验证登录”。课表与考试会保存到本机。", color = muted, fontSize = 13.sp)
                                        status.cacheLabel?.let { Text(it, color = muted, fontSize = 12.sp, style = tabularTextStyle()) }
                                        Button(shape = PocketShapes.Control, enabled = !status.busy && !status.importing && (status.connected || status.cacheLabel != null), onClick = actions.sync, modifier = Modifier.fillMaxWidth()) {
                                            Text(if (status.busy) "正在同步…" else if (!status.connected && status.cacheLabel != null) "恢复连接并同步" else "同步课表和考试")
                                        }
                                        if (!status.connected && status.cacheLabel == null) Text("请先登录学校账号再同步。", fontSize = 12.sp, color = muted)
                                        OutlinedButton(shape = PocketShapes.Control, enabled = !status.busy && !status.importing, onClick = actions.login, modifier = Modifier.fillMaxWidth()) { Text(if (status.connected || status.cacheLabel != null) "重新登录学校" else "登录学校账号") }
                                        Text("重新登录会清除上一账号的学校缓存。会话到期后的处理和数据说明，可在“使用指南”查看。", color = muted, fontSize = 12.sp)
                                        TextButton(onClick = actions.schoolWeb, modifier = Modifier.fillMaxWidth()) { Text("打开学校教务网页") }
                                        TextButton(enabled = !status.busy && !status.importing, onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("退出登录并清除学校与导入数据", color = MaterialTheme.colorScheme.error) }
                                    } }
                                }
                                SettingsPage.APPEARANCE -> item { AppearanceControls(preferences, wallpapers, opacity, actions.appearance) }
                                SettingsPage.REMINDERS -> {
                                    item { SettingsSection {
                                        Text("课前提醒与声音预约", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                        ClassReminderControls(preferences, notificationRevision, actions.permission) {
                                            backPage = SettingsPage.REMINDERS
                                            page = SettingsPage.PERMISSIONS
                                        }
                                    } }
                                    item { SettingsSection {
                                        EveningReminderControls(preferences, notificationRevision, actions.permission, actions.reminder)
                                    } }
                                }
                                SettingsPage.PERMISSIONS -> item { SettingsSection { PermissionChecklist(notificationRevision, actions.permission) } }
                                SettingsPage.WIDGET -> item { WidgetControls(actions.widget) }
                                SettingsPage.DATA -> item { SettingsSection {
                                    if (status.track == StudentTrack.GRADUATE) {
                                        Text("研究生校历与作息", fontWeight = FontWeight.Medium)
                                        Text("采用已核对的学校校历和校本部作息。尚未核对的新学期需设置第1周周一；如个人安排不同，也可手动调整。", color = muted, fontSize = 13.sp, style = tabularTextStyle())
                                        Text(status.termCode?.let { "当前学期：$it" } ?: "请先验证研究生学校登录", fontSize = 13.sp, style = tabularTextStyle())
                                        val confirmedDate = status.termCode?.let { graduateFirstMonday(it, preferences.graduateMonday(it)) }
                                        OutlinedButton(enabled = status.termCode != null && !status.busy && !status.importing,
                                            onClick = { choosingCalendar = true }, shape = PocketShapes.Control, modifier = Modifier.fillMaxWidth()) {
                                            Text(confirmedDate?.let { "研究生第1周周一：$it" } ?: "选择研究生第1周周一", style = tabularTextStyle())
                                        }
                                        val periods = remember { PocketStore(settingsContext, StudentTrack.GRADUATE).periods }
                                        Text(periods.filter { it.enabled }.joinToString("  ") { "${it.number}节 ${it.start}–${it.end}" }, color = muted, fontSize = 12.sp, style = tabularTextStyle())
                                        OutlinedButton(shape = PocketShapes.Control, enabled = status.termCode != null && !status.busy && !status.importing && confirmedDate != null,
                                            onClick = actions.sync, modifier = Modifier.fillMaxWidth()) { Text("同步研究生课表与考试") }
                                        HorizontalDivider()
                                    }
                                    Text("手动导入课表", fontWeight = FontWeight.Medium)
                                    Text("已登录的同学优先使用学校同步。手动导入适用于保存好的课表接口JSON文件。", color = muted, fontSize = 13.sp)
                                    OutlinedButton(shape = PocketShapes.Control, enabled = !status.importing && !status.busy, onClick = actions.chooseImport, modifier = Modifier.fillMaxWidth()) { Text(if (status.importing) "正在导入…" else "选择课表JSON文件") }
                                    Text("支持cxxszhxqkb完整响应，最大1MB，仅保留课程必需字段。导入会切换到本机课表，不混用学校缓存。", color = muted, fontSize = 12.sp)
                                    if (status.track == StudentTrack.UNDERGRADUATE) {
                                        OutlinedButton(enabled = !status.importing && !status.busy, onClick = { choosingCalendar = true }, shape = PocketShapes.Control, modifier = Modifier.fillMaxWidth()) { Text("第一教学周周一：${status.firstMonday}", style = tabularTextStyle()) }
                                        Text("仅影响导入课表；切换学期后请核对日期。", color = muted, fontSize = 12.sp)
                                    } else Text("研究生学期起点可在本页上方调整。", color = muted, fontSize = 12.sp)
                                    if (status.hasImported) TextButton(enabled = !status.importing && !status.busy, onClick = actions.clearImport) { Text("仅清除导入课表") }
                                    if (status.cacheLabel == null && !status.hasImported) TextButton(enabled = !status.importing && !status.busy, onClick = actions.preview) { Text(if (status.preview) "返回登录引导" else "预览演示数据") }
                                } }
                                SettingsPage.HELP, SettingsPage.HOME -> Unit
                            }
                        }
                    }
                }
            }
        }
        if (choosingCalendar) PocketDatePicker(status.calendarDate, today, onDismiss = { choosingCalendar = false },
            mondaysOnly = true) { date ->
            choosingCalendar = false
            actions.calendar(date)
        }
        if (showReleaseNotes) ReleaseHighlights { showReleaseNotes = false }
        if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("退出并清除本机数据？") },
            text = { Text("将清除学校登录会话、课表、考试和导入课表。个人待办、主题、壁纸与提醒偏好保留。") },
            confirmButton = { TextButton(onClick = { confirmClear = false; actions.logout() }) { Text("退出并清除") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    }
}

@Composable private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    PocketCard(Modifier.fillMaxWidth(), shape = PocketShapes.Hero) { Column(content = content) }
}

@Composable
internal fun SettingsSection(horizontalPadding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    SettingsCard {
        Column(Modifier.padding(horizontal = horizontalPadding, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp, fontWeight = FontWeight.Medium)
        SettingsCard(content)
    }
}

@Composable
private fun SettingsEntryDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
}

@Composable private fun SettingsEntry(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = PocketShapes.Control, color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, null, Modifier.padding(10.dp).size(21.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(summary, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
        }
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 22.sp)
    }
}

@Composable private fun AppearanceControls(preferences: AppPreferences, wallpapers: WallpaperSession, opacity: TimetableOpacity, onChange: () -> Unit) {
    val context = LocalContext.current
    val dark = LocalPocketDark.current
    val wallpaper = wallpapers.images
    var home by remember { mutableStateOf(preferences.homeTab) }
    var themeMode by remember { mutableStateOf(preferences.themeMode) }
    var accent by remember { mutableIntStateOf(preferences.themeAccent) }
    var scheduleMulticolor by remember { mutableStateOf(preferences.scheduleMulticolor) }
    var cardStyle by remember { mutableStateOf(preferences.courseCardStyle) }
    var gridStyle by remember { mutableStateOf(preferences.timetableGridStyle) }
    var wallpaperScope by remember { mutableStateOf(preferences.wallpaperScope) }
    var blur by remember { mutableFloatStateOf(preferences.timetableWallpaperBlur) }
    var custom by rememberSaveable { mutableStateOf(ThemeAccent.hex(accent)) }
    var editingColor by rememberSaveable { mutableStateOf(false) }
    var customError by remember { mutableStateOf(false) }
    var fade by remember { mutableFloatStateOf(preferences.wallpaperFade.toFloat()) }
    val timetableColors by produceState(emptyList<Int>(), wallpaper) {
        value = emptyList()
        if (wallpaper != null) value = withContext(Dispatchers.Default) { ThemeAccent.wallpaperColors(wallpaper.original) }
    }
    val widgetColors by produceState(emptyList<Int>(), context) {
        value = withContext(Dispatchers.IO) {
            WallpaperStore.widget(context).load()?.let { bitmap ->
                try { ThemeAccent.wallpaperColors(bitmap) } finally { bitmap.recycle() }
            }.orEmpty()
        }
    }
    fun setAccent(value: Int) {
        val normalized = ThemeAccent.readable(value)
        if (normalized != accent) {
            preferences.themeAccent = normalized; accent = normalized; onChange()
        }
        custom = ThemeAccent.hex(accent); customError = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSection {
            Text("主题与色彩", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("深浅模式", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                AppearanceChoices(ThemeMode.entries, themeMode, ThemeMode::title) {
                    themeMode = it
                    preferences.themeMode = it
                    onChange()
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("主题颜色", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    TextButton(onClick = { setAccent(ThemeAccent.DEFAULT) }) { Text("恢复默认", fontSize = 12.sp) }
                }
                ThemeColorSwatches(ThemeAccent.presets, accent, "主题色", onCustom = {
                    custom = ThemeAccent.hex(accent)
                    customError = false
                    editingColor = true
                }, onSelect = ::setAccent)
            }
            if (wallpaper != null && timetableColors.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("从当前课表壁纸提取", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ThemeColorSwatches(timetableColors, accent, "课表壁纸取色", onSelect = ::setAccent)
                }
            }
            if (widgetColors.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("从当前小组件壁纸提取", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ThemeColorSwatches(widgetColors, accent, "小组件壁纸取色", onSelect = ::setAccent)
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("日程页跟随多色课程色条", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Switch(checked = scheduleMulticolor, onCheckedChange = {
                    scheduleMulticolor = it
                    preferences.scheduleMulticolor = it
                    onChange()
                }, modifier = Modifier.semantics { contentDescription = "日程页跟随多色课程色条" })
            }
        }
        SettingsSection(horizontalPadding = 8.dp) {
            Text("课表装潢", Modifier.padding(horizontal = 10.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            TimetableAppearancePreview(wallpaper, fade / 100f, blur, cardStyle, gridStyle, opacity)
            Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("一键风格配方", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimetableRecipe.entries.forEach { recipe ->
                            val matches = cardStyle == recipe.style && gridStyle == recipe.grid &&
                                opacity.alpha == recipe.opacity / 100f && fade == recipe.fade.toFloat() && blur == recipe.blur &&
                                (dark || recipe.style != CourseCardStyle.LiquidGlass || opacity.lightGlassAlpha == DEFAULT_LIGHT_GLASS_OPACITY / 100f)
                            PocketChoiceChip(selected = matches, onClick = {
                                preferences.applyTimetableRecipe(recipe)
                                cardStyle = recipe.style
                                opacity.reload()
                                gridStyle = recipe.grid
                                fade = recipe.fade.toFloat()
                                blur = recipe.blur
                                onChange()
                            }, label = { Text(recipe.title) })
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("课块风格", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    AppearanceChoices(CourseCardStyle.entries, cardStyle, CourseCardStyle::title) {
                        cardStyle = it
                        preferences.courseCardStyle = it
                        onChange()
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("网格线样式", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    AppearanceChoices(TimetableGridStyle.entries, gridStyle, TimetableGridStyle::title) {
                        gridStyle = it
                        preferences.timetableGridStyle = it
                        onChange()
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("壁纸范围", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WallpaperScope.entries.forEach { candidate ->
                            PocketChoiceChip(selected = wallpaperScope == candidate, onClick = {
                                wallpaperScope = candidate
                                preferences.wallpaperScope = candidate
                                onChange()
                            }, label = { Text(candidate.title) })
                        }
                    }
                }
                if (wallpaper != null) {
                    Column {
                        TimetableOpacitySlider(opacity, cardStyle)
                        Text("壁纸淡化 ${fade.toInt()}%", fontSize = 13.sp, style = tabularTextStyle())
                        Slider(value = fade, onValueChange = { fade = it },
                            valueRange = WALLPAPER_FADE_RANGE.first.toFloat()..WALLPAPER_FADE_RANGE.last.toFloat(),
                            onValueChangeFinished = {
                                fade = fade.toInt().toFloat()
                                preferences.wallpaperFade = fade.toInt()
                                onChange()
                            })
                        if (timetableBlurSupported) {
                            Text("壁纸模糊 ${blur.roundToInt()} dp", fontSize = 13.sp, style = tabularTextStyle())
                            Slider(value = blur, onValueChange = { blur = it }, valueRange = 0f..16f, steps = 15,
                                onValueChangeFinished = { preferences.timetableWallpaperBlur = blur; onChange() })
                        }
                    }
                }
                WallpaperLibraryControls(wallpapers)
                Text("支持仅课程表或全局沉浸显示，图片仅存本机。每张最大 16MB，导入时自动压缩。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsSection {
            Text("启动时打开", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            HomeTab.entries.filter { preferences.studentTrack.isTabVisible(it.index) }.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { candidate ->
                        FilterChip(selected = home == candidate, shape = PocketShapes.Compact,
                            onClick = { home = candidate; preferences.homeTab = candidate },
                            label = { Text(candidate.title) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            Text("下次从图标打开时生效。小组件和通知仍直达对应日期。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
    if (editingColor) AlertDialog(
        onDismissRequest = { editingColor = false },
        title = { Text("自定义色号") },
        text = {
            OutlinedTextField(custom, onValueChange = { if (it.length <= 7) { custom = it; customError = false } },
                shape = PocketShapes.Control, label = { Text("Hex 色号") }, placeholder = { Text("#3155C6") },
                singleLine = true, isError = customError, modifier = Modifier.fillMaxWidth(),
                supportingText = { if (customError) Text("请输入六位十六进制色号，例如 #3155C6。") })
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = ThemeAccent.parse(custom)
                if (parsed == null) customError = true
                else {
                    setAccent(parsed)
                    editingColor = false
                }
            }) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = { editingColor = false }) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> AppearanceChoices(choices: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { candidate ->
            FilterChip(selected = candidate == selected, onClick = { onSelect(candidate) },
                shape = PocketShapes.Compact, label = { Text(label(candidate), style = tabularTextStyle()) })
        }
    }
}

@Composable
internal fun ThemeColorSwatches(
    colors: List<Int>, active: Int, label: String,
    onCustom: (() -> Unit)? = null, onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { candidate ->
            val chosen = active == candidate
            Surface(onClick = { onSelect(candidate) }, shape = CircleShape, color = Color(candidate or 0xFF000000.toInt()),
                modifier = Modifier.size(48.dp).semantics { contentDescription = "$label ${ThemeAccent.hex(candidate)}"; selected = chosen }) {
                Box(contentAlignment = Alignment.Center) {
                    if (chosen) Icon(Icons.Outlined.Check, null, tint = Color.White)
                }
            }
        }
        if (onCustom != null) {
            val scheme = MaterialTheme.colorScheme
            Surface(onClick = onCustom, color = scheme.surfaceContainerLow, shape = CircleShape,
                border = BorderStroke(0.8.dp, scheme.outlineVariant),
                modifier = Modifier.size(48.dp).semantics { contentDescription = "自定义色号" }) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable private fun EveningReminderControls(preferences: AppPreferences, revision: Int, onPermission: () -> Unit, onChange: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(preferences.eveningEnabled) }
    var time by remember { mutableStateOf(preferences.eveningTime) }
    var selectingTime by rememberSaveable { mutableStateOf(false) }
    val allowed = remember(revision) { EveningReminder.allowed(context) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("晚间明日课程提醒", Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(enabled, onCheckedChange = { enabled = it; preferences.eveningEnabled = it; onChange() })
    }
    Text("预告明天第一节课与课程数量；没有已排课程时送上一句问候，文案按天轮换。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    if (enabled) {
        OutlinedButton(onClick = { selectingTime = true }, shape = PocketShapes.Control, modifier = Modifier.fillMaxWidth()) { Text("提醒时间：${time.format(DateTimeFormatter.ofPattern("HH:mm"))}", style = tabularTextStyle()) }
        Text(if (allowed) "系统通知已允许" else "系统通知未允许，目前不会发送提醒", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        TextButton(onClick = onPermission) { Text(if (allowed) "打开系统通知设置" else "允许系统通知") }
    }
    if (enabled && selectingTime) TimeWheelSheet(time, onDismiss = { selectingTime = false }, onConfirm = { selected ->
        selectingTime = false
        if (selected != time) { time = selected; preferences.eveningTime = selected; onChange() }
    })
    Text("读取本机已保存课表，无需闹钟权限。没有课表时不发送；省电限制可能延迟，过夜不补发。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
}

@Composable internal fun QuickStartSteps() {
    val track = AppPreferences(LocalContext.current).studentTrack
    Text("三步上手", fontSize = 19.sp, fontWeight = FontWeight.Medium)
    listOf(
        "同步课表" to if (track == StudentTrack.GRADUATE) "登录教务账号并验证，就能一键同步课表和考试。" else "登录教务账号并验证，就能一键同步课表和考试。研究生记得先在“学校账号”切换培养类型。",
        "看课查考" to "“日程”盯今天，“课程表”左右滑看整周，考场和座位号都在“考试”页。",
        "课前提醒" to "怕忘课就开个课前提醒，再到“权限与后台”放行通知和准时提醒。",
    ).forEachIndexed { index, (title, detail) ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("${index + 1}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 13.sp, style = tabularTextStyle()) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable private fun HelpFeature(title: String, detail: String, icon: ImageVector) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}

@Composable private fun HelpAnswer(title: String, text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.animateContentSize(animationSpec = tween(180))) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth()) {
            Text(title, Modifier.weight(1f), fontSize = 13.sp); Text(if (expanded) "−" else "+")
        }
        if (expanded) Text(text, fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
