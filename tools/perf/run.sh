#!/bin/sh
# One-command baseline: builds the app image, makes sure a display exists, runs measure.py.
# Usage: tools/perf/run.sh [measure.py args]   (output: build/perf/$PERF_LABEL.json)
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
APP_DIR="$REPO/desktop/app/build/compose/binaries/main-release/app/Puklic"

if [ -z "${DISPLAY:-}" ]; then
    eval "$("$HERE/setup-xvfb.sh")"
fi
PATH="${PERF_X_ROOT:-/tmp/puklic-perf-x}/bin:$PATH" "$REPO/gradlew" -p "$REPO" :desktop:app:createReleaseDistributable

mkdir -p "$REPO/build/perf"
exec python3 -I "$HERE/measure.py" --app-dir "$APP_DIR" --jvm-flags "${PERF_JVM_FLAGS:-}" --label "${PERF_LABEL:-baseline}" \
    --output "$REPO/build/perf/${PERF_LABEL:-baseline}.json" "$@"
