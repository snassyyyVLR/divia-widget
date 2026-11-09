package com.zai.divia.core;

/**
 * Manual probe: fetch a repo's latest GitHub release with the exact code the
 * app uses and dump what the updater would make of it.
 *
 * Usage (after run-tests.sh, from the repo root):
 *   javac -cp out/test -d out/test test/com/zai/divia/core/ProbeUpdate.java
 *   java  -cp out/test com.zai.divia.core.ProbeUpdate [owner/repo]
 */
public final class ProbeUpdate {
    public static void main(String[] args) throws Exception {
        String repo = args.length > 0 ? args[0] : ReleaseCheck.REPO;
        System.out.println("repo:    " + repo);
        System.out.println("api:     https://api.github.com/repos/" + repo + "/releases/latest");
        try {
            ReleaseCheck.Release r = ReleaseCheck.check(repo, 10000);
            System.out.println("tag:     " + r.tag);
            System.out.println("version: " + r.versionString
                    + " (triple " + java.util.Arrays.toString(r.version) + ")");
            System.out.println("title:   " + r.name);
            System.out.println("page:    " + r.pageUrl);
            System.out.println("apk:     " + r.apkUrl + " (" + r.apkSize + " bytes)");
            System.out.println("notes:   " + r.notes.replace("\n", " | "));
            System.out.println("isNewerThan(\"1.2.0\"): " + r.isNewerThan("1.2.0"));
            System.out.println("isNewerThan(\"1.1.0\"): " + r.isNewerThan("1.1.0"));
        } catch (Exception e) {
            System.out.println("check failed: " + e);
            System.out.println("(404 = repo not found / private / no release published)");
        }
    }
}
