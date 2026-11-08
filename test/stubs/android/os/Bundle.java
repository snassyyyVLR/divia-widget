package android.os;

public final class Bundle {
    private final java.util.HashMap<String, Object> map = new java.util.HashMap<String, Object>();

    public void putInt(String k, int v) { map.put(k, Integer.valueOf(v)); }

    public int getInt(String k, int def) {
        Object v = map.get(k);
        return v instanceof Integer ? ((Integer) v).intValue() : def;
    }
}
