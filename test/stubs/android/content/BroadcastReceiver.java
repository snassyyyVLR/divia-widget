package android.content;

public class BroadcastReceiver {
    public static final class PendingResult {
        public boolean finished = false;
        public void finish() { finished = true; }
    }

    public PendingResult goAsync() { return new PendingResult(); }

    public void onReceive(Context context, Intent intent) {}
}
