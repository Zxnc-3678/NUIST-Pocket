package cn.nuist.pocket;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcel;
import android.util.SizeF;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import cn.nuist.pocket.data.TodoItem;
import cn.nuist.pocket.data.TodoStore;
import cn.nuist.pocket.data.WallpaperStore;
import cn.nuist.pocket.data.WidgetAppearance;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Isolated fixtures only: never reads or replaces the user's tasks, images or school cache. */
public final class TodoInstrumentation {
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
    private static int resource(Context context, String type, String name) {
        int id = context.getResources().getIdentifier(name, type, context.getPackageName());
        check(id != 0, "resource " + name); return id;
    }
    private static void importImage(WallpaperStore store, byte[] bytes) throws Exception {
        WallpaperStore.class.getMethod("import", java.io.InputStream.class).invoke(store, new ByteArrayInputStream(bytes));
    }
    public static void run(Instrumentation instrumentation) {
        Context target = instrumentation.getTargetContext();
        File folder = new File(target.getCacheDir(), "todo-check-" + System.nanoTime());
        folder.mkdirs();
        String preferencePrefix = "todo-check-" + System.nanoTime() + "-";
        Context isolated = new ContextWrapper(target) {
            @Override public File getFilesDir() { return folder; }
            @Override public File getCacheDir() { return folder; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences(preferencePrefix + name, mode);
            }
        };
        Bundle result = new Bundle();
        try {
            WidgetAppearance appearance = new WidgetAppearance(isolated);
            check(appearance.getFade() == 70, "default fade unchanged");
            appearance.setFade(40);
            check(new WidgetAppearance(isolated).getFade() == 40, "40 percent fade persists");
            appearance.setFade(95);
            check(appearance.getFade() == 95, "95 percent fade persists");
            appearance.setFade(0);
            check(appearance.getFade() == 40, "fade lower bound");
            appearance.setFade(100);
            check(appearance.getFade() == 95, "fade upper bound");
            appearance.setFade(40);
            TodoStore store = new TodoStore(isolated);
            check(store.load().isEmpty(), "empty storage");
            TodoItem first = new TodoItem("fixture-a", "完成实验报告", LocalDate.of(2026, 10, 23), LocalTime.of(18, 30), "测试备注", false);
            TodoItem second = new TodoItem("fixture-b", "图书馆借书", LocalDate.of(2026, 10, 24), null, "", true);
            List<TodoItem> tasks = Arrays.asList(first, second);
            store.save(tasks);
            check(new TodoStore(isolated).load().equals(tasks), "persisted task fields");
            File data = new File(folder, "personal-todos.json");
            byte[] saved = Files.readAllBytes(data.toPath());
            boolean rejected = false;
            try { store.save(Arrays.asList(first, first)); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected && Arrays.equals(saved, Files.readAllBytes(data.toPath())), "invalid save preserves old data");
            Files.write(data.toPath(), "{invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            rejected = false;
            try { store.load(); } catch (Exception expected) { rejected = true; }
            check(rejected && data.length() == 8, "corrupt data not overwritten");
            Files.write(new File(folder, "personal-todos.json.bak").toPath(), saved);
            check(store.load().equals(tasks), "atomic backup recovery");

            Bitmap input = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888);
            input.eraseColor(android.graphics.Color.rgb(20, 80, 100));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); input.compress(Bitmap.CompressFormat.PNG, 100, bytes); input.recycle();
            WallpaperStore appImage = new WallpaperStore(isolated);
            WallpaperStore widgetImage = WallpaperStore.widget(isolated);
            importImage(appImage, bytes.toByteArray()); importImage(widgetImage, bytes.toByteArray());
            Bitmap appBitmap = appImage.load(), widgetBitmap = widgetImage.load();
            check(appBitmap.getWidth() == 1600 && widgetBitmap.getWidth() == 1600, "independent high-resolution image sizes");
            Bitmap preview = widgetImage.loadPreview();
            check(preview != null && preview.getWidth() <= 800 && preview.getWidth() > 320, "bounded sharp settings preview");
            preview.recycle();
            Method prepare = Class.forName("cn.nuist.pocket.WidgetBackgroundKt").getMethod("prepareWidgetBackground", Bitmap.class, int.class, int.class);
            Bitmap transfer = (Bitmap) prepare.invoke(null, widgetBitmap.copy(Bitmap.Config.ARGB_8888,false), 1440,3200);
            check(transfer.getWidth()==1600 && transfer.getHeight()==800, "high-resolution pixels preserved for launcher");
            if (android.os.Build.VERSION.SDK_INT>=31) {
                Parcel imageParcel = Parcel.obtain(); transfer.writeToParcel(imageParcel,0);
                check(imageParcel.hasFileDescriptors() && imageParcel.dataSize()<20000, "shared-memory image transport");
                imageParcel.recycle();
            }
            appBitmap.recycle();
            widgetImage.clear(); check(appImage.exists(), "widget clear preserves timetable wallpaper");
            Method legacy = Class.forName("cn.nuist.pocket.WidgetBackgroundKt").getMethod("legacyWidgetBackground", Bitmap.class, int.class, int.class, int.class);
            Bitmap rounded = (Bitmap) legacy.invoke(null, widgetBitmap, 276, 455, 70);
            check(rounded.getWidth() <= 900 && rounded.getHeight() > 600 && rounded.getWidth()*rounded.getHeight() <= 450000 && android.graphics.Color.alpha(rounded.getPixel(0,0)) == 0,
                "Android11 rounded background is bounded and transparent at corners");
            rounded.recycle();

            Bitmap black = Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);
            black.eraseColor(android.graphics.Color.BLACK);
            for (int fade : new int[]{40,95}) {
                Bitmap faded = (Bitmap) legacy.invoke(null,black,100,100,fade);
                int center = android.graphics.Color.red(faded.getPixel(faded.getWidth()/2,faded.getHeight()/2));
                check(Math.abs(center-fade*255/100)<=1, "legacy fade blend " + fade);
                faded.recycle();
            }
            black.recycle();

