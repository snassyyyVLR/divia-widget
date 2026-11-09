package android.content.pm;

/** Stub for the JVM harness: the updater notifications read versionName. */
public class PackageManager {
    public PackageInfo getPackageInfo(String pkg, int flags) {
        PackageInfo pi = new PackageInfo();
        pi.versionName = "1.2.0";
        pi.versionCode = 11;
        return pi;
    }
}
