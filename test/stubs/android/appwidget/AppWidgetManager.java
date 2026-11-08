package android.appwidget;

public class AppWidgetManager {
    public static final int INVALID_APPWIDGET_ID = 0;
    public static final String EXTRA_APPWIDGET_ID = "appWidgetId";
    public static final String OPTION_APPWIDGET_MIN_HEIGHT = "appWidgetMinHeight";
    public static final String OPTION_APPWIDGET_MAX_HEIGHT = "appWidgetMaxHeight";
    public static final String OPTION_APPWIDGET_MIN_WIDTH = "appWidgetMinWidth";
    public static final String OPTION_APPWIDGET_MAX_WIDTH = "appWidgetMaxWidth";

    private static final AppWidgetManager INSTANCE = new AppWidgetManager();

    /** Set by the harness: the widget ids the fake launcher has bound. */
    public int[] boundIds = new int[0];

    /** Each entry: {Integer id, RemoteViews rv}. */
    public final java.util.List<Object[]> updates = new java.util.ArrayList<Object[]>();

    public static AppWidgetManager getInstance(android.content.Context c) { return INSTANCE; }

    public int[] getAppWidgetIds(android.content.ComponentName provider) { return boundIds; }

    public void updateAppWidget(int id, android.widget.RemoteViews rv) {
        updates.add(new Object[]{Integer.valueOf(id), rv});
    }

    public void clearUpdates() { updates.clear(); }
}
