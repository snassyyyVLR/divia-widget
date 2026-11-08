package android.content;

/** In-memory SharedPreferences stub. */
public class SharedPreferences {
    final java.util.HashMap<String, Object> map = new java.util.HashMap<String, Object>();

    public boolean contains(String k) { return map.containsKey(k); }
    public String getString(String k, String def) {
        Object v = map.get(k); return v instanceof String ? (String) v : def;
    }
    public int getInt(String k, int def) {
        Object v = map.get(k); return v instanceof Integer ? ((Integer) v).intValue() : def;
    }
    public long getLong(String k, long def) {
        Object v = map.get(k); return v instanceof Long ? ((Long) v).longValue() : def;
    }
    public boolean getBoolean(String k, boolean def) {
        Object v = map.get(k); return v instanceof Boolean ? ((Boolean) v).booleanValue() : def;
    }
    public java.util.Map<String, Object> getAll() { return map; }

    public Editor edit() { return new Editor(); }

    public class Editor {
        private final java.util.HashMap<String, Object> pend = new java.util.HashMap<String, Object>();
        private final java.util.ArrayList<String> removals = new java.util.ArrayList<String>();
        public Editor putString(String k, String v) { pend.put(k, v); return this; }
        public Editor putInt(String k, int v) { pend.put(k, Integer.valueOf(v)); return this; }
        public Editor putLong(String k, long v) { pend.put(k, Long.valueOf(v)); return this; }
        public Editor putBoolean(String k, boolean v) { pend.put(k, Boolean.valueOf(v)); return this; }
        public Editor remove(String k) { removals.add(k); return this; }
        public void apply() {
            map.putAll(pend);
            for (String k : removals) map.remove(k);
        }
    }
}
