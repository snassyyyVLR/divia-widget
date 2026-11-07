package android.content.res;

public class AssetManager {
    private final String path;
    public AssetManager(String path) { this.path = path; }
    public java.io.InputStream open(String name) throws java.io.IOException {
        return new java.io.FileInputStream(path);
    }
}
