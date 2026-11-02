package cn.nuist.pocket

import android.graphics.Bitmap
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.nuist.pocket.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal data class SettingsStatus(
    val connected: Boolean, val busy: Boolean, val importing: Boolean,
    val cacheLabel: String?, val hasImported: Boolean, val firstMonday: LocalDate, val preview: Boolean,
    val track: StudentTrack = StudentTrack.UNDERGRADUATE, val termCode: String? = null,
)
internal data class SettingsActions(
    val close: () -> Unit, val login: () -> Unit, val sync: () -> Unit, val logout: () -> Unit,
    val widget: () -> Unit, val chooseImport: () -> Unit, val calendar: () -> Unit,
    val clearImport: () -> Unit, val preview: () -> Unit, val schoolWeb: () -> Unit,
    val permission: () -> Unit, val reminder: () -> Unit, val appearance: () -> Unit,
)
private enum class SettingsPage(val title: String) {
    HOME("设置"), ACCOUNT("学校账号"), APPEARANCE("外观与启动"), REMINDERS("课程与提醒"),
    WIDGET("桌面小组件"), DATA("课表数据"), PERMISSIONS("权限与后台"), HELP("新手指引与帮助"),
}

@Composable
internal fun SettingsScreen(preferences: AppPreferences, status: SettingsStatus, actions: SettingsActions,
                            notificationRevision: Int, wallpaper: Bitmap?, onTrackChange: (StudentTrack) -> Unit) {
    val settingsContext = LocalContext.current
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    var backPage by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var showReleaseNotes by rememberSaveable { mutableStateOf(false) }
    var showMoreHelp by rememberSaveable { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun back() { if (page == SettingsPage.HOME) actions.close() else { page = backPage; backPage = SettingsPage.HOME } }
    fun openHelpSetting(target: SettingsPage) { backPage = SettingsPage.HELP; page = target }
    Dialog(onDismissRequest = actions.close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { back() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, if (page == SettingsPage.HOME) "关闭设置" else if (backPage == SettingsPage.HELP) "返回新手指引" else "返回设置")
                    }
                    Text(page.title, fontWeight = FontWeight.Bold, fontSize = 21.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.close) { Text("完成") }
                }
                AnimatedContent(targetState = page,
                    transitionSpec = { fadeIn(tween(170)) togetherWith fadeOut(tween(100)) }, label = "设置页面切换") { displayedPage ->
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (displayedPage == SettingsPage.HOME) {
                            item {
                                Text("按习惯安排你的校园口袋", color = muted, fontSize = 13.sp)
                            }
                            item { SettingsCard {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("培养类型", fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        StudentTrack.entries.forEach { choice ->
                                            FilterChip(selected = status.track == choice, enabled = !status.busy && !status.importing,
                                                onClick = { onTrackChange(choice) }, label = { Text(choice.title) }, modifier = Modifier.weight(1f))
                                        }
                                    }
                                    Text("各自保留课表、考试和导入数据；切换后重新验证连接。研究生模式隐藏教室入口，待办与外观设置共用。", color = muted, fontSize = 12.sp)
                                }
                            } }
                            item { SettingsCard {
                                SettingsEntry("学校账号", if (status.connected) "已连接 · 同步课表与考试" else if (status.cacheLabel != null) "连接待验证 · 已保留缓存" else "登录与同步", Icons.Outlined.Person) { page = SettingsPage.ACCOUNT }
                            } }
                            item { SettingsCard {
                                SettingsEntry("外观与启动", "主题色、课表壁纸、默认打开页面", Icons.Outlined.Edit) { page = SettingsPage.APPEARANCE }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                                SettingsEntry("课程与提醒", "课前提醒、静音预约与晚间安排", Icons.Outlined.Notifications) { page = SettingsPage.REMINDERS }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                                SettingsEntry("权限与后台", "通知、准时提醒、声音与锁屏设置", Icons.Outlined.Settings) { page = SettingsPage.PERMISSIONS }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                                SettingsEntry("桌面小组件", "背景图片、纯色与待办展示", Icons.Outlined.DateRange) { page = SettingsPage.WIDGET }
                            } }
                            item { SettingsCard {
                                SettingsEntry("课表数据", "手动导入、学期起始日与演示", Icons.AutoMirrored.Outlined.List) { page = SettingsPage.DATA }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                                SettingsEntry("面对面分享课表", "扫码分享或查看收到的独立课表", Icons.Outlined.Share) { settingsContext.startActivity(Intent(settingsContext, ShareTimetableActivity::class.java)) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                                SettingsEntry("新手指引与帮助", "入门步骤、功能用法与常见问题", Icons.Outlined.Info) { page = SettingsPage.HELP }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                                SettingsEntry("本次更新", "v${BuildConfig.VERSION_NAME} · 更新小提示", Icons.Outlined.Info) { showReleaseNotes = true }
                            } }
                            item {
                                Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("NUIST Pocket ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = muted)
                                    Text("作者：仲夏南城  ·  开发协作：ChatGPT", fontSize = 12.sp, color = muted)
                                    Text("非学校官方应用 · ${status.track.title} / 校本部", fontSize = 11.sp, color = muted)
                                }
                            }
                        } else if (displayedPage == SettingsPage.HELP) {
                            item { SettingsCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                QuickStartSteps()
                                OutlinedButton(onClick = { openHelpSetting(SettingsPage.ACCOUNT) }, modifier = Modifier.fillMaxWidth()) { Text("打开学校账号设置") }
                                TextButton(onClick = { openHelpSetting(SettingsPage.PERMISSIONS) }, modifier = Modifier.fillMaxWidth()) { Text("检查提醒权限") }
                                Text("还没准备好登录？可以先看演示；待办无需登录。", color = muted, fontSize = 12.sp, lineHeight = 18.sp)
                            } } }
                            item { SettingsCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("各个页面怎么用", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                HelpFeature("日程", "看每天课程、停课日期和待办。点周次可跳周。", Icons.Outlined.Home)
                                HelpFeature("课程表", "左右滑动换周；点课程查看完整时间、地点与教师。", Icons.Outlined.DateRange)
                                if (status.track.supportsRooms) HelpFeature("教室", "选择楼栋、日期和节次查询占用。", Icons.Outlined.Place)
                                HelpFeature("考试", "筛选考试，查看考场、座位和详情。", Icons.AutoMirrored.Outlined.List)
                            } } }
                            item { SettingsCard {
                                Text("按习惯设置", Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 4.dp), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                SettingsEntry("外观与启动", "选择主题、课表壁纸和默认页面", Icons.Outlined.Edit) { openHelpSetting(SettingsPage.APPEARANCE) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                SettingsEntry("课程与提醒", "课前20分钟、静音预约与晚间提醒", Icons.Outlined.Notifications) { openHelpSetting(SettingsPage.REMINDERS) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                SettingsEntry("桌面小组件", "添加到桌面，设置独立背景与待办", Icons.Outlined.DateRange) { openHelpSetting(SettingsPage.WIDGET) }
                            } }
                            item { SettingsCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("常见问题", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                HelpAnswer("学校登录到期怎么办？", "先到“学校账号”尝试恢复连接并同步。学校会话确已到期时，需要在学校原版页面重新认证，再点顶部“验证登录”。已保存的课表和考试仍可离线查看，小组件也继续使用缓存。重新登录会清除上一账号的学校缓存，个人待办保留。")
                                HelpAnswer("如何切换周次、返回本周？", "课程表左右滑动切周，点周次或左侧日历可直接选周、返回本周；自己的课表还可在选周弹窗调整显示。收到的课表用右侧齿轮调整非本周课程。日程用两侧箭头或点周次跳转，回到今天可返回当前日期。")
                                HelpAnswer("临时停课怎么设置？", "在日程选中日期，点日期右侧的“当天操作”菜单设置停课；课程表、日程、小组件和课前提醒会一起跳过这一天。恢复时回到同一天，在菜单中选择“恢复当天课程与提醒”。")
                                HelpAnswer("怎样分享或接收课表？", "在设置点“面对面分享课表”，可出示二维码或通过系统分享课表文件；对方扫码或导入文件。分享生成后30分钟内可接收。收到的课表独立查看，可改名称或删除，不会加入自己的提醒。")
                                HelpAnswer("长课名会被缩写吗？", "不会。课程表使用学校数据中的完整课程名，自动换行，并按完整文字所需空间统一调整节次高度，不使用简称或课名省略号。小屏、大字号或特别长的课名可能需要上下滚动。时间重叠的其他课程仍通过“另几项”入口查看，点课程可看完整安排。")
                                HelpAnswer("课表背景和卡片底色在哪里调？", "右上角设置 → 外观与启动可选择课程表壁纸，壁纸只铺在课程区域。点课程表顶部周次可调整“课程卡片底色”，80%–98%，越高文字越清楚；它与壁纸淡化分别控制。桌面小组件使用独立背景，仍在小组件设置中调整。")
                                HelpAnswer("课前提醒怎么开启？", "在“课程与提醒”开启课前提醒，再到“权限与后台”检查通知、锁屏与准时提醒。开课前20分钟发出通知，可按次预约静音或勿扰；课前1分钟生效，下课后1分钟解除。")
                                HelpAnswer("如何切换深色模式？", "App跟随手机系统的浅色或深色设置，页面、课程表和弹窗自动调整。桌面小组件继续使用单独选择的背景；学校网页的颜色由学校页面决定。")
                                if (showMoreHelp) {
                                HelpAnswer("顶部没有应用名称了，设置在哪？", "所有页面统一使用单层标题栏，设置始终在右上角，日程标题旁保留待办入口。本科生模式显示日程、课程表、教室和考试；研究生模式隐藏教室，其他页面保持相同风格。")
                                HelpAnswer("本科生和研究生怎么切换？", "在设置首页选择培养类型。两边分别保存课表、考试与导入数据，切换后需验证学校连接；待办、主题和壁纸共用。小组件和晚间提醒使用当前类型的本机课表。研究生与本科生共用已核对的学校校历和作息，未核对的新学期可到“课表数据”设置第1周周一，也可手动调整已知学期。")
                                HelpAnswer("待办怎么用，会发提醒吗？", "在日程标题旁点“待办”添加或管理。可以按日期查看或切到“全部未完成”，已完成事项可显示或隐藏；具体时间通过小时、分钟滚轮选择，取消不修改。待办时间仅用于展示和排序，目前不推送通知。无需学校登录，退出学校账号后仍保留。")
                                HelpAnswer("课前提醒与声音预约怎么用？", "在“课程与提醒”开启课前提醒，并允许系统通知、锁屏显示和准时提醒。每堂课提前20分钟通知，收起时可点右侧“预约静音”或“预约勿扰”，只预约本次课程；点通知正文则进入确认页，不会直接预约。课前1分钟生效，下课后1分钟结束。声音预约还需系统声音与勿扰授权。未预约的提醒保留到上课，也可划掉；划掉不取消已建立的预约。")
                                HelpAnswer("预约通知被收到“更多通知”怎么办？", "部分系统会额外归类自定义通知。若被收纳，可在系统通知栏手动将这类通知移出；本机检查中，移出后新通知继续留在普通区，但不同系统或重新安装后可能需要再设置。应用不能强制覆盖系统归类。即使按钮被系统隐藏，也可点通知正文进入预约确认页。")
                                HelpAnswer("下课后会恢复什么状态？", "静音预约在生效时记住原有响铃、振动或静音，结束时恢复；若已手动改成响铃或振动，则保留手动状态。勿扰预约仅结束本应用的上课规则，不关闭其他勿扰规则，允许哪些来电或闹钟由系统勿扰设置决定。重叠的同模式预约合并到最后一堂课下课后1分钟。系统省电、强行停止或撤销权限可能影响准时执行，异常时会提示检查系统声音。")
                                HelpAnswer("锁屏提醒和灵动岛支持吗？", "支持安卓标准通知与锁屏提示，可在“课程与提醒”选择是否显示课程详情。“课前20分钟提醒”和“课程声音预约状态”需分别允许系统锁屏显示，可在“权限与后台”检查。厂商的原生实况或岛样式尚未接入。")
                                HelpAnswer("晚间提醒为什么没收到？", "先确认已保存课表，再到“课程与提醒”开启晚间提醒并允许系统通知。默认21:00，可滚动选择其他时间；点“确定”才保存。系统省电可能使通知延迟，过夜不补发。个人待办不使用这项提醒。")
                                HelpAnswer("小组件为什么没有最新课程？", "小组件只读取本机已保存的课表和待办。请先在App同步课表，再点小组件的“刷新”；省电限制也可能使自动换课延迟。今天课程结束后，小组件会预览明天。")
                                HelpAnswer("如何进入或退出演示？", "首次使用可点“先看看演示”；演示课程和考试均为虚构数据。未保存真实课表时，可在“课表数据”中切换演示或返回登录引导，也可进入“学校账号”登录并同步自己的安排。")
                                HelpAnswer("缓存会越用越多吗？", "课表和考试只保留最近一次成功同步的数据，失败时保留旧数据；教室查询只替换当前结果，不保存历史。壁纸各保留一张，正常导入会立即删除临时文件；异常中断的残留在启动时按需清理，仅处理超过24小时且未使用的图片临时文件。进出学校登录页时清理网页资源缓存，不清登录会话或网页存储。清理失败会在下次启动或打开学校页时重试，个人待办、已保存课表、考试和壁纸保留。")
                                HelpAnswer("更新内容在哪里看？", "覆盖升级后首次进入主界面会显示一次更新小提示，关闭后不再自动弹出；首次安装直接使用新手指引。以后可在“设置 → 本次更新”重看，无需打开外部网页。")
                                HelpAnswer("我的数据保存在哪里？", "登录会话、课表、考试、个人待办和壁纸仅保存在本机，App无自建中转服务，不自行保存账号密码或验证码。退出学校账号会清除学校与导入课表数据，保留待办、主题、壁纸和提醒偏好；卸载会清除本机数据。")
                                }
                                TextButton(onClick = { showMoreHelp = !showMoreHelp }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (showMoreHelp) "收起更多问题" else "查看更多问题")
                                }
                            } } }
                        } else {
                            item { SettingsCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                when (displayedPage) {
                                    SettingsPage.ACCOUNT -> {
                                        Text(if (status.connected) "学校账号已连接" else if (status.cacheLabel != null) "学校连接待验证" else "连接学校账号", fontWeight = FontWeight.Bold)
                                        Text("在学校原版页面完成登录，再点“验证登录”。课表与考试会保存到本机。", color = muted, fontSize = 13.sp)
                                        status.cacheLabel?.let { Text(it, color = muted, fontSize = 12.sp) }
                                        Button(enabled = !status.busy && !status.importing && (status.connected || status.cacheLabel != null), onClick = actions.sync, modifier = Modifier.fillMaxWidth()) {
                                            Text(if (status.busy) "正在同步…" else if (!status.connected && status.cacheLabel != null) "恢复连接并同步" else "同步课表和考试")
                                        }
                                        if (!status.connected && status.cacheLabel == null) Text("请先登录学校账号再同步。", fontSize = 12.sp, color = muted)
                                        OutlinedButton(enabled = !status.busy && !status.importing, onClick = actions.login, modifier = Modifier.fillMaxWidth()) { Text(if (status.connected || status.cacheLabel != null) "重新登录学校" else "登录学校账号") }
                                        Text("重新登录会清除上一账号的学校缓存。会话到期后的处理和数据说明，可在“新手指引与帮助”查看。", color = muted, fontSize = 12.sp)
                                        TextButton(onClick = actions.schoolWeb, modifier = Modifier.fillMaxWidth()) { Text("打开学校教务网页") }
                                        TextButton(enabled = !status.busy && !status.importing, onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("退出登录并清除学校与导入数据", color = MaterialTheme.colorScheme.error) }
                                    }
                                    SettingsPage.APPEARANCE -> AppearanceControls(preferences, wallpaper, actions.appearance)
                                    SettingsPage.REMINDERS -> ReminderControls(preferences, notificationRevision, actions.permission, actions.reminder) { backPage = SettingsPage.REMINDERS; page = SettingsPage.PERMISSIONS }
                                    SettingsPage.PERMISSIONS -> PermissionChecklist(notificationRevision, actions.permission)
                                    SettingsPage.WIDGET -> {
                                        Text("今日课表，放到桌面上", fontWeight = FontWeight.Bold)
                                        Text("显示尚未结束的课程，课前15分钟提示即将上课，上课中显示倒计时；今天上完后预览明天。", color = muted, fontSize = 13.sp)
                                        Button(onClick = actions.widget, modifier = Modifier.fillMaxWidth()) { Text("添加今日课表到桌面") }
                                        Text("也可以长按桌面空白处，在小组件中选择NUIST Pocket。添加后长按小组件，拖动边框调整大小。", fontSize = 13.sp, color = muted)
                                        HorizontalDivider()
                                        Text("自动换课可能受系统省电影响延迟，点“刷新”可校正。刷新只重读本机课表；最新课程请在App里同步。", fontSize = 12.sp, color = muted)
                                        Text("无需“闹钟和提醒”权限。", fontSize = 12.sp, color = muted)
                                        HorizontalDivider()
                                        WidgetControls()
                                    }
                                    SettingsPage.DATA -> {
                                        if (status.track == StudentTrack.GRADUATE) {
                                            Text("研究生校历与作息", fontWeight = FontWeight.Bold)
                                            Text("采用已核对的学校校历和校本部作息。尚未核对的新学期需设置第1周周一；如个人安排不同，也可手动调整。", color = muted, fontSize = 13.sp)
                                            Text(status.termCode?.let { "当前学期：$it" } ?: "请先验证研究生学校登录", fontSize = 13.sp)
                                            val confirmedDate = status.termCode?.let { graduateFirstMonday(it, preferences.graduateMonday(it)) }
                                            OutlinedButton(enabled = status.termCode != null && !status.busy && !status.importing,
                                                onClick = actions.calendar, modifier = Modifier.fillMaxWidth()) {
                                                Text(confirmedDate?.let { "研究生第1周周一：$it" } ?: "选择研究生第1周周一")
                                            }
                                            val periods = remember { PocketStore(settingsContext, StudentTrack.GRADUATE).periods }
                                            Text(periods.filter { it.enabled }.joinToString("  ") { "${it.number}节 ${it.start}–${it.end}" }, color = muted, fontSize = 12.sp)
                                            OutlinedButton(enabled = status.termCode != null && !status.busy && !status.importing && confirmedDate != null,
                                                onClick = actions.sync, modifier = Modifier.fillMaxWidth()) { Text("同步研究生课表与考试") }
                                            HorizontalDivider()
                                        }
                                        Text("手动导入课表", fontWeight = FontWeight.Bold)
                                        Text("已登录的同学优先使用学校同步。手动导入适用于保存好的课表接口JSON文件。", color = muted, fontSize = 13.sp)
                                        OutlinedButton(enabled = !status.importing && !status.busy, onClick = actions.chooseImport, modifier = Modifier.fillMaxWidth()) { Text(if (status.importing) "正在导入…" else "选择课表JSON文件") }
                                        Text("支持cxxszhxqkb完整响应，最大1MB，仅保留课程必需字段。导入会切换到本机课表，不混用学校缓存。", color = muted, fontSize = 12.sp)
                                        if (status.track == StudentTrack.UNDERGRADUATE) {
                                            OutlinedButton(enabled = !status.importing && !status.busy, onClick = actions.calendar, modifier = Modifier.fillMaxWidth()) { Text("第一教学周周一：${status.firstMonday}") }
                                            Text("仅影响导入课表；切换学期后请核对日期。", color = muted, fontSize = 12.sp)
                                        } else Text("研究生学期起点可在本页上方调整。", color = muted, fontSize = 12.sp)
                                        if (status.hasImported) TextButton(enabled = !status.importing && !status.busy, onClick = actions.clearImport) { Text("仅清除导入课表") }
                                        if (status.cacheLabel == null && !status.hasImported) TextButton(enabled = !status.importing && !status.busy, onClick = actions.preview) { Text(if (status.preview) "返回登录引导" else "预览演示数据") }
                                    }
                                    SettingsPage.HELP -> Unit
                                    SettingsPage.HOME -> Unit
                                }
                            } } }
                        }
                    }
                }
            }
        }
        if (showReleaseNotes) ReleaseHighlights { showReleaseNotes = false }
        if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("退出并清除本机数据？") },
            text = { Text("将清除学校登录会话、课表、考试和导入课表。个人待办、主题、壁纸与提醒偏好保留。") },
            confirmButton = { TextButton(onClick = { confirmClear = false; actions.logout() }) { Text("退出并清除") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    }
}

