#!/usr/bin/env bash
# Compile and run the JVM test harness for the Divia widget.
# Uses android STUBS (not android.jar) so the code runs on a desktop JVM.
#
# Two stages:
#   1. LayoutWhitelist  — the widget layout must only contain
#      @RemoteView-whitelisted classes (the v1.x "Problem loading widget
#      on every launcher" bug was a plain <View> divider).
#   2. RenderHarness    — full render pipeline against the real catalog,
#      the real string resources and the live feed.
#
# Prerequisite: run build.sh first (it generates R.java).
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ECJ="$SDK/dl/ecj.jar"
OUT="$PROJ/out/test"

if [ ! -f "$PROJ/out/gen/com/zai/divia/R.java" ]; then
    echo "R.java missing - build the APK first (build.sh)"; exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

# Activity UIs are excluded: they use framework classes (AlertDialog,
# Toast, PackageManager...) that the JVM stubs do not provide; the harness
# exercises the render pipeline, not the screens.
SRCS=$(find "$PROJ/src" -name '*.java' \
    ! -name 'ConfigActivity.java' \
    ! -name 'HomeActivity.java' \
    ! -name 'SettingsActivity.java')
STUBS=$(find "$PROJ/test/stubs" -name '*.java')

if [ -f "$ECJ" ]; then
    java -jar "$ECJ" -nowarn -encoding UTF-8 -source 8 -target 8 \
        -d "$OUT" \
        "$PROJ/out/gen/com/zai/divia/R.java" \
        $SRCS $STUBS \
        "$PROJ/test/com/zai/divia/RenderHarness.java" \
        "$PROJ/test/com/zai/divia/LayoutWhitelist.java"
else
    javac -nowarn -encoding UTF-8 -source 8 -target 8 \
        -d "$OUT" \
        "$PROJ/out/gen/com/zai/divia/R.java" \
        $SRCS $STUBS \
        "$PROJ/test/com/zai/divia/RenderHarness.java" \
        "$PROJ/test/com/zai/divia/LayoutWhitelist.java"
fi

java -cp "$OUT" com.zai.divia.LayoutWhitelist
echo
java -cp "$OUT" com.zai.divia.RenderHarness
