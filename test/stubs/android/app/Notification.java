package android.app;

import android.content.Context;

/** Minimal Notification stub: records title/text/PI so tests can assert. */
public final class Notification {

    public static final class Builder {
        private final Notification n = new Notification();

        public Builder(android.content.Context c) {}

        public Builder(android.content.Context c, String channel) {}

        public Builder setContentTitle(CharSequence t) { n.title = String.valueOf(t); return this; }

        public Builder setContentText(CharSequence t) { n.text = String.valueOf(t); return this; }

        public Builder setSmallIcon(int icon) { n.icon = icon; return this; }

        public Builder setAutoCancel(boolean b) { return this; }

        public Builder setContentIntent(PendingIntent pi) { n.pi = pi; return this; }

        public Notification build() { return n; }
    }

    public String title = "";
    public String text = "";
    public int icon;
    public PendingIntent pi;
}
