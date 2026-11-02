package cn.nuist.pocket

import android.annotation.SuppressLint
import android.content.Context
import cn.nuist.pocket.data.*
import java.io.IOException

/** One bounded local snapshot. Calls are serialized by ClassReminders and run on Dispatchers.IO. */
internal class ClassReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences("class_reminders", Context.MODE_PRIVATE)
    fun load(): ClassReminderState = prefs.getString("state", null)?.let(::decodeClassReminderState) ?: ClassReminderState()
    @SuppressLint("ApplySharedPref") // Write-ahead recovery must reach disk before changing system audio.
    fun save(state: ClassReminderState) {
        if (!prefs.edit().putString("state", encodeClassReminderState(state)).commit()) throw IOException("预约状态未能保存")
    }
    var ruleId: String?
        get() = prefs.getString("rule_id", null)
        @SuppressLint("ApplySharedPref")
        set(value) { if (!prefs.edit().putString("rule_id", value).commit()) throw IOException("勿扰规则未能保存") }
}
