# JSON fields are read explicitly; no reflective model serialization is used.
# Keep source lines in retrace output for internal test builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Retain the small I/O boundary so device tests can exercise the same release code.
-keep class cn.nuist.pocket.data.WallpaperStore { public *; }
-keep class cn.nuist.pocket.data.TodoStore { public *; }
-keep class cn.nuist.pocket.data.TodoItem { public *; }
-keep class cn.nuist.pocket.data.WidgetAppearance { public *; }
-keep class cn.nuist.pocket.TodayWidgetProvider$Companion { public android.widget.RemoteViews render*(...); }
-keepclassmembers class cn.nuist.pocket.TodayWidgetProvider { public static ** Companion; }
-keep class cn.nuist.pocket.WidgetBackgroundKt { public *; }
-keep class cn.nuist.pocket.data.ImageSize { public *; }
