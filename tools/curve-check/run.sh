#!/usr/bin/env bash
# Exercises the pure logic -- MagnificationCurve, ZoomController, OneEuroFilter -- on the JVM,
# with no device and no emulator. Everything that decides how the magnifier *feels* lives in
# those three files, so this is where tuning happens before anything is flashed.
#
#   ./run.sh          run the checks
#   ./run.sh Sweep    sweep 1-Euro minCutoff/beta against rest stability and tracking
#   ./run.sh Sweep2   sweep the zoom rate limit against tracking and autofocus-step absorption
set -euo pipefail
cd "$(dirname "$0")"

TARGET="${1:-Check}"
SRC=../../app/src/main/kotlin/com/pobox/magicmagnifier
CACHE="${KOTLIN_HOME:-$HOME/tools/kotlinc}"

if [ ! -x "$CACHE/bin/kotlinc" ]; then
    echo "Fetching the Kotlin compiler into $CACHE (one time, ~82MB)..."
    tmp=$(mktemp -d)
    curl -sSL -o "$tmp/kotlinc.zip" \
        https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip
    mkdir -p "$(dirname "$CACHE")"
    python3 - "$tmp/kotlinc.zip" "$(dirname "$CACHE")" <<'PY'
import os, sys, zipfile
z = zipfile.ZipFile(sys.argv[1]); dest = sys.argv[2]
z.extractall(dest)
for n in z.namelist():
    m = z.getinfo(n).external_attr >> 16
    if m: os.chmod(os.path.join(dest, n), m)
PY
    rm -rf "$tmp"
fi

out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
"$CACHE/bin/kotlinc" \
    "$SRC/MagnificationCurve.kt" "$SRC/OneEuroFilter.kt" "$TARGET.kt" \
    -include-runtime -d "$out/$TARGET.jar" 2>&1 | grep -v "^warning: \(unable\|advanced\)" || true
java -jar "$out/$TARGET.jar"
