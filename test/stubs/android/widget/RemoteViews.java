package android.widget;

/**
 * Recording RemoteViews stub: every action is stored so the harness can
 * assert exactly what would be applied in the launcher's process.
 */
public class RemoteViews {

    public static final class Act {
        public final String op;      // "text" | "vis" | "click" | "color"
        public final int viewId;
        public final Object value;
        Act(String op, int viewId, Object value) {
            this.op = op; this.viewId = viewId; this.value = value;
        }
    }

    public final String packageName;
    public final int layoutId;
    public final java.util.List<Act> acts = new java.util.ArrayList<Act>();

    public RemoteViews(String packageName, int layoutId) {
        this.packageName = packageName;
        this.layoutId = layoutId;
    }

    public void setTextViewText(int viewId, CharSequence text) {
        acts.add(new Act("text", viewId, text));
    }

    public void setViewVisibility(int viewId, int visibility) {
        acts.add(new Act("vis", viewId, Integer.valueOf(visibility)));
    }

    public void setTextColor(int viewId, int color) {
        acts.add(new Act("color", viewId, Integer.valueOf(color)));
    }

    public void setOnClickPendingIntent(int viewId, android.app.PendingIntent pi) {
        acts.add(new Act("click", viewId, pi));
    }
}
