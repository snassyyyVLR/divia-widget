package android.content.res;

/**
 * Stub Resources for the JVM render harness. The harness installs a
 * complete R.color id -> 0xAARRGGBB map parsed from the real
 * res/values/colors.xml (and/or the values-night variant), so getColor()
 * behaves like on device and palette mistakes fail the test.
 */
public class Resources {
    public java.util.Map<Integer, Integer> colorValues =
            new java.util.HashMap<Integer, Integer>();

    public int getColor(int id) {
        Integer v = colorValues.get(Integer.valueOf(id));
        if (v == null) throw new IllegalStateException("color id " + id + " not found in colors.xml");
        return v.intValue();
    }
}
