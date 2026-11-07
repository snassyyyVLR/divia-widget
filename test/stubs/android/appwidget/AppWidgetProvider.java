package android.appwidget;

public class AppWidgetProvider extends android.content.BroadcastReceiver {
    public void onUpdate(android.content.Context c, AppWidgetManager m, int[] ids) {}
    public void onAppWidgetOptionsChanged(android.content.Context c, AppWidgetManager m,
                                          int appWidgetId, android.os.Bundle newOptions) {}
    public void onDeleted(android.content.Context c, int[] ids) {}
    public void onEnabled(android.content.Context c) {}
    public void onDisabled(android.content.Context c) {}
    public void onReceive(android.content.Context c, android.content.Intent i) {}
}
