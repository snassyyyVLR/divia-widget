package android.graphics;

/** No-op Canvas stub that records draw calls into its Bitmap. */
public final class Canvas {

    private final Bitmap bitmap;

    public Canvas(Bitmap b) {
        this.bitmap = b;
    }

    public void drawRoundRect(float left, float top, float right, float bottom,
                              float rx, float ry, Paint paint) {
        if (bitmap != null && paint != null) bitmap.bgColors.add(Integer.valueOf(paint.color));
    }

    public void drawText(String text, float x, float y, Paint paint) {
        if (bitmap != null) bitmap.drawnTexts.add(String.valueOf(text));
    }
}
