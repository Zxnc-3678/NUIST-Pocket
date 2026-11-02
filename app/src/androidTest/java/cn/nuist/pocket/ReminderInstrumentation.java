package cn.nuist.pocket;

import android.Manifest;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Tests the real receiver with the user's granted notification permission and cached data.
 * No clock changes, injected gestures, credentials or preference values in test output. */
public class ReminderInstrumentation {
    public static void run(Instrumentation instrumentation) {
        Context context = instrumentation.getTargetContext();
        Bundle result = new Bundle();
        SharedPreferences preferences = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        StringBuilder report = new StringBuilder();
        report.append("schoolCachePresent=").append(context.getSharedPreferences("school_cache", Context.MODE_PRIVATE).contains("snapshot"))
            .append(" importedPresent=").append(context.getSharedPreferences("offline_schedule", Context.MODE_PRIVATE).contains("courses"))
            .append(" channelImportance=").append(manager.getNotificationChannel("tomorrow_courses") == null ? -1 : manager.getNotificationChannel("tomorrow_courses").getImportance()).append('\n');
        boolean allowed = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
        if (!allowed || !preferences.getBoolean("evening_enabled", true)) {
            result.putString("stream", "REMINDER_SKIPPED disabled_or_permission\n"); instrumentation.finish(0, result); return;
        }
        if (java.util.Arrays.stream(manager.getActiveNotifications()).anyMatch(n -> n.getId() == 2100)) {
            result.putString("stream", "REMINDER_SKIPPED already_visible\n"); instrumentation.finish(0, result); return;
        }
        String oldTime = preferences.getString("evening_time", null);
        String oldDate = preferences.getString("last_reminder_date", null);
        boolean posted = false;
        try {
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
            preferences.edit().putString("evening_time", now.toLocalTime().minusMinutes(1).toString()).remove("last_reminder_date").commit();
            context.sendBroadcast(new Intent().setComponent(new ComponentName(context, "cn.nuist.pocket.EveningReminder"))
                .setAction("cn.nuist.pocket.EVENING_REMINDER"));
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (SystemClock.elapsedRealtime() < deadline) {
                boolean visible = java.util.Arrays.stream(manager.getActiveNotifications()).anyMatch(n -> n.getId() == 2100);
                if (visible && now.toLocalDate().toString().equals(preferences.getString("last_reminder_date", null))) { posted = true; break; }
                SystemClock.sleep(50);
            }
        } finally {
            SharedPreferences.Editor edit = preferences.edit();
            if (oldTime == null) edit.remove("evening_time"); else edit.putString("evening_time", oldTime);
            if (oldDate == null) edit.remove("last_reminder_date"); else edit.putString("last_reminder_date", oldDate);
            edit.commit();
        }
        result.putString("stream", report + "REMINDER_POSTED=" + posted + " preferencesRestored=true\n");
        instrumentation.finish(posted ? -1 : 0, result);
    }
}
