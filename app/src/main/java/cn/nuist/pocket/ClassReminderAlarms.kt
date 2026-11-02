package cn.nuist.pocket

import android.annotation.SuppressLint
import android.content.Context
import cn.nuist.pocket.data.MAX_CLASS_WAKE_TIMES
import java.io.IOException

/** Bounded independent slots survive process death and a missed/failed earlier occurrence. */
internal class ClassReminderAlarms(context: Context) {
    private val prefs = context.getSharedPreferences("class_reminder_alarms", Context.MODE_PRIVATE)

    @SuppressLint("ApplySharedPref", "UseKtx") // Check commit's result before modifying AlarmManager; edit { } hides it.
    fun replace(times: List<Long>, register: (Int, Long) -> Boolean, cancel: (Int) -> Unit): Boolean {
        require(times.size <= MAX_CLASS_WAKE_TIMES && times == times.distinct().sorted())
        val oldCount = prefs.getInt("count", 0).coerceIn(0, MAX_CLASS_WAKE_TIMES)
        if (!prefs.edit().putInt("count", maxOf(oldCount, times.size)).commit()) throw IOException("提醒登记未能保存")
        var precise = true
        times.forEachIndexed { slot, at -> if (!register(slot, at)) precise = false }
        // Arm the complete new plan before removing surplus old slots.
        for (slot in times.size until oldCount) cancel(slot)
        if (!prefs.edit().putInt("count", times.size).commit()) throw IOException("提醒登记未能保存")
        return precise
    }
}
