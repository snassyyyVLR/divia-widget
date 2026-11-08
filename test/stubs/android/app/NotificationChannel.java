package android.app;

public final class NotificationChannel {
    public final String id;

    public NotificationChannel(String id, CharSequence name, int importance) {
        this.id = id;
    }
}
