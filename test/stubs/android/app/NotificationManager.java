package android.app;

/** Recording NotificationManager stub (one shared instance per Context). */
public class NotificationManager {

    public static final int IMPORTANCE_HIGH = 3;
    public static final int IMPORTANCE_DEFAULT = 1;

    /** Each entry: {Integer id, Notification n}. */
    public final java.util.List<Object[]> posted = new java.util.ArrayList<Object[]>();
    /** Channel ids created so far. */
    public final java.util.List<String> channels = new java.util.ArrayList<String>(2);

    public void notify(int id, Notification n) {
        if (n != null) posted.add(new Object[]{Integer.valueOf(id), n});
    }

    public void createNotificationChannel(NotificationChannel c) {
        if (c != null) channels.add(c.id);
    }
}
