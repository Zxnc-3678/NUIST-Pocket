package cn.nuist.pocket

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.nuist.pocket.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Missing-grant actions open this Activity directly; there is no notification trampoline. */
class ClassQuietSetupActivity : ComponentActivity() {
    private var permissionRevision by mutableIntStateOf(0)
    override fun onResume() {
        super.onResume(); permissionRevision++
        ClassReminders.requestRefresh(this)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val requestedKey = intent.getStringExtra("class_key")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        val requestedMode = QuietMode.entries.find { it.name == intent.getStringExtra("quiet_mode") }
            ?: AppPreferences(this).classQuietMode
        setContent {
            val preferences = remember { AppPreferences(this) }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision++ }
            fun requestNotifications() {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else classSystemSettings(this, Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            }
            PocketTheme(preferences.themeAccent) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
                        .padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("课程提醒与声音预约", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        if (requestedKey == null) ClassReminderControls(preferences, permissionRevision, ::requestNotifications)
                        else {
                            var event by remember { mutableStateOf<ClassEvent?>(null) }
                            var loaded by remember { mutableStateOf(false) }
                            var outcome by rememberSaveable { mutableStateOf<String?>(null) }
                            var completed by rememberSaveable { mutableStateOf(false) }
                            var requested by rememberSaveable { mutableStateOf(intent.getBooleanExtra("auto_reserve", true)) }
                            LaunchedEffect(requestedKey, permissionRevision) {
                                event = withContext(Dispatchers.IO) { ClassReminders.event(this@ClassQuietSetupActivity, requestedKey) }
                                loaded = true
                            }
                            val exact = remember(permissionRevision) { ClassReminders.preciseAllowed(this@ClassQuietSetupActivity) }
                            val policy = remember(permissionRevision) { ClassReminders.policyAllowed(this@ClassQuietSetupActivity) }
                            val notifications = remember(permissionRevision) { ClassReminders.notificationsAllowed(this@ClassQuietSetupActivity) }
                            event?.let { course ->
                                Text(course.title, fontWeight = FontWeight.Medium, fontSize = 18.sp)
                                Text("${course.date} · ${ClassReminders.clock(course.startsAt)}–${ClassReminders.clock(course.endsAt)}\n${course.room}")
                                Text("预约${requestedMode.title}：${ClassReminders.clock(course.startsAt - CLASS_QUIET_MARGIN_MS)}生效，${ClassReminders.clock(course.endsAt + CLASS_QUIET_MARGIN_MS)}结束。")
                                Text(if (requested) "授权完成后，会自动预约这堂课；你可随时从通知或设置取消。"
                                    else "确认预约后，才会在上课前切换声音。", style = MaterialTheme.typography.bodySmall)
                            }
                            if (loaded && event == null) Text("课程或培养类型已变化，本次通知已失效，请使用最新提醒。")
                            if (!preferences.classRemindersEnabled) Text("请先在应用设置开启课前提醒。")
                            if (!requested && event != null && preferences.classRemindersEnabled) Button(onClick = { requested = true }) {
                                Text("预约${requestedMode.title}")
                            }
                            ClassPermissionControls(permissionRevision, ::requestNotifications)
                            LaunchedEffect(event?.key, exact, policy, notifications, requested) {
                                if (requested && event != null && exact && policy && notifications && preferences.classRemindersEnabled && !completed) {
                                    val result = withContext(Dispatchers.IO) { ClassReminders.reserve(this@ClassQuietSetupActivity, requestedKey, requestedMode) }
                                    outcome = result.message; completed = result.success
                                }
                            }
                            LaunchedEffect(completed) { if (completed) { delay(900); finish() } }
                            outcome?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        }
                        TextButton(onClick = { finish() }) { Text("返回") }
                    }
                }
            }
        }
    }
}

