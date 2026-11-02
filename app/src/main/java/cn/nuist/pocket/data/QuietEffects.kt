package cn.nuist.pocket.data

internal const val RINGER_SILENT = 0
internal const val RINGER_VIBRATE = 1
internal const val RINGER_NORMAL = 2

/** Write-ahead ownership record survives process death between a system change and receiver completion. */
internal data class QuietLease(val mode: QuietMode, val endsAt: Long, val previousRinger: Int? = null,
    val ruleId: String? = null, val id: String = java.util.UUID.randomUUID().toString())
internal interface QuietDevice {
    val hasPolicyAccess: Boolean
    fun ringerMode(): Int
    fun setRingerMode(mode: Int)
    fun ensureRule(): String
    fun setRuleActive(id: String, active: Boolean, fromUser: Boolean = false)
}
internal enum class QuietRestore(val message: String) {
    NORMAL("已恢复响铃"), VIBRATE("已恢复振动"),
    UNCHANGED("已保留原有静音"), MANUAL("已保留你手动调整的声音状态"), DND("本次上课勿扰已结束"),
}

internal fun beginQuiet(mode: QuietMode, endsAt: Long, device: QuietDevice, remember: (QuietLease) -> Unit): QuietLease {
    check(device.hasPolicyAccess) { "请先允许声音与勿扰控制" }
    val lease = when (mode) {
        QuietMode.SILENT -> QuietLease(mode, endsAt, previousRinger = device.ringerMode().also {
            check(it in RINGER_SILENT..RINGER_NORMAL) { "系统未提供可恢复的声音状态" }
        })
        QuietMode.DND -> QuietLease(mode, endsAt, ruleId = device.ensureRule())
    }
    remember(lease) // Must commit successfully before changing system audio or activating our rule.
    when (mode) {
        QuietMode.SILENT -> if (lease.previousRinger != RINGER_SILENT) device.setRingerMode(RINGER_SILENT)
        QuietMode.DND -> device.setRuleActive(requireNotNull(lease.ruleId), true)
    }
    return lease
}

internal fun restoreQuiet(lease: QuietLease, device: QuietDevice, fromUser: Boolean = false): QuietRestore {
    if (lease.mode == QuietMode.DND) {
        check(device.hasPolicyAccess) { "声音与勿扰权限已关闭，请检查系统勿扰状态" }
        device.setRuleActive(requireNotNull(lease.ruleId), false, fromUser)
        return QuietRestore.DND
    }
    val previous = requireNotNull(lease.previousRinger)
    // A manual switch to ringing/vibration takes precedence over our captured baseline.
    if (device.ringerMode() != RINGER_SILENT) return QuietRestore.MANUAL
    if (previous == RINGER_SILENT) return QuietRestore.UNCHANGED
    check(device.hasPolicyAccess) { "声音控制权限已关闭，请检查系统声音状态" }
    device.setRingerMode(previous)
    return if (previous == RINGER_VIBRATE) QuietRestore.VIBRATE else QuietRestore.NORMAL
}