@Composable private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp)) { Column(content = content) }
}

@Composable private fun SettingsEntry(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, null, Modifier.padding(10.dp).size(21.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(summary, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
        }
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 22.sp)
    }
}

@Composable private fun AppearanceControls(preferences: AppPreferences, wallpaper: Bitmap?, onChange: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { WallpaperStore(context) }
    var home by remember { mutableStateOf(preferences.homeTab) }
    var accent by remember { mutableIntStateOf(preferences.themeAccent) }
    var custom by rememberSaveable { mutableStateOf(ThemeAccent.hex(accent)) }
    var customError by remember { mutableStateOf(false) }
    var fade by remember { mutableFloatStateOf(preferences.wallpaperFade.toFloat()) }
    var saving by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            saving = true; imageError = null
            try {
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: error("无法读取图片")
                    store.import(input)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { imageError = if (error is IllegalArgumentException) error.message else "图片无法读取，请换一张图片重试。原壁纸未被替换。" }
            finally { saving = false; onChange() }
        }
    }
    fun setAccent(value: Int) {
        val normalized = ThemeAccent.readable(value)
        if (normalized != accent) {
            preferences.themeAccent = normalized; accent = normalized; onChange()
        }
        custom = ThemeAccent.hex(accent); customError = false
    }
    Text("主题颜色", fontWeight = FontWeight.Bold)
    Text("浅色与深色跟随手机系统切换；深色模式会调整主题色亮度，保持文字清楚。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ThemeAccent.presets.forEach { candidate ->
            val selected = accent == candidate
            Surface(onClick = { setAccent(candidate) }, shape = CircleShape, color = Color(0xFF000000 or candidate.toLong()), modifier = Modifier.size(44.dp).semantics { contentDescription = "主题色 ${ThemeAccent.hex(candidate)}"; this.selected = selected }) {
                Box(contentAlignment = Alignment.Center) { if (selected) Icon(Icons.Outlined.Check, null, tint = Color.White) }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(custom, onValueChange = { if (it.length <= 7) { custom = it; customError = false } },
            label = { Text("自定义色号") }, placeholder = { Text("#3155C6") }, singleLine = true, isError = customError, modifier = Modifier.weight(1f))
        TextButton(onClick = { val parsed = ThemeAccent.parse(custom); if (parsed == null) customError = true else setAccent(parsed) }) { Text("应用") }
    }
    Text(if (customError) "请输入六位十六进制色号，例如#3155C6。" else "过浅的颜色会自动加深，保证按钮文字清晰。主题色即时生效。", fontSize = 12.sp, color = if (customError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { setAccent(ThemeAccent.DEFAULT) }) { Text("恢复默认主题色") }
    HorizontalDivider()
    Text("课程表壁纸", fontWeight = FontWeight.Bold)
    if (wallpaper != null) {
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            Image(wallpaper.asImageBitmap(), "当前课程表壁纸预览", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = fade / 100f)))
            Surface(Modifier.align(Alignment.Center).padding(16.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f), shape = RoundedCornerShape(12.dp)) {
                Text("课程表壁纸预览", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    OutlinedButton(enabled = !saving, onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "正在处理图片…" else if (wallpaper == null) "从手机选择图片" else "更换壁纸") }
    imageError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    if (wallpaper != null) {
        Text("壁纸淡化 ${fade.toInt()}%", fontSize = 13.sp)
        Slider(value = fade, onValueChange = { fade = it }, valueRange = 20f..90f,
            onValueChangeFinished = { preferences.wallpaperFade = fade.toInt(); onChange() })
        TextButton(enabled = !saving, onClick = { store.clear(); onChange() }) { Text("移除壁纸") }
    }
    Text("图片仅保存在本机，用于独立“课程表”页。支持常见图片格式，最大16MB；会自动缩小，避免影响滑动。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider()
    Text("启动时打开", fontWeight = FontWeight.Bold)
    HomeTab.entries.filter { preferences.studentTrack.isTabVisible(it.index) }.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { candidate ->
            FilterChip(selected = home == candidate, onClick = { home = candidate; preferences.homeTab = candidate }, label = { Text(candidate.title) }, modifier = Modifier.weight(1f))
        } }
    }
    Text("下次从App图标打开时生效。点小组件或通知仍直接打开对应日期。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
}

@Composable private fun ReminderControls(preferences: AppPreferences, revision: Int, onPermission: () -> Unit, onChange: () -> Unit, openPermissions: () -> Unit) {
    ClassReminderControls(preferences, revision, onPermission, openPermissions)
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(preferences.eveningEnabled) }
    var time by remember { mutableStateOf(preferences.eveningTime) }
    var selectingTime by rememberSaveable { mutableStateOf(false) }
    val allowed = remember(revision) { EveningReminder.allowed(context) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("晚间明日课程提醒", Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Switch(enabled, onCheckedChange = { enabled = it; preferences.eveningEnabled = it; onChange() })
    }
    Text("预告明天第一节课与课程数量；没有已排课程时送上一句问候，文案按天轮换。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    if (enabled) {
        OutlinedButton(onClick = { selectingTime = true }, modifier = Modifier.fillMaxWidth()) { Text("提醒时间：${time.format(DateTimeFormatter.ofPattern("HH:mm"))}") }
        Text(if (allowed) "系统通知已允许" else "系统通知未允许，目前不会发送提醒", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        TextButton(onClick = onPermission) { Text(if (allowed) "打开系统通知设置" else "允许系统通知") }
    }
    if (enabled && selectingTime) TimeWheelDialog(time, onDismiss = { selectingTime = false }, onConfirm = { selected ->
        selectingTime = false
        if (selected != time) { time = selected; preferences.eveningTime = selected; onChange() }
    })
    Text("读取本机已保存课表，无需闹钟权限。没有课表时不发送；省电限制可能延迟，过夜不补发。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
}

@Composable internal fun QuickStartSteps() {
    val track = AppPreferences(LocalContext.current).studentTrack
    Text("开始使用", fontSize = 19.sp, fontWeight = FontWeight.Bold)
    listOf(
        "连接学校" to if (track == StudentTrack.GRADUATE) "研究生模式已选。登录学校页面并验证，再同步课表和考试。" else "研究生先在设置切换模式；登录学校页面并验证，再同步。",
        "查看安排" to "日程看当天，课表左右滑动看整周；考试在底部导航。",
        "按需提醒" to "在设置开启课前提醒，并到“权限与后台”完成授权。",
    ).forEachIndexed { index, (title, detail) ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("${index + 1}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
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
