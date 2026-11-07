#!/usr/bin/env bash
# Build the Divia widget APK with raw Android build-tools (no Gradle).
#
# Requirements:
#   - Android SDK with build-tools 34.0.0 and platform android-34
#     (set ANDROID_HOME, or drop the SDK at ~/Android/Sdk)
#   - a JDK 8+ and, optionally, the Eclipse compiler jar (ecj.jar) at
#     $ANDROID_HOME/dl/ecj.jar (falls back to plain javac)
#   - python3 (for the widget-layout whitelist guard)
#
# Resource ID stability is MANDATORY for widgets: after an in-place app
# update the launchers re-apply the RemoteViews they cached from the
# previous APK, with the OLD numeric view/resource ids baked in. If the
# update renumbered them, apply() crashes inside the launcher and every
# existing widget turns into "Can't load widget" (seen on HyperOS AND
# Lawnchair with v1.3). --stable-ids pins every resource to the numbering
# of the day the file was created; the drift check below makes the build
# fail if anything ever moves.
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
ECJ="$SDK/dl/ecj.jar"
OUT="$PROJ/out"
KS="$PROJ/debug.keystore"
FINAL="$PROJ/DiviaWidget-debug.apk"
STABLE="$PROJ/stable-ids.txt"

[ -x "$BT/aapt2" ] || { echo "aapt2 not found: $BT — set ANDROID_HOME"; exit 1; }
[ -f "$AJ" ]       || { echo "android.jar not found: $AJ — install platform 34"; exit 1; }

# Debug keystore (shared here so every rebuild installs over the previous
# one). If you deleted it, a fresh one is generated — but then Android will
# require uninstalling the previously installed build first.
if [ ! -f "$KS" ]; then
    echo "[0/8] generating debug keystore"
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes"

echo "[1/8] aapt2 compile"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "[1b/8] widget layout whitelist check (launcher-process safety)"
python3 "$PROJ/check_widget_layout.py" "$PROJ/res"

echo "[2/8] aapt2 link (+assets, R.java, stable ids)"
"$BT/aapt2" link \
    -o "$OUT/unsigned.apk" \
    -I "$AJ" \
    -A "$PROJ/assets" \
    --manifest "$PROJ/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version 21 \
    --target-sdk-version 34 \
    --version-code 8 \
    --version-name 2.1.1 \
    --auto-add-overlay \
    --stable-ids "$STABLE" \
    --emit-ids "$OUT/emitted-ids.txt" \
    "$OUT/res.zip"

echo "[3/8] drift check: every pinned resource id must be unchanged"
MISSING=0
while IFS= read -r line; do
    [ -z "$line" ] && continue
    if ! grep -qxF "$line" "$OUT/emitted-ids.txt"; then
        echo "  DRIFT: $line" >&2
        MISSING=1
    fi
done < "$STABLE"
if [ "$MISSING" -ne 0 ]; then
    echo "FATAL: resource ids drifted from stable-ids.txt." >&2
    echo "Renumbered ids break every widget bound under previous versions" >&2
    echo "(launchers re-apply cached RemoteViews with old numeric ids)." >&2
    exit 1
fi
echo "  OK: $(wc -l < "$STABLE") pinned ids unchanged, $(wc -l < "$OUT/emitted-ids.txt") total"

echo "[4/8] compile (ecj or javac)"
SRCS=$(find "$PROJ/src" -name '*.java')
if [ -f "$ECJ" ]; then
    java -jar "$ECJ" -nowarn -encoding UTF-8 -source 8 -target 8 \
        -bootclasspath "$AJ" \
        -d "$OUT/classes" \
        "$OUT/gen/com/zai/divia/R.java" \
        $SRCS
else
    javac -nowarn -encoding UTF-8 -source 8 -target 8 \
        -bootclasspath "$AJ" \
        -d "$OUT/classes" \
        "$OUT/gen/com/zai/divia/R.java" \
        $SRCS
fi

echo "[5/8] d8 dex"
"$BT/d8" --release --min-api 21 --lib "$AJ" \
    --output "$OUT" \
    $(find "$OUT/classes" -name '*.class')

echo "[6/8] pack classes.dex"
(cd "$OUT" && zip -q unsigned.apk classes.dex)

echo "[7/8] zipalign"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[8/8] apksigner sign"
"$BT/apksigner" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$FINAL" \
    "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$FINAL" | head -3
echo "DONE: $FINAL ($(du -h "$FINAL" | cut -f1))"