internal fun classSystemSettings(context: Context, action: String, channel: String? = null) {
    val intent = when (action) {
        Settings.ACTION_APP_NOTIFICATION_SETTINGS -> Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS -> Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM -> Intent(action, Uri.parse("package:${context.packageName}"))
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS -> Intent(action, Uri.parse("package:${context.packageName}"))
        else -> Intent(action)
    }
    if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
}

@Composable internal fun ClassPermissionControls(revision: Int, notifications: () -> Unit) {
    val context = LocalContext.current
    val canNotify = remember(revision) { ClassReminders.notificationsAllowed(context) }
    val exact = remember(revision) { ClassReminders.preciseAllowed(context) }
    val policy = remember(revision) { ClassReminders.policyAllowed(context) }
    Text("1 · 通知", fontWeight = FontWeight.Bold)
    Text(if (canNotify) "系统通知与课前通道：已允许" else "未允许或课前通道已关闭；晚间提醒也需要通知权限。", fontSize = 12.sp)
    OutlinedButton(onClick = notifications, modifier = Modifier.fillMaxWidth()) { Text(if (canNotify) "检查系统通知与锁屏设置" else "允许系统通知") }
    if (canNotify) {
        Text("课前提醒和预约生效后的状态是两项通知，请分别允许锁屏显示。", fontSize = 12.sp)
        TextButton(onClick = { classSystemSettings(context, Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, ClassReminders.ALERT_CHANNEL) }) {
            Text("课前提醒的锁屏设置")
        }
        TextButton(onClick = { classSystemSettings(context, Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, ClassReminders.STATUS_CHANNEL) }) {
            Text("预约状态的锁屏设置")
        }
        Text("若“在锁定屏幕上”设为“完全不显示通知”，请改为显示通知；是否显示课程内容可在应用内选择。", fontSize = 12.sp)
    }
    HorizontalDivider()
    Text("2 · 闹钟和提醒", fontWeight = FontWeight.Bold)
    Text(if (exact) "准时提醒：已允许或系统无需单独授权" else "未允许；课前提醒可能延迟，声音预约也无法准时执行。", fontSize = 12.sp)
    if (!exact && Build.VERSION.SDK_INT >= 31) OutlinedButton(
        onClick = { classSystemSettings(context, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }, modifier = Modifier.fillMaxWidth()
    ) { Text("允许准时提醒") }
    HorizontalDivider()
    Text("3 · 声音与勿扰控制", fontWeight = FontWeight.Bold)
    Text(if (policy) "已允许" else "未允许；只在预约静音或勿扰时需要，普通课前提醒不需要。", fontSize = 12.sp)
    OutlinedButton(onClick = { classSystemSettings(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (policy) "检查声音与勿扰授权" else "允许声音与勿扰控制")
    }
    if (policy) TextButton(onClick = { classSystemSettings(context, Settings.ACTION_SOUND_SETTINGS) }) {
        Text("检查系统声音与勿扰规则")
    }
}

@Composable internal fun PermissionChecklist(revision: Int, notifications: () -> Unit) {
    val context = LocalContext.current
    Text("安装后可在这里依次检查", fontWeight = FontWeight.Bold, fontSize = 17.sp)
    Text("安卓的系统页面需要逐项确认；不同手机名称和位置可能不同。开启提醒后仍可随时回来检查。", fontSize = 13.sp)
    ClassPermissionControls(revision, notifications)
    HorizontalDivider()
    Text("4 · 后台与省电", fontWeight = FontWeight.Bold)
    Text("如提醒不稳定，请在应用信息中检查电池、后台运行或自启动限制，并按手机系统提示设置。系统未提供统一的状态判断，请在系统页面确认。", fontSize = 12.sp)
    OutlinedButton(onClick = { classSystemSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text("打开应用信息") }
    Text("锁屏显示需在上方两类通知中分别检查；如果通知被系统收纳，可在通知栏调整。状态显示不代表锁屏或后台设置已自动完成。", fontSize = 12.sp)
}
