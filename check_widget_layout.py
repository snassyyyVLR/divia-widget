#!/usr/bin/env python3
"""Build-time guard: the app widget RemoteViews layout may only contain
@RemoteView-annotated classes, because the launcher inflates it in its own
process through RemoteViews.apply() with a LayoutInflater filter.

The v1.x "Problem loading widget on EVERY launcher" bug was a plain <View>
used as a 1dp divider: "Class not allowed to be instantiated:
android.view.View" -> InflateException -> error view on every launcher.

Usage: check_widget_layout.py <res-dir>
Exits non-zero and prints offenders when the check fails."""
import os
import sys
import xml.etree.ElementTree as ET

ALLOWED = {
    # layouts
    "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout",
    # widgets
    "AnalogClock", "Button", "Chronometer", "ImageButton", "ImageView",
    "ProgressBar", "TextView", "ViewFlipper", "ListView", "GridView",
    "StackView", "AdapterViewFlipper", "ViewStub",
}

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def check_layout(path):
    """Return the list of non-whitelisted element names in a layout XML."""
    tree = ET.parse(path)
    bad = []
    for el in tree.iter():
        tag = el.tag
        if not isinstance(tag, str):  # comments / PIs
            continue
        if tag.startswith(ANDROID_NS):
            continue
        if tag not in ALLOWED:
            bad.append(tag)
    return bad


def main():
    res_dir = sys.argv[1] if len(sys.argv) > 1 else "res"
    widget_layouts = []
    # The widget layouts: the one referenced by res/xml/widget_info.xml
    # (the initial/detailed layout) plus every other widget RemoteViews
    # layout — v1.5.0 added the compact variant. All of them are inflated
    # in the launcher's process, so ALL must pass the whitelist.
    info = os.path.join(res_dir, "xml", "widget_info.xml")
    layout_ref = None
    if os.path.isfile(info):
        info_tree = ET.parse(info)
        layout_ref = info_tree.getroot().get(ANDROID_NS + "initialLayout")
    if layout_ref and layout_ref.startswith("@layout/"):
        widget_layouts.append(os.path.join(res_dir, "layout",
                                            layout_ref[len("@layout/"):] + ".xml"))
    else:
        # Fall back to the known name so the guard still runs.
        widget_layouts.append(os.path.join(res_dir, "layout", "widget_divia.xml"))
    # v1.5.0: the compact layout variant, pushed by RefreshLogic when a
    # widget's config has compact=true. v1.6.0 adds the modern variant
    # (layout=2), v1.7.0 the large-text variant (layout=3). All are pushed
    # by RefreshLogic, so they are inflated in the launcher's process too
    # and must pass the whitelist.
    for extra in ("widget_divia_compact.xml", "widget_divia_modern.xml",
                  "widget_divia_large.xml"):
        path = os.path.join(res_dir, "layout", extra)
        if path not in widget_layouts:
            widget_layouts.append(path)

    failed = False
    for path in widget_layouts:
        if not os.path.isfile(path):
            print("LAYOUT-CHECK FATAL: widget layout not found: %s" % path)
            failed = True
            continue
        bad = check_layout(path)
        if bad:
            failed = True
            print("LAYOUT-CHECK FATAL: %s contains non-@RemoteView classes: %s"
                  % (path, ", ".join(sorted(set(bad)))))
            print("  The launcher inflates this layout in ITS process and will")
            print("  show 'Problem loading widget' on EVERY launcher.")
        else:
            print("LAYOUT-CHECK OK: %s — only whitelisted classes" % path)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
