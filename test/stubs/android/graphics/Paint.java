package android.graphics;

/** Minimal Paint stub; the current color is public so Canvas can record it. */
public final class Paint {

    public static final int ANTI_ALIAS_FLAG = 1;

    public static final class Align {
        public static final Align CENTER = new Align();
        public static final Align LEFT = new Align();
        public static final Align RIGHT = new Align();
    }

    public int color = 0xFF000000;

    public Paint() {}

    public Paint(int flags) {}

    public void setColor(int c) { this.color = c; }

    public void setTextSize(float size) {}

    public void setTypeface(Typeface tf) {}

    public void setTextAlign(Align a) {}

    public float measureText(String text) {
        return text == null ? 0f : text.length() * 8f;
    }

    public float ascent() { return -10f; }

    public float descent() { return 3f; }
}
