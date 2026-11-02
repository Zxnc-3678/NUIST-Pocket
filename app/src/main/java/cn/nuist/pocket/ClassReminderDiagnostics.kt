package cn.nuist.pocket

import android.annotation.SuppressLint
import android.content.Context

/** Fixed-size local status only; no names, locations, account information or remote reporting. */
internal class ClassReminderDiagnostics(context: Context) {
    private val prefs = context.getSharedPreferences("class_reminder_diagnostics", Context.MODE_PRIVATE)
    val receivedAt: Long get() = prefs.getLong("received_at", 0)
    val submittedAt: Long get() = prefs.getLong("submitted_at", 0)
    val nextWake: Long get() = prefs.getLong("next_wake", 0)
    val exact: Boolean get() = prefs.getBoolean("exact", false)
    val error: String? get() = prefs.getString("error", null)
    val testAt: Long get() = prefs.getLong("test_at", 0)
    val testSubmittedAt: Long get() = prefs.getLong("test_submitted_at", 0)

    @SuppressLint("ApplySharedPref", "UseKtx") // Persist the explicit test request and check commit before registering its alarm.
    fun testScheduled(at: Long): Boolean = prefs.edit().putLong("test_at", at).commit()
    fun testSubmitted(at: Long) = write { putLong("test_submitted_at", at); putLong("test_at", 0) }

    @SuppressLint("ApplySharedPref", "UseKtx") // Small bounded IO-thread writes; diagnostics intentionally tolerate write failure.
    private fun write(block: android.content.SharedPreferences.Editor.() -> Unit) {
        // Diagnostics must never prevent reminder delivery or recovery if storage fails.
        runCatching { prefs.edit().apply(block).commit() }
    }
    fun received(at: Long) = write { putLong("received_at", at) }
    fun submitted(at: Long) = write { putLong("submitted_at", at) }
    fun scheduled(at: Long, precise: Boolean) = write {
        putLong("next_wake", at); putBoolean("exact", precise); remove("error")
    }
    fun failed(message: String) = write { putString("error", message.take(160)) }
}
