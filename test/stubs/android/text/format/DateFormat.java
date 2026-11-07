package android.text.format;

public final class DateFormat {
    public static java.text.DateFormat getTimeFormat(android.content.Context c) {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE);
    }
}
