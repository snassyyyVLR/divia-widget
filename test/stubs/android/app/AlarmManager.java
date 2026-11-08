package android.app;

public class AlarmManager {
    public static final int RTC = 1;
    public static final int RTC_WAKEUP = 2;

    /** Each entry: {type, triggerAtMs, intervalMs, PendingIntent}. */
    public final java.util.List<Object[]> setRepeatingCalls = new java.util.ArrayList<Object[]>();
    public final java.util.List<PendingIntent> cancels = new java.util.ArrayList<PendingIntent>();
    /** One-shot window alarms: {type, triggerAtMs, windowMs, PendingIntent}. */
    public final java.util.List<Object[]> windowCalls = new java.util.ArrayList<Object[]>();
    /** Exact alarms: {type, triggerAtMs, PendingIntent}. */
    public final java.util.List<Object[]> exactCalls = new java.util.ArrayList<Object[]>();
    /** Plain set() alarms: {type, triggerAtMs, PendingIntent}. */
    public final java.util.List<Object[]> setCalls = new java.util.ArrayList<Object[]>();
    /** Toggled by the harness to simulate the exact-alarm grant. */
    public boolean exactAllowed = true;

    public void setRepeating(int type, long triggerAtMs, long intervalMs, PendingIntent pi) {
        setRepeatingCalls.add(new Object[]{Integer.valueOf(type), Long.valueOf(triggerAtMs),
                Long.valueOf(intervalMs), pi});
    }

    public void setWindow(int type, long triggerAtMs, long windowMs, PendingIntent pi) {
        windowCalls.add(new Object[]{Integer.valueOf(type), Long.valueOf(triggerAtMs),
                Long.valueOf(windowMs), pi});
    }

    public void setExactAndAllowWhileIdle(int type, long triggerAtMs, PendingIntent pi) {
        exactCalls.add(new Object[]{Integer.valueOf(type), Long.valueOf(triggerAtMs), pi});
    }

    public void set(int type, long triggerAtMs, PendingIntent pi) {
        setCalls.add(new Object[]{Integer.valueOf(type), Long.valueOf(triggerAtMs), pi});
    }

    public boolean canScheduleExactAlarms() {
        return exactAllowed;
    }

    public void cancel(PendingIntent pi) { cancels.add(pi); }
}
