package cn.nuist.pocket

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ConditionProviderService
import cn.nuist.pocket.data.QuietDevice

/** Controls only this app's DND rule. Other manual/scheduled DND rules are never switched off. */
internal class ClassQuietDevice(private val context: Context, private val store: ClassReminderStore) : QuietDevice {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val owner = ComponentName(context, ClassQuietConditionService::class.java)
    private val condition = Uri.parse("condition://${context.packageName}/class-quiet")
    override val hasPolicyAccess: Boolean get() = notifications.isNotificationPolicyAccessGranted
    override fun ringerMode(): Int = audio.ringerMode
    override fun setRingerMode(mode: Int) { audio.ringerMode = mode }
    override fun ensureRule(): String {
        check(hasPolicyAccess) { "请先允许声音与勿扰控制" }
        val existing = store.ruleId?.let { id -> notifications.getAutomaticZenRule(id)?.let { id to it } }
            ?: notifications.automaticZenRules.entries.firstOrNull { it.value.owner == owner && it.value.conditionId == condition }
                ?.let { it.key to it.value }
        if (existing != null) {
            check(existing.second.owner == owner && existing.second.conditionId == condition) { "上课勿扰规则已变化，请检查系统规则" }
            check(existing.second.isEnabled) { "请在系统勿扰设置启用上课勿扰规则" }
            store.ruleId = existing.first
            return existing.first
        }
        @Suppress("DEPRECATION") // The owner-based constructor supports every supported version, Android11+.
        val rule = AutomaticZenRule("NUIST Pocket 上课勿扰", owner, condition,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
        val id = checkNotNull(notifications.addAutomaticZenRule(rule)) { "系统未能创建上课勿扰规则" }
        try { store.ruleId = id } catch (e: Exception) { notifications.removeAutomaticZenRule(id); throw e }
        return id
    }
    override fun setRuleActive(id: String, active: Boolean, fromUser: Boolean) {
        val rule = notifications.getAutomaticZenRule(id)
        if (rule == null && !active) return // User deleted the app-owned rule; there is nothing left to deactivate.
        check(rule != null && rule.owner == owner && rule.conditionId == condition) { "上课勿扰规则已变化，请重新预约" }
        if (active) check(rule.isEnabled) { "请在系统勿扰设置启用上课勿扰规则" }
        val state = if (active) Condition.STATE_TRUE else Condition.STATE_FALSE
        val update = if (Build.VERSION.SDK_INT >= 35)
            Condition(condition, "NUIST Pocket 上课预约", state,
                if (fromUser) Condition.SOURCE_USER_ACTION else Condition.SOURCE_SCHEDULE)
        else Condition(condition, "NUIST Pocket 上课预约", state)
        notifications.setAutomaticZenRuleState(id, update)
    }
}

class ClassQuietConditionService : ConditionProviderService() {
    override fun onConnected() { ClassReminders.requestRefresh(this) }
    override fun onSubscribe(conditionId: Uri?) { ClassReminders.requestRefresh(this) }
    override fun onUnsubscribe(conditionId: Uri?) { /* Respect rule removal/disable in system settings. */ }
}
