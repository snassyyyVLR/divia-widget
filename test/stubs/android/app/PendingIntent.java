package android.app;

/**
 * Records every PendingIntent created. getService() deliberately THROWS:
 * using a started-service PendingIntent is exactly what crashed v1.0 on
 * Android 8+ when the app was backgrounded, so any regression fails the
 * harness loudly.
 */
public class PendingIntent {
    public static final int FLAG_UPDATE_CURRENT = 134217728;
    public static final int FLAG_IMMUTABLE = 67108864;

    public final String kind;        // "broadcast" | "activity"
    public final int requestCode;
    public final android.content.Intent intent;

    private static final java.util.List<PendingIntent> CREATED =
            new java.util.ArrayList<PendingIntent>();

    private PendingIntent(String kind, int requestCode, android.content.Intent intent) {
        this.kind = kind; this.requestCode = requestCode; this.intent = intent;
        CREATED.add(this);
    }

    public static java.util.List<PendingIntent> created() { return CREATED; }

    public static PendingIntent getBroadcast(android.content.Context c, int rc,
                                             android.content.Intent i, int flags) {
        return new PendingIntent("broadcast", rc, i);
    }

    public static PendingIntent getActivity(android.content.Context c, int rc,
                                            android.content.Intent i, int flags) {
        return new PendingIntent("activity", rc, i);
    }

    public static PendingIntent getService(android.content.Context c, int rc,
                                           android.content.Intent i, int flags) {
        throw new IllegalStateException(
                "PendingIntent.getService() is forbidden: it crashes when the app is "
                + "backgrounded on Android 8+ (the v1.0 'Can't load widget' bug)");
    }
}
