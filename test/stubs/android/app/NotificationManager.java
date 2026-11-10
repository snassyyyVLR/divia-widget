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

    /** v1.6.1: cancelUpdateTapToInstall (ROM-blocked-session fallback). */
    public void cancel(int id) {
        for (java.util.Iterator<Object[]> it = posted.iterator(); it.hasNext(); ) {
            Object[] entry = it.next();
            if (entry[0] instanceof Integer && ((Integer) entry[0]).intValue() == id) {
                it.remove();
            }
        }
    }

    public void createNotificationChannel(NotificationChannel c) {
        if (c != null) channels.add(c.id);
    }
}
