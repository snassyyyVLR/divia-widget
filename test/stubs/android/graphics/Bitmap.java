package android.graphics;

/**
 * Recording Bitmap stub: the harness asserts what was drawn on it (texts,
 * background colors) by reading the lists the Canvas stub fills in.
 */
public final class Bitmap {

    public static final class Config {
        public static final Config ARGB_8888 = new Config();
    }

    public final int width;
    public final int height;
    /** Texts drawn onto the bitmap, in draw order (harness assertions). */
    public final java.util.List<String> drawnTexts = new java.util.ArrayList<String>(2);
    /** Background colors painted so far (harness assertions). */
    public final java.util.List<Integer> bgColors = new java.util.ArrayList<Integer>(2);

    private Bitmap(int w, int h) {
        this.width = w;
        this.height = h;
    }

    public static Bitmap createBitmap(int w, int h, Config c) {
        return new Bitmap(Math.max(1, w), Math.max(1, h));
    }
}
