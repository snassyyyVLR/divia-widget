package com.zai.divia;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Regression guard for the v1.x "Problem loading widget on EVERY launcher"
 * bug: the widget layout is inflated inside the LAUNCHER's process by
 * RemoteViews.apply(), whose LayoutInflater filter only accepts classes
 * annotated @RemoteView. A plain <View> (the old divider) threw
 * "Class not allowed to be instantiated: android.view.View" on every
 * launcher,Pixel/Lawnchair/HyperOS alike — platform-enforced, not a
 * launcher quirk.
 *
 * This test re-implements that filter against the SOURCE layout so the
 * JVM harness fails loudly before anything is installed on a device.
 */
public final class LayoutWhitelist {

    /** Project root: injected by run-tests.sh (-Ddivia.proj=...). */
    static final String PROJ = System.getProperty("divia.proj", ".");
    static int pass = 0, fail = 0;

    /**
     * The exact @RemoteView-annotated view classes as of API 34
     * (frameworks/base RemoteViews.LayoutInflaterFilter whitelist).
     */
    static final Set<String> ALLOWED = new HashSet<String>(Arrays.asList(
            // layouts
            "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout",
            // widgets
            "AnalogClock", "Button", "Chronometer", "ImageButton", "ImageView",
            "ProgressBar", "TextView", "ViewFlipper", "ListView", "GridView",
            "StackView", "AdapterViewFlipper", "ViewStub"));

    /** Every id the render code addresses via RemoteViews actions. */
    static final Set<String> NEEDED_IDS = new LinkedHashSet<String>(Arrays.asList(
            "widget_root", "line_badge", "stop_name", "line_dest",
            "row0", "row1", "row2", "row3", "row4",
            "dest0", "dest1", "dest2", "dest3", "dest4",
            "count0", "count1", "count2", "count3", "count4",
            "clock0", "clock1", "clock2", "clock3", "clock4",
            "empty_status", "updated"));

    public static void main(String[] args) throws Exception {
        System.out.println("[widget layout whitelist]");

        Document doc = parse(PROJ + "/res/layout/widget_divia.xml");
        Element root = doc.getDocumentElement();
        check(root != null, "widget_divia.xml parses with a root element");

        Set<String> declaredIds = new HashSet<String>();
        StringBuilder offenders = new StringBuilder();
        walk(root, declaredIds, offenders, new int[]{0});
        check(offenders.length() == 0,
                "every element class is @RemoteView-whitelisted"
                + (offenders.length() == 0 ? "" : " — OFFENDERS:" + offenders));
        System.out.println("        (" + elements(root) + " elements checked, "
                + declaredIds.size() + " ids declared)");

        for (String needed : NEEDED_IDS) {
            check(declaredIds.contains(needed), "layout declares @+id/" + needed);
        }

        // Cross-check: the ids referenced by the Java render arrays must be
        // exactly the row/dest/count/clock ids present in the layout (v1.1.0:
        // five rows — 3 always + 2 shown on tall widgets).
        Set<String> rowIds = new HashSet<String>();
        for (String n : declaredIds) {
            if (n.startsWith("row") || n.startsWith("dest")
                    || n.startsWith("count") || n.startsWith("clock")) rowIds.add(n);
        }
        check(rowIds.equals(new HashSet<String>(Arrays.asList(
                "row0", "row1", "row2", "row3", "row4",
                "dest0", "dest1", "dest2", "dest3", "dest4",
                "count0", "count1", "count2", "count3", "count4",
                "clock0", "clock1", "clock2", "clock3", "clock4"))),
                "no stray row/dest/count/clock ids (RemoteViews would crash the launcher)");

        System.out.println("[widget provider info]");
        Document info = parse(PROJ + "/res/xml/widget_info.xml");
        Element prov = info.getDocumentElement();
        String initial = prov.getAttribute("android:initialLayout");
        check("@layout/widget_divia".equals(initial),
                "initialLayout is @layout/widget_divia (got: " + initial + ")");
        String period = prov.getAttribute("android:updatePeriodMillis");
        check(period.isEmpty() || Long.parseLong(period) >= 1800000L,
                "updatePeriodMillis >= 30 min when set (got: " + period + ")");

        System.out.println();
        System.out.println("LayoutWhitelist: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void walk(Element e, Set<String> ids, StringBuilder offenders, int[] counter) {
        counter[0]++;
        String tag = e.getTagName();
        if (!ALLOWED.contains(tag)) {
            offenders.append(" <").append(tag).append(">");
        }
        String id = attr(e, "android:id");
        if (id != null && id.startsWith("@+id/")) ids.add(id.substring("@+id/".length()));

        // Unprefixed android: attrs are safe; anything else is suspicious.
        NodeList children = e.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element) walk((Element) n, ids, offenders, counter);
        }
    }

    static int elements(Element root) {
        int[] c = new int[]{0};
        count(root, c);
        return c[0];
    }

    static void count(Element e, int[] c) {
        c[0]++;
        NodeList children = e.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element) count((Element) n, c);
        }
    }

    static String attr(Element e, String name) {
        // DocumentBuilder does not resolve the android: prefix into a
        // namespace unless the parser is namespace-aware AND the DTD maps
        // it, so fall back to the qualified name.
        String v = e.getAttribute(name);
        if (v == null || v.isEmpty()) {
            v = e.getAttribute(name.substring(name.indexOf(':') + 1));
        }
        return v;
    }

    static Document parse(String path) throws Exception {
        File f = new File(path);
        if (!f.isFile()) {
            System.out.println("  FAIL  missing file: " + path);
            fail++;
            throw new IllegalStateException("missing " + path);
        }
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(f);
    }

    static void check(boolean ok, String msg) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + msg);
        } else {
            fail++;
            System.out.println("  FAIL  " + msg);
        }
    }

    private LayoutWhitelist() {}
}
