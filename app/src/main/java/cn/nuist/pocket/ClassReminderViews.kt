package cn.nuist.pocket

import android.app.PendingIntent
import android.content.Context
import android.widget.RemoteViews

/** A 48dp collapsed content row; system decoration and expanded actions remain native. */
internal fun classReminderCompact(context: Context, title: String, detail: String, action: String, pending: PendingIntent) =
    RemoteViews(context.packageName, R.layout.class_reminder_compact).apply {
        setTextViewText(R.id.class_notification_title, title)
        setTextViewText(R.id.class_notification_detail, detail)
        setTextViewText(R.id.class_notification_action, action)
        setContentDescription(R.id.class_notification_action, "$action：$title")
        // Activity PendingIntents use the system unlock flow. No unlocked broadcast shortcut on the lockscreen.
        setOnClickPendingIntent(R.id.class_notification_action, pending)
    }

internal fun classReminderExpanded(context: Context, title: String, detail: String, note: String) =
    RemoteViews(context.packageName, R.layout.class_reminder_expanded).apply {
        setTextViewText(R.id.class_notification_title, title)
        setTextViewText(R.id.class_notification_detail, detail)
        setTextViewText(R.id.class_notification_note, note)
    }
