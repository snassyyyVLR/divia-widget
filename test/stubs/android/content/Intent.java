package android.content;

/** Minimal stub: carries action + extras. */
public class Intent {
    public static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    public static final String ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;

    public String action;
    public Class<?> cls;
    public String className;
    public int flags;
    public final java.util.HashMap<String, Object> extras = new java.util.HashMap<String, Object>();

    public Intent() {}
    public Intent(String action) { this.action = action; }
    public Intent(Context pkg, Class<?> cls) { this.cls = cls; }

    public Intent setClass(Context c, Class<?> cls) { this.cls = cls; return this; }
    public Intent setClassName(Context c, String cn) { this.className = cn; return this; }
    public Intent addFlags(int f) { this.flags |= f; return this; }
    public String getAction() { return action; }
    public Intent putExtra(String k, int v) { extras.put(k, Integer.valueOf(v)); return this; }
    public int getIntExtra(String k, int def) {
        Object v = extras.get(k);
        return v instanceof Integer ? ((Integer) v).intValue() : def;
    }
    public boolean hasExtra(String k) { return extras.containsKey(k); }
}