            StringBuilder report = new StringBuilder("fade=40..95 persistenceAndBounds=passed legacyBlend=40,95 storage=5/5 imageIsolation=2/2 preview=800px highResolution=1600px sharedTransport=passed legacyBackground=1/1");
            final Throwable[] renderFailure = {null};
            instrumentation.runOnMainSync(() -> {
                try {
                    Object companion = Class.forName("cn.nuist.pocket.TodayWidgetProvider").getField("Companion").get(null);
                    Method render = null;
                    for (Method method : companion.getClass().getMethods()) {
                        if (method.getName().startsWith("render") && method.getParameterCount() == 9) { render = method; break; }
                    }
                    check(render != null, "release renderer boundary");
                    Map<SizeF,RemoteViews> responsive = new LinkedHashMap<>();
                    for (int[] size : new int[][]{{276,455}, {349,455}, {203,337}, {180,130}}) {
                        RemoteViews views = (RemoteViews) render.invoke(companion, target, null, null, LocalDateTime.of(2026,10,23,12,0), size[0], size[1], Collections.singletonList(first), transfer, appearance);
                        responsive.put(new SizeF(size[0],size[1]), views);
                        Parcel parcel = Parcel.obtain(); views.writeToParcel(parcel, 0);
                        check(parcel.dataSize() < 900000, "bounded widget parcel"); parcel.recycle();
                        View view = views.apply(target, new FrameLayout(target));
                        float density = target.getResources().getDisplayMetrics().density;
                        int width = Math.round(size[0]*density), height = Math.round(size[1]*density);
                        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                        view.layout(0,0,width,height);
                        View footer = view.findViewById(resource(target,"id","widget_todos"));
                        check(footer.getVisibility() == View.VISIBLE, "todo footer visible");
                        check(footer.getBottom() <= height && footer.getTop() >= 0, "footer fits");
                        check(view.findViewById(resource(target,"id",android.os.Build.VERSION.SDK_INT >= 31 ? "widget_wallpaper" : "widget_wallpaper_legacy")).getVisibility() == View.VISIBLE, "custom background rendered");
                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            android.graphics.drawable.ColorDrawable veil = (android.graphics.drawable.ColorDrawable)view.findViewById(resource(target,"id","widget_veil")).getBackground();
                            check(android.graphics.Color.alpha(veil.getColor()) == 40*255/100, "launcher receives 40 percent fade");
                        }
                        report.append("\nrender=").append(size[0]).append('x').append(size[1]).append(" footerFits=true");
                    }
                    if (android.os.Build.VERSION.SDK_INT>=31) {
                        for (int[] size : new int[][]{{346,374},{455,276},{320,400},{600,240}}) {
                            responsive.put(new SizeF(size[0],size[1]), (RemoteViews)render.invoke(companion,target,null,null,LocalDateTime.of(2026,10,23,12,0),size[0],size[1],Collections.singletonList(first),transfer,appearance));
                        }
                        RemoteViews all = new RemoteViews(responsive);
                        Parcel parcel = Parcel.obtain(); all.writeToParcel(parcel,0);
                        check(parcel.dataSize()<900000 && parcel.hasFileDescriptors(), "eight sizes reuse shared image transport");
                        report.append("\neightSizeParcelBytes=").append(parcel.dataSize()).append(" sharedMemory=true");
                        parcel.recycle();
                    }
                } catch (Throwable failure) { renderFailure[0] = failure; }
            });
            if (renderFailure[0] != null) throw new AssertionError("render check failed", renderFailure[0]);
            widgetBitmap.recycle();
            transfer.recycle();
            result.putString("stream", report.append("\n").toString()); instrumentation.finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", failure.getClass().getSimpleName() + ": " + failure.getMessage() + (failure.getCause() == null ? "" : " cause=" + failure.getCause()) + "\n"); instrumentation.finish(0,result);
        } finally {
            target.deleteSharedPreferences(preferencePrefix + "widget_appearance");
            File[] children = folder.listFiles(); if (children != null) for (File child : children) child.delete(); folder.delete();
        }
    }
}
