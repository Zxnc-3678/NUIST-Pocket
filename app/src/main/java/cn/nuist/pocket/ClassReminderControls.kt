package cn.nuist.pocket

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.nuist.pocket.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun ClassReminderControls(preferences: AppPreferences, revision: Int, notifications: () -> Unit, openPermissions: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(preferences.classRemindersEnabled) }
    var mode by remember { mutableStateOf(preferences.classQuietMode) }
    var lockDetails by remember { mutableStateOf(preferences.classLockscreenDetails) }
    var changed by remember { mutableIntStateOf(0) }
    var overview by remember { mutableStateOf<ClassReminderOverview?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun refresh() { changed++ }
    LaunchedEffect(revision, changed) {
        overview = withContext(Dispatchers.IO) {
            runCatching { ClassReminders.refresh(context); ClassReminders.overview(context) }
                .getOrElse { ClassReminderOverview(null, ClassReminderState(error = "预约配置无法读取，请检查系统声音状态")) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("课前20分钟提醒", Modifier.weight(1f), fontWeight = FontWeight.Medium, style = tabularTextStyle())
        Switch(enabled, onCheckedChange = { enabled = it; preferences.classRemindersEnabled = it; refresh() })
    }
    Text("显示课程、地点和开课倒计时，可在通知中预约本次静音或勿扰。只使用本机已保存的课表。", fontSize = 13.sp)
    if (enabled) {
        val exact = remember(revision, changed) { ClassReminders.preciseAllowed(context) }
        val canNotify = remember(revision, changed) { ClassReminders.notificationsAllowed(context) }
        if (!exact || !canNotify) {
            Text("提醒尚未准备好", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
            if (!exact && Build.VERSION.SDK_INT >= 31) {
                Text("还未允许“闹钟和提醒”。仅开启通知权限仍可能漏掉课前提醒：系统可延后任务，等到开课时提醒就失效了。", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error)
                OutlinedButton(shape = PocketShapes.Control, onClick = { classSystemSettings(context, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) },
                    modifier = Modifier.fillMaxWidth()) { Text("允许闹钟和提醒（准时发送）") }
            }
            if (!canNotify) TextButton(onClick = notifications) { Text("允许课前提醒通知") }
        }
        Text("预约生效范围：上课前1分钟 → 下课后1分钟。", fontWeight = FontWeight.Medium, fontSize = 13.sp, style = tabularTextStyle())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuietMode.entries.forEach { candidate ->
                FilterChip(selected = mode == candidate, shape = PocketShapes.Compact, onClick = { mode = candidate; preferences.classQuietMode = candidate; refresh() }, label = { Text(candidate.title) })
            }
        }
        Text("静音关闭来电与通知铃声，不改媒体音量。勿扰沿用系统允许的闹钟、联系人等例外。模式切换只影响之后新建的预约。", fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("锁屏显示课程和地点", Modifier.weight(1f), fontSize = 13.sp)
            Switch(lockDetails, onCheckedChange = { lockDetails = it; preferences.classLockscreenDetails = it; refresh() })
        }
        if (openPermissions != null) OutlinedButton(shape = PocketShapes.Control, onClick = openPermissions, modifier = Modifier.fillMaxWidth()) { Text("检查权限与后台设置") }
        else ClassPermissionControls(revision + changed, notifications)
        overview?.next?.let { event ->
            HorizontalDivider()
            Text("下一堂：${event.title}", fontWeight = FontWeight.Medium)
            Text("${event.date} · ${ClassReminders.clock(event.startsAt)}–${ClassReminders.clock(event.endsAt)} · ${event.room}", fontSize = 12.sp, style = tabularTextStyle())
            Text("课前提醒：${java.time.Instant.ofEpochMilli(event.remindAt).atZone(ClassZone).format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))}" +
                if (!exact) "（未允许准时发送，可能延迟）" else "", fontSize = 12.sp, style = tabularTextStyle())
            OutlinedButton(shape = PocketShapes.Control, onClick = {
                context.startActivity(Intent(context, ClassQuietSetupActivity::class.java).putExtra("class_key", event.key).putExtra("quiet_mode", mode.name))
            }, modifier = Modifier.fillMaxWidth()) { Text("预约这堂课${mode.title}") }
        }
        var showChecks by remember { mutableStateOf(false) }
        TextButton(onClick = { showChecks = !showChecks }) { Text(if (showChecks) "收起提醒测试与检查" else "提醒测试与检查") }
        if (showChecks) {
            OutlinedButton(shape = PocketShapes.Control, onClick = { scope.launch {
                val result = withContext(Dispatchers.IO) { ClassReminders.scheduleCheck(context) }
                message = result.message; refresh()
            } }, modifier = Modifier.fillMaxWidth()) { Text("1分钟后测试后台提醒", style = tabularTextStyle()) }
            val diagnostics = ClassReminderDiagnostics(context)
            fun stamp(at: Long) = if (at == 0L) "暂无记录" else java.time.Instant.ofEpochMilli(at).atZone(ClassZone)
                .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm:ss"))
            Text("最近后台触发：${stamp(diagnostics.receivedAt)}\n最近课程通知提交：${stamp(diagnostics.submittedAt)}\n最近测试通知提交：${stamp(diagnostics.testSubmittedAt)}", fontSize = 12.sp, style = tabularTextStyle())
            if (diagnostics.testAt > 0) Text("测试计划：${stamp(diagnostics.testAt)}", fontSize = 12.sp, style = tabularTextStyle())
            diagnostics.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Text("记录仅保存在手机；提交系统不等于一定弹出横幅，也请检查通知收纳和锁屏设置。", fontSize = 12.sp)
        }
    }
    val state = overview?.state
    state?.lease?.let { lease ->
        Text("当前${lease.mode.title}预约时段：至${ClassReminders.clock(lease.endsAt)}", fontWeight = FontWeight.Medium, style = tabularTextStyle())
    }
    state?.reservations?.take(8)?.forEach { saved ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(saved.title, fontSize = 13.sp)
                Text("${java.time.Instant.ofEpochMilli(saved.startsAt).atZone(ClassZone).toLocalDate()} · ${saved.mode.title} · ${ClassReminders.clock(saved.activatesAt)}–${ClassReminders.clock(saved.restoresAt)}", fontSize = 12.sp, style = tabularTextStyle())
            }
            TextButton(onClick = { scope.launch {
                val result = withContext(Dispatchers.IO) { ClassReminders.cancel(context, saved.key) }
                message = result.message; refresh()
            } }) { Text("取消") }
        }
    }
    if (state?.lease != null || !state?.reservations.isNullOrEmpty()) OutlinedButton(shape = PocketShapes.Control, onClick = {
        scope.launch {
            val result = withContext(Dispatchers.IO) { ClassReminders.cancelAll(context) }
            message = result.message; refresh()
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("取消全部预约并结束本次声音控制") }
    state?.error?.let { error ->
        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        TextButton(onClick = { classSystemSettings(context, Settings.ACTION_SOUND_SETTINGS) }) { Text("打开系统声音设置") }
    }
    message?.let { Text(it, fontSize = 12.sp) }
    Text("重叠或紧挨的同模式预约会连续执行。下课后保留原有静音或振动；你课中手动切换到响铃或振动时，不会强行改回。", fontSize = 12.sp)
    Text("需允许准时提醒与后台运行；强行停止应用、关机或系统省电限制可能影响执行。开启锁屏通知仍受系统的隐藏内容设置控制。", fontSize = 12.sp)
}
