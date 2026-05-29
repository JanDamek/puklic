#!/usr/bin/env bash
# build-libopus-xcframework.sh — reproducibly build libopus as a multi-arch
# iOS XCFramework for `:shared:voice-codec` Kotlin/Native cinterop.
#
# Output:
#   shared/voice-codec/libs/Opus.xcframework/    — multi-slice static framework
#   shared/voice-codec/libs/Opus.xcframework.sha256 — SHA-256 manifest
#
# Prereqs (macOS):
#   brew install autoconf automake libtool pkg-config
#   Xcode + Command Line Tools
#
# Usage:
#   ./dist/apple/build-libopus-xcframework.sh
#
# Pinned upstream tag — bump to take a new libopus release. SHA manifest
# must be regenerated and committed alongside any version bump.
#
# Slices produced:
#   ios-arm64                       device
#   ios-arm64_x86_64-simulator      simulator (arm64 + x86_64 lipo'd)
#   macos-arm64_x86_64              macOS desktop (arm64 + x86_64 lipo'd) — FP-14c
#
# See docs/03_infrastructure/architect-reports/2026-05-29-fp4-ios-opus-libopus.md
# See docs/03_infrastructure/architect-reports/2026-05-29-fp14c-codec-wrappers.md
set -euo pipefail

OPUS_VERSION="v1.5.2"
OPUS_REPO="https://gitlab.xiph.org/xiph/opus.git"
IOS_DEPLOYMENT_TARGET="15.0"
MACOS_DEPLOYMENT_TARGET="13.0"

# Resolve repo root from this script's location: dist/apple/<this>
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="$REPO_ROOT/shared/voice-codec/libs"
XCF_OUT="$OUT_DIR/Opus.xcframework"
SHA_OUT="$OUT_DIR/Opus.xcframework.sha256"

WORK="$(mktemp -d -t libopus-xcframework-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "[1/5] Cloning $OPUS_REPO @ $OPUS_VERSION → $WORK"
git -C "$WORK" clone --depth=1 --branch="$OPUS_VERSION" "$OPUS_REPO" opus

cd "$WORK/opus"
echo "[2/5] Running autogen.sh"
./autogen.sh >/dev/null

build_arch() {
    local sdk="$1" arch="$2" host="$3" min_flag="$4"
    local sdkroot
    sdkroot="$(xcrun --sdk "$sdk" --show-sdk-path)"
    local out="$WORK/build/${sdk}-${arch}"
    mkdir -p "$out"

    echo "    → configure $sdk / $arch (host=$host)"
    make distclean >/dev/null 2>&1 || true

    # `--disable-asm`: upstream ships GNU-syntax `.S` for ARM that Apple's
    # integrated assembler rejects (`unknown directive` on `.fpu` etc.).
    # Disabling asm uses portable C — adequate for the 48 kHz mono/stereo
    # 20 ms voice frames Puklic uses (perf headroom is plentiful on modern
    # iOS devices). `--disable-rtcd`: avoid run-time CPU detection paths
    # that also reference the asm units.
    #
    # CC is set explicitly with -arch so cross-compiling to x86_64 from an
    # arm64 host actually emits x86_64 objects (autoconf's default-host
    # inference picks the build arch otherwise).
    CC="$(xcrun --sdk "$sdk" --find clang) -arch $arch -isysroot $sdkroot $min_flag" \
    CFLAGS="-O2 -fPIC" \
    LDFLAGS="" \
    ./configure \
        --host="$host" \
        --disable-shared \
        --enable-static \
        --disable-doc \
        --disable-extra-programs \
        --disable-stack-protector \
        --disable-asm \
        --disable-rtcd \
        >/dev/null

    echo "    → make $sdk / $arch"
    make -j"$(sysctl -n hw.ncpu)" >/dev/null
    cp .libs/libopus.a "$out/libopus.a"
}

echo "[3/5] Building per-arch static libs"
build_arch iphoneos        arm64  arm-apple-darwin     "-miphoneos-version-min=$IOS_DEPLOYMENT_TARGET"
build_arch iphonesimulator arm64  arm-apple-darwin     "-mios-simulator-version-min=$IOS_DEPLOYMENT_TARGET"
build_arch iphonesimulator x86_64 x86_64-apple-darwin  "-mios-simulator-version-min=$IOS_DEPLOYMENT_TARGET"
build_arch macosx          arm64  arm-apple-darwin     "-mmacosx-version-min=$MACOS_DEPLOYMENT_TARGET"
build_arch macosx          x86_64 x86_64-apple-darwin  "-mmacosx-version-min=$MACOS_DEPLOYMENT_TARGET"

echo "[4/5] lipo simulator + macOS slices → fat libopus.a"
# Keep the binary basename `libopus.a` in both slices so cinterop
# `staticLibraries = libopus.a` resolves with one .def file and per-target
# `libraryPaths` only differing in directory.
SIM_DIR="$WORK/sim-fat"
mkdir -p "$SIM_DIR"
SIM_FAT="$SIM_DIR/libopus.a"
lipo -create \
    "$WORK/build/iphonesimulator-arm64/libopus.a" \
    "$WORK/build/iphonesimulator-x86_64/libopus.a" \
    -output "$SIM_FAT"

MAC_DIR="$WORK/mac-fat"
mkdir -p "$MAC_DIR"
MAC_FAT="$MAC_DIR/libopus.a"
lipo -create \
    "$WORK/build/macosx-arm64/libopus.a" \
    "$WORK/build/macosx-x86_64/libopus.a" \
    -output "$MAC_FAT"

# Stage headers (xcodebuild requires a directory, not a list of files)
HDR_STAGE="$WORK/headers"
mkdir -p "$HDR_STAGE"
cp "$WORK/opus/include/opus.h"             "$HDR_STAGE/"
cp "$WORK/opus/include/opus_defines.h"     "$HDR_STAGE/"
cp "$WORK/opus/include/opus_types.h"       "$HDR_STAGE/"
cp "$WORK/opus/include/opus_multistream.h" "$HDR_STAGE/"

echo "[5/5] Creating Opus.xcframework → $XCF_OUT"
rm -rf "$XCF_OUT"
mkdir -p "$OUT_DIR"
xcodebuild -create-xcframework \
    -library "$WORK/build/iphoneos-arm64/libopus.a" -headers "$HDR_STAGE" \
    -library "$SIM_FAT" -headers "$HDR_STAGE" \
    -library "$MAC_FAT" -headers "$HDR_STAGE" \
    -output "$XCF_OUT" >/dev/null

# Manifest: SHA-256 of every file inside the XCFramework, sorted.
echo "    → writing SHA manifest"
( cd "$OUT_DIR" && find Opus.xcframework -type f -print0 | LC_ALL=C sort -z \
    | xargs -0 shasum -a 256 ) > "$SHA_OUT"

# Tail summary line for quick diff
SUMMARY=$(shasum -a 256 "$SHA_OUT" | awk '{print $1}')
echo "opus_version=$OPUS_VERSION xcframework_manifest_sha256=$SUMMARY"
