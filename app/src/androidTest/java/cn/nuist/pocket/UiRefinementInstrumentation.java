package cn.nuist.pocket;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

/** Offscreen widget XML geometry; no gestures or personal data. */
public final class UiRefinementInstrumentation {
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private static int id(Context context, String type, String name) {
        int result = context.getResources().getIdentifier(name, type, context.getPackageName());
        check(result != 0, name); return result;
    }
    public static void run(Instrumentation instrumentation) {
        Bundle result = new Bundle();
        Throwable[] failure = {null};
        StringBuilder report = new StringBuilder();
        instrumentation.runOnMainSync(() -> {
            try {
                Context context = instrumentation.getTargetContext();
                RemoteViews layout = new RemoteViews(context.getPackageName(), id(context,"layout","widget_today"));
                layout.setViewVisibility(id(context,"id","widget_empty"), View.GONE);
                layout.setViewVisibility(id(context,"id","widget_todos"), View.VISIBLE);
                layout.addView(id(context,"id","widget_courses"), new RemoteViews(context.getPackageName(), id(context,"layout","widget_course")));
                View view = layout.apply(context, new FrameLayout(context));
                float density = context.getResources().getDisplayMetrics().density;
                int width = Math.round(346*density), height = Math.round(374*density);
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
                view.layout(0,0,width,height);
                View root = view.findViewById(id(context,"id","widget_root"));
                View header = (View)view.findViewById(id(context,"id","widget_date")).getParent();
                ViewGroup courses = view.findViewById(id(context,"id","widget_courses"));
                View footer = view.findViewById(id(context,"id","widget_todos"));
                float outer = context.getResources().getDimension(id(context,"dimen","widget_outer_radius"));
                float inner = context.getResources().getDimension(id(context,"dimen","widget_inner_radius"));
                float actualOuter = ((GradientDrawable)view.getBackground()).getCornerRadius();
                check(Math.abs(actualOuter-outer)<=1f, "outer radius actual="+actualOuter+" expected="+outer);
                for (View card : new View[]{header,courses.getChildAt(0),footer}) {
                    float actualInner = ((GradientDrawable)card.getBackground()).getCornerRadius();
                    check(Math.abs(actualInner-inner)<=1f, "inner radius actual="+actualInner+" expected="+inner);
                }
                check(root.getPaddingLeft()==Math.round(12*density), "consistent outer inset");
                check(courses.getTop()-header.getBottom()>=Math.floor(8*density), "header and course separated");
                check(footer.getBottom()<=root.getHeight()-root.getPaddingBottom(), "footer keeps outer inset");
                if (android.os.Build.VERSION.SDK_INT>=31) check(view.getClipToOutline(),"photo clipped to outer shape");
                report.append("widgetGeometry: outer=").append(outer/density).append(" inner=").append(inner/density)
                    .append(" inset=12 gap=8 clipping=true\n");
            } catch (Throwable error) { failure[0]=error; }
        });
        if (failure[0] != null) { result.putString("stream", "FAILED: " + failure[0]+"\n"); instrumentation.finish(0,result); }
        else { result.putString("stream", report.toString()); instrumentation.finish(-1,result); }
    }
}
