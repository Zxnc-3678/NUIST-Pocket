package cn.nuist.pocket;

import android.app.Instrumentation;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import java.util.ArrayList;

/** Offscreen RemoteViews measurement: no login, stored-course reads, or injected gestures. */
public class WidgetLayoutInstrumentation extends Instrumentation {
    private boolean directory;
    private boolean reminder;
    private String buildingFilter;
    private boolean preferences;
    private boolean appearance;
    private boolean todos;
    private boolean refinements;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        Bundle args = arguments == null ? Bundle.EMPTY : arguments;
        directory = "true".equals(args.getString("directory"));
        reminder = "true".equals(args.getString("reminder"));
        preferences = "true".equals(args.getString("preferences"));
        appearance = "true".equals(args.getString("appearance"));
        todos = "true".equals(args.getString("todos"));
        refinements = "true".equals(args.getString("refinements"));
        buildingFilter = args.getString("building");
        start();
    }
    @Override public void onStart() {
        if (refinements) { UiRefinementInstrumentation.run(this); return; }
        if (todos) { TodoInstrumentation.run(this); return; }
        if (appearance) { AppearanceStorageInstrumentation.run(this); return; }
        if (preferences) {
            android.content.SharedPreferences prefs = getTargetContext().getSharedPreferences("app_preferences", Context.MODE_PRIVATE);
            Bundle result = new Bundle();
            result.putString("stream", "HOME_TAB=" + prefs.getString("home_tab", "unset") + " notificationEnabled=" + prefs.getBoolean("evening_enabled", true) + " target=" + getTargetContext().getPackageName() + "\n");
            finish(-1, result); return;
        }
        if (reminder) { ReminderInstrumentation.run(this); return; }
        if (directory) { new SchoolDirectoryInstrumentation(this, buildingFilter).run(); return; }
        Bundle result = new Bundle();
        try {
            runOnMainSync(() -> {
                Context context = getTargetContext();
                AppWidgetManager manager = AppWidgetManager.getInstance(context);
                int[] ids = manager.getAppWidgetIds(new ComponentName(context, "cn.nuist.pocket.TodayWidgetProvider"));
                StringBuilder report = new StringBuilder("fontScale=").append(context.getResources().getConfiguration().fontScale);
                for (int id : ids) {
                    Bundle options = manager.getAppWidgetOptions(id);
                    report.append("\nwidget=").append(id);
                    for (String key : new String[]{AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
                            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT}) {
                        report.append(" ").append(key).append("=").append(options.getInt(key));
                    }
                    ArrayList<SizeF> sizes = android.os.Build.VERSION.SDK_INT >= 31
                        ? options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES) : null;
                    if (sizes == null || sizes.isEmpty()) {
                        sizes = new ArrayList<>();
                        sizes.add(new SizeF(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280),
                            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)));
                    }
                    for (SizeF size : sizes) {
                        report.append("\nsize=").append(size);
                        measure(context, size, report);
                    }
                }
                // Also measure common four/five-column launcher sizes and a narrow layout.
                for (SizeF size : new SizeF[]{new SizeF(276, 455), new SizeF(349, 455), new SizeF(203, 337)}) {
                    report.append("\nsimulatedSize=").append(size);
                    measure(context, size, report);
                }
                report.append("\ncombinedCoursesAndTodos:");
                measureCombined(context, new SizeF(276,455), 4, 2, report);
                measureCombined(context, new SizeF(346,374), 3, 1, report);
                measureCombined(context, new SizeF(203,337), 2, 1, report);
                result.putString("stream", report.toString() + "\n");
            });
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
            finish(0, result);
        }
    }
    private static int resource(Context c, String type, String name) {
        int id = c.getResources().getIdentifier(name, type, c.getPackageName());
        if (id == 0) throw new IllegalStateException("Missing resource " + name);
        return id;
    }
    private static void measure(Context context, SizeF size, StringBuilder report) {
        measureLayout(context, size, report, 4, -1);
    }
    private static void measureCombined(Context context, SizeF size, int courses, int todos, StringBuilder report) {
        measureLayout(context, size, report, courses, todos);
    }
    private static void measureLayout(Context context, SizeF size, StringBuilder report, int courseCount, int todoCount) {
        RemoteViews root = new RemoteViews(context.getPackageName(), resource(context, "layout", "widget_today"));
        root.setTextViewText(resource(context, "id", "widget_date"), "今天 · 10月22日 / 周四");
        root.setTextViewText(resource(context, "id", "widget_week"), "第8周");
        root.setViewVisibility(resource(context, "id", "widget_empty"), View.GONE);
        int footer = context.getResources().getIdentifier("widget_footer", "id", context.getPackageName());
        if (footer != 0) root.setViewVisibility(footer, View.GONE);
        int container = resource(context, "id", "widget_courses");
        root.removeAllViews(container);
        int rowLayout = resource(context, "layout", size.getWidth() < 260 ? "widget_course_compact" : "widget_course");
        for (int i = 0; i < courseCount; i++) {
            RemoteViews row = new RemoteViews(context.getPackageName(), rowLayout);
            row.setTextViewText(resource(context, "id", "widget_start"), "10:10");
            row.setTextViewText(resource(context, "id", "widget_end"), "11:50");
            row.setTextViewText(resource(context, "id", "widget_name"), "雷达与卫星气象学基础（测量样例）");
            row.setTextViewText(resource(context, "id", "widget_detail"), "第3–4节 | 示例教室 | 示例教师");
            int state = context.getResources().getIdentifier("widget_state", "id", context.getPackageName());
            if (state != 0) row.setViewVisibility(state, View.GONE);
            row.setViewVisibility(resource(context, "id", "widget_countdown"), i == 0 ? View.VISIBLE : View.GONE);
            row.setTextViewText(resource(context, "id", "widget_countdown"), "上课中 · 54:15 后下课");
            root.addView(container, row);
        }
        if (todoCount >= 0) {
            root.setViewVisibility(resource(context,"id","widget_todos"), View.VISIBLE);
            root.setTextViewText(resource(context,"id","widget_todo_heading"), "今天待办 · 2项 ›");
            for (int i = 0; i < 2; i++) {
                int id = resource(context,"id",i == 0 ? "widget_todo_one" : "widget_todo_two");
                root.setViewVisibility(id, i < todoCount ? View.VISIBLE : View.GONE);
                root.setTextViewText(id,"○ 18:30 完成实验报告（测量样例）");
            }
        }
        FrameLayout parent = new FrameLayout(context);
        View view = root.apply(context, parent);
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(size.getWidth() * density), height = Math.round(size.getHeight() * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
        View courses = view.findViewById(container);
        View content = view.findViewById(resource(context, "id", "widget_root"));
        float required = (courses.getBottom() + content.getPaddingBottom()) / density;
        if (todoCount >= 0) required = (view.findViewById(resource(context,"id","widget_todos")).getBottom() + content.getPaddingBottom()) / density;
        report.append(" rows=").append(courseCount).append(" todos=").append(todoCount).append(" requiredDp=").append(required).append(" fits=").append(required <= size.getHeight());
        if (todoCount >= 0 && context.getResources().getConfiguration().fontScale <= 1f && required > size.getHeight()) {
            throw new AssertionError("Combined courses and tasks overflow: " + required + " > " + size.getHeight());
        }
        if (context.getResources().getConfiguration().fontScale <= 1f && size.getWidth() >= 260 && size.getHeight() >= 374 && required > size.getHeight()) {
            throw new AssertionError("Four normal-size course rows overflow: " + required + " > " + size.getHeight());
        }
        for (int i = 0; i < ((android.view.ViewGroup) courses).getChildCount(); i++) {
            report.append(" row").append(i).append("=").append(((android.view.ViewGroup) courses).getChildAt(i).getMeasuredHeight() / density);
        }
    }
}
