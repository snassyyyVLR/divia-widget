package android.app;

public class AlarmManager {
    public static final int RTC = 1;

    /** Each entry: {type, triggerAtMs, intervalMs, PendingIntent}. */
    public final java.util.List<Object[]> setRepeatingCalls = new java.util.ArrayList<Object[]>();
    public final java.util.List<PendingIntent> cancels = new java.util.ArrayList<PendingIntent>();

    public void setRepeating(int type, long triggerAtMs, long intervalMs, PendingIntent pi) {
        setRepeatingCalls.add(new Object[]{Integer.valueOf(type), Long.valueOf(triggerAtMs),
                Long.valueOf(intervalMs), pi});
    }

    public void cancel(PendingIntent pi) { cancels.add(pi); }
}
