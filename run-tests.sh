#!/usr/bin/env bash
# Compile and run the JVM test harness for the Divia widget.
# Uses android STUBS (not android.jar) so the code runs on a desktop JVM.
#
# Three stages:
#   0. Updater source guard — the PackageInstaller status PendingIntent
#      in UpdateFlow must be created FLAG_MUTABLE: an immutable callback
#      drops the status fill-in (no install popup, "Failed to install"),
#      the v1.2.0-v1.5.1 bug fixed in 1.5.2.
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

echo "[0/3] updater source guard: install-status PendingIntent must be mutable"
if grep -q "FLAG_IMMUTABLE" "$PROJ/src/com/zai/divia/UpdateFlow.java"; then
    echo "FAIL: UpdateFlow creates an immutable status PendingIntent — Android"
    echo "      drops the status fill-in, the install popup never appears"
    exit 1
fi
if ! grep -q "FLAG_MUTABLE" "$PROJ/src/com/zai/divia/UpdateFlow.java"; then
    echo "FAIL: UpdateFlow lost the FLAG_MUTABLE status PendingIntent"
    exit 1
fi
echo "  OK: PackageInstaller status callback is mutable"

rm -rf "$OUT"
mkdir -p "$OUT"

# Activity UIs are excluded: they use framework classes (AlertDialog,
# Toast, PackageManager...) that the JVM stubs do not provide; the harness
# exercises the render pipeline, not the screens. UpdateFlow / UpdateReceiver
# drive Android's PackageInstaller and dialogs, so they are excluded too —
# their pure logic (MiniJson, ReleaseCheck) IS in the harness.
SRCS=$(find "$PROJ/src" -name '*.java' \
    ! -name 'ConfigActivity.java' \
    ! -name 'HomeActivity.java' \
    ! -name 'SettingsActivity.java' \
    ! -name 'NotifyActivity.java' \
    ! -name 'UpdateFlow.java' \
    ! -name 'UpdateReceiver.java')
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

java -Ddivia.proj="$PROJ" -cp "$OUT" com.zai.divia.LayoutWhitelist
echo
java -Ddivia.proj="$PROJ" -cp "$OUT" com.zai.divia.RenderHarness
