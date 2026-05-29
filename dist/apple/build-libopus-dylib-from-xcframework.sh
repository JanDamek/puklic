#!/usr/bin/env bash
# build-libopus-dylib-from-xcframework.sh — re-link the macOS slice of
# Opus.xcframework (static libopus.a) into a universal libopus.dylib JNA
# can load from the Mac App Store .app bundle.
#
# Input:  shared/voice-codec/libs/Opus.xcframework/macos-arm64_x86_64/libopus.a
# Output: desktop/platform-macos-appstore/libs/libopus.dylib
#
# Prereqs: build-libopus-xcframework.sh has been run at least once. clang +
# codesign from Xcode Command Line Tools on PATH.
#
# Used by FP-14c (Mac App Store JNA codec wrappers, Issue #56).
#
# See docs/03_infrastructure/architect-reports/2026-05-29-fp14c-codec-wrappers.md
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
XCF="$REPO_ROOT/shared/voice-codec/libs/Opus.xcframework"
SLICE="$XCF/macos-arm64_x86_64/libopus.a"
OUT="$REPO_ROOT/desktop/platform-macos-appstore/libs/libopus.dylib"

if [ ! -f "$SLICE" ]; then
    echo "missing $SLICE — run dist/apple/build-libopus-xcframework.sh first" >&2
    exit 1
fi

mkdir -p "$(dirname "$OUT")"

# -force_load includes every object from the static archive in the resulting
# dylib (the default would drop symbols nothing externally references — a
# JNA Native.load() call hits every entry point lazily, so all must be present).
# -install_name uses @rpath so the same binary can live at any path inside the
# .app bundle as long as an rpath entry points at its directory.
# arm64-only — Mac App Store ship targets arm64 Macs per CLAUDE.md `## Platforms`.
# Intel Macs are out of scope; bundling an x86_64 slice would only waste space.
clang -dynamiclib -arch arm64 \
    -install_name @rpath/libopus.dylib \
    -Wl,-force_load,"$SLICE" \
    -o "$OUT"

# Ad-hoc signature so the dylib loads on Mac dev machines without further
# config. The Mac App Store packaging step (FP-14d jpackage --mac-sign)
# re-signs with the Apple Distribution identity.
codesign --sign - --force "$OUT"

SIZE=$(stat -f%z "$OUT")
echo "built $OUT ($SIZE bytes)"
echo "info:"
file -b "$OUT" || true
otool -L "$OUT" || true
