package android.content;

/**
 * Stub Context for the JVM render harness. The harness installs:
 *  - assetPath: the real assets/catalog.gz file,
 *  - stringValues: a complete R.string id -> text map parsed from the real
 *    res/values/strings.xml, so getString() behaves exactly like on device
 *    (and a missing string or format-arg mismatch fails the test).
 */
public class Context {
    public static final String ALARM_SERVICE = "alarm";
    public static final String NOTIFICATION_SERVICE = "notification";
    public static final int MODE_PRIVATE = 0;

    public String packageName = "com.zai.divia";
    public String assetPath;
    public java.util.Map<Integer, String> stringValues;
    /** Colors for getResources().getColor(): filled by the harness. */
    public final android.content.res.Resources resources =
            new android.content.res.Resources();

    private final java.util.HashMap<String, SharedPreferences> prefsMap =
            new java.util.HashMap<String, SharedPreferences>();
    public final android.app.AlarmManager alarmManager = new android.app.AlarmManager();
    /** Posted notifications (shared per-Context, like on device). */
    public final android.app.NotificationManager notificationManager =
            new android.app.NotificationManager();

    public String getPackageName() { return packageName; }

    public Object getSystemService(String name) {
        if (ALARM_SERVICE.equals(name)) return alarmManager;
        if (NOTIFICATION_SERVICE.equals(name)) return notificationManager;
        return null;
    }

    public android.content.res.AssetManager getAssets() {
        return new android.content.res.AssetManager(assetPath);
    }

    public android.content.res.Resources getResources() {
        return resources;
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences p = prefsMap.get(name);
        if (p == null) { p = new SharedPreferences(); prefsMap.put(name, p); }
        return p;
    }

    public String getString(int id) {
        String v = stringValues.get(Integer.valueOf(id));
        if (v == null) throw new IllegalStateException("string id " + id + " not found in strings.xml");
        return v;
    }

    public String getString(int id, Object... args) {
        String f = stringValues.get(Integer.valueOf(id));
        if (f == null) throw new IllegalStateException("string id " + id + " not found in strings.xml");
        return String.format(f, args);
    }

    public void startActivity(Intent i) { /* no-op on JVM */ }

    /** Minimal PackageManager stub (updater notifications read versionName). */
    public android.content.pm.PackageManager getPackageManager() {
        return new android.content.pm.PackageManager();
    }

    /** Recorded by the harness so it can assert broadcast targets. */
    public final java.util.List<Intent> sentBroadcasts = new java.util.ArrayList<Intent>();

    public void sendBroadcast(Intent i) { sentBroadcasts.add(i); }
}
