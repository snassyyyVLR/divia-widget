package android.content;

/** Minimal stub: carries action + extras (int / long / String / boolean). */
public class Intent {
    public static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    public static final String ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS = 0x00800000;
    /** Used by the v1.6.1 install-fallback ACTION_VIEW intent (ApkProvider). */
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;

    public String action;
    public Class<?> cls;
    public String className;
    public int flags;
    public Object data;
    public String type;
    public final java.util.HashMap<String, Object> extras = new java.util.HashMap<String, Object>();

    public Intent() {}

    public Intent(String action) { this.action = action; }

    public Intent(Context pkg, Class<?> cls) { this.cls = cls; }

    public Intent setClass(Context c, Class<?> cls) { this.cls = cls; return this; }

    public Intent setClassName(Context c, String cn) { this.className = cn; return this; }

    public Intent addFlags(int f) { this.flags |= f; return this; }

    public String getAction() { return action; }

    public Intent setData(android.net.Uri uri) { this.data = uri; return this; }

    /** v1.6.1: the install-fallback intent (data + MIME type). */
    public Intent setDataAndType(android.net.Uri uri, String type) {
        this.data = uri; this.type = type; return this;
    }

    public Intent putExtra(String k, int v) { extras.put(k, Integer.valueOf(v)); return this; }

    public Intent putExtra(String k, long v) { extras.put(k, Long.valueOf(v)); return this; }

    public Intent putExtra(String k, String v) { extras.put(k, v); return this; }

    public Intent putExtra(String k, boolean v) { extras.put(k, Boolean.valueOf(v)); return this; }

    public int getIntExtra(String k, int def) {
        Object v = extras.get(k);
        return v instanceof Integer ? ((Integer) v).intValue() : def;
    }

    public long getLongExtra(String k, long def) {
        Object v = extras.get(k);
        return v instanceof Long ? ((Long) v).longValue() : def;
    }

    public String getStringExtra(String k) {
        Object v = extras.get(k);
        return v instanceof String ? (String) v : null;
    }

    public boolean getBooleanExtra(String k, boolean def) {
        Object v = extras.get(k);
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : def;
    }

    public boolean hasExtra(String k) { return extras.containsKey(k); }
}
