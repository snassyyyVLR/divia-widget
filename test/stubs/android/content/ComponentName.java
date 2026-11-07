package android.content;

public class ComponentName {
    public final String pkg;
    public final String cls;
    public ComponentName(Context c, Class<?> k) { this.pkg = c.getPackageName(); this.cls = k.getName(); }
    public ComponentName(String pkg, String cls) { this.pkg = pkg; this.cls = cls; }
    public String getClassName() { return cls; }
}
