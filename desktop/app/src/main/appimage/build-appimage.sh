#!/usr/bin/env bash
# build-appimage.sh — package Compose Desktop's app-image directory into a
# real .AppImage using appimagetool.
#
# Compose Desktop's `TargetFormat.AppImage` is jpackage's `--type app-image`,
# which emits a runtime tree under build/compose/binaries/main/app-image/Puklic/.
# That directory is NOT an AppImage file — it's an unpacked jpackage app-image.
# AppImage convention requires:
#   AppDir/
#     AppRun        — entry point (relay to bin/Puklic)
#     *.desktop     — top-level .desktop manifest
#     *.png         — top-level icon (matches Icon= in .desktop)
#     ...           — the jpackage app-image content (bin/, lib/)
# We layer those three files on top, then call appimagetool.
#
# Inputs (env):
#   PUKLIC_VERSION   — required, e.g. "1.0.1"
#   APP_IMAGE_DIR    — required, path to Compose Desktop's emitted app-image dir
#                      (e.g. desktop/app/build/compose/binaries/main/app-image/Puklic)
#   OUT_DIR          — required, directory where the final .AppImage is written
#   ICON_PATH        — required, path to puklic.png (512x512 PNG)
#   APPIMAGETOOL_URL — optional override (default: pinned release below)
#   APPIMAGETOOL_SHA256 — optional override matching APPIMAGETOOL_URL
#
# Output:
#   $OUT_DIR/Puklic-<version>-x86_64.AppImage

set -euo pipefail

: "${PUKLIC_VERSION:?PUKLIC_VERSION required}"
: "${APP_IMAGE_DIR:?APP_IMAGE_DIR required}"
: "${OUT_DIR:?OUT_DIR required}"
: "${ICON_PATH:?ICON_PATH required}"

# Pinned appimagetool release (NOT 'continuous' — supply-chain stability per CLAUDE.md).
# Release 1.9.1 (x86_64). Update by editing both URL + SHA256 together.
DEFAULT_APPIMAGETOOL_URL="https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage"
DEFAULT_APPIMAGETOOL_SHA256="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"

APPIMAGETOOL_URL="${APPIMAGETOOL_URL:-$DEFAULT_APPIMAGETOOL_URL}"
APPIMAGETOOL_SHA256="${APPIMAGETOOL_SHA256:-$DEFAULT_APPIMAGETOOL_SHA256}"

if [ ! -d "$APP_IMAGE_DIR" ]; then
    echo "ERROR: APP_IMAGE_DIR='$APP_IMAGE_DIR' does not exist." >&2
    echo "       Run :desktop:app:packageAppImage first." >&2
    exit 1
fi
if [ ! -f "$ICON_PATH" ]; then
    echo "ERROR: ICON_PATH='$ICON_PATH' not found." >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APRUN_SRC="$SCRIPT_DIR/AppRun"
DESKTOP_SRC="$SCRIPT_DIR/puklic.desktop"
for f in "$APRUN_SRC" "$DESKTOP_SRC"; do
    if [ ! -f "$f" ]; then
        echo "ERROR: AppDir template file missing: $f" >&2
        exit 1
    fi
done

mkdir -p "$OUT_DIR"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

APPDIR="$WORK/Puklic.AppDir"
echo "build-appimage: staging AppDir at $APPDIR"
mkdir -p "$APPDIR"
# Copy the jpackage app-image content (bin/, lib/) into the AppDir root.
cp -a "$APP_IMAGE_DIR/." "$APPDIR/"

# Layer AppDir-specific files.
install -m 0755 "$APRUN_SRC" "$APPDIR/AppRun"
install -m 0644 "$DESKTOP_SRC" "$APPDIR/puklic.desktop"
install -m 0644 "$ICON_PATH" "$APPDIR/puklic.png"
# AppImage also requires a top-level .DirIcon (symlink or copy of the icon).
cp "$ICON_PATH" "$APPDIR/.DirIcon"

# Fetch + verify appimagetool.
TOOL="$WORK/appimagetool.AppImage"
echo "build-appimage: downloading $APPIMAGETOOL_URL"
curl -fsSL -o "$TOOL" "$APPIMAGETOOL_URL"
echo "$APPIMAGETOOL_SHA256  $TOOL" | sha256sum -c -
chmod +x "$TOOL"

# Build the AppImage. ARCH=x86_64 explicit (some CI hosts have ambiguous uname).
# --no-appstream skips AppStream metadata validation (we don't ship a .metainfo.xml yet).
OUT_FILE="$OUT_DIR/Puklic-${PUKLIC_VERSION}-x86_64.AppImage"
echo "build-appimage: invoking appimagetool -> $OUT_FILE"
# appimagetool itself is an AppImage; on a GH runner FUSE may be unavailable.
# `--appimage-extract-and-run` extracts the tool to /tmp and runs it without FUSE.
ARCH=x86_64 "$TOOL" --appimage-extract-and-run \
    --no-appstream \
    "$APPDIR" "$OUT_FILE"

chmod +x "$OUT_FILE"
echo "build-appimage: produced $OUT_FILE"
ls -la "$OUT_FILE"
