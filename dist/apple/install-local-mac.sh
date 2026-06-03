#!/usr/bin/env bash
# install-local-mac.sh — install the current release's .dmg into /Applications and
# re-sign with the local "Developer ID Application: Jan Damek (GR74KSG8M9)" identity.
#
# Why re-sign: jpackage's local `packageDmg` output and the CI-built .dmg are both
# ad-hoc signed (Signature=adhoc, TeamIdentifier=not set) because Apple credentials
# live only on this Mac per HARD RULE #4. macOS keychain ACL is keyed on the
# designated requirement (Team ID + bundle ID); ad-hoc signatures change between
# builds, so "Always Allow" never persists and the user is prompted on every
# launch. Re-signing the installed bundle with Developer ID Application makes the
# requirement stable across releases — one "Always Allow" carries forward.
#
# Issue #90.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

VERSION="$(awk -F= '/^puklic\.version=/ {print $2; exit}' "${REPO_ROOT}/gradle.properties" | tr -d '[:space:]')"
[ -n "$VERSION" ] || { echo "puklic.version missing from gradle.properties" >&2; exit 1; }

DMG_LOCAL="${REPO_ROOT}/desktop/app/build/compose/binaries/main/dmg/Puklic-${VERSION}.dmg"
DMG_CI_DIR="/tmp/puklic-v${VERSION}"
DMG="${DMG_LOCAL}"

if [ ! -f "$DMG" ]; then
  echo "[install-local-mac] local DMG missing; downloading v${VERSION} from GitHub Release"
  mkdir -p "${DMG_CI_DIR}"
  gh release download "v${VERSION}" --pattern '*.dmg' --dir "${DMG_CI_DIR}" -R JanDamek/puklic
  DMG="${DMG_CI_DIR}/Puklic-${VERSION}.dmg"
fi

[ -f "$DMG" ] || { echo "[install-local-mac] no .dmg found at $DMG" >&2; exit 1; }

IDENTITY="Developer ID Application: Jan Damek (GR74KSG8M9)"
if ! security find-identity -v -p codesigning | grep -q "$IDENTITY"; then
  echo "[install-local-mac] codesigning identity missing: $IDENTITY" >&2
  exit 1
fi

echo "[install-local-mac] mounting $DMG"
pkill -f "/Applications/Puklic.app/" 2>/dev/null || true
sleep 1
rm -rf /Applications/Puklic.app
hdiutil attach "$DMG" -nobrowse -quiet
APP_SRC="$(ls -d /Volumes/Puklic*/Puklic.app 2>/dev/null | head -1)"
[ -n "$APP_SRC" ] || { echo "[install-local-mac] Puklic.app not found inside mounted DMG" >&2; exit 1; }

cp -R "$APP_SRC" /Applications/Puklic.app
hdiutil detach "$(dirname "$APP_SRC")" -force >/dev/null
xattr -dr com.apple.quarantine /Applications/Puklic.app 2>/dev/null || true

ENT="${SCRIPT_DIR}/local-mac.entitlements"
[ -f "$ENT" ] || { echo "[install-local-mac] missing entitlements file: $ENT" >&2; exit 1; }
# Main-executable + bundle entitlements add the iCloud-Keychain trio
# (application-identifier + team-identifier + keychain-access-groups) so the
# macApp writes a SYNCHRONIZABLE discord.token that syncs to iOS via iCloud
# Keychain (#92/#74). Authorized by the embedded Developer ID provisioning
# profile. Nested dylibs keep the JIT-only $ENT set.
ENT_KEYCHAIN="${SCRIPT_DIR}/local-mac-keychain.entitlements"
[ -f "$ENT_KEYCHAIN" ] || { echo "[install-local-mac] missing entitlements file: $ENT_KEYCHAIN" >&2; exit 1; }
PROFILE="${SCRIPT_DIR}/Puklic_macApp_DeveloperID.provisionprofile"
[ -f "$PROFILE" ] || { echo "[install-local-mac] missing provisioning profile: $PROFILE" >&2; exit 1; }

# Embed the Developer ID provisioning profile so AMFI authorizes the keychain /
# application-identifier entitlements at launch (a Developer ID app cannot use
# keychain-access-groups without an embedded profile).
cp "$PROFILE" /Applications/Puklic.app/Contents/embedded.provisionprofile

echo "[install-local-mac] re-signing /Applications/Puklic.app with $IDENTITY"
# Sign nested binaries first (--deep is documented as deprecated and is known
# to skip JDK runtime dylibs in jpackage bundles). Order: leaf dylibs/jnilibs,
# then the JDK launcher helper, then the app launcher, then the bundle wrapper.
# Entitlements MUST be applied to every Mach-O binary that runs JIT code (the
# JVM dylibs) — applying only to the outer .app is insufficient and triggers
# pthread_jit_write_protect_np SIGTRAP at JNI_CreateJavaVM. Nested binaries use
# the JIT-only set; the main launcher + bundle use the keychain set.
find /Applications/Puklic.app -type f \( -name "*.dylib" -o -name "*.jnilib" -o -name "*.so" \) \
  -exec codesign --force --options runtime --entitlements "$ENT" \
    --sign "$IDENTITY" --timestamp=none {} \;
codesign --force --options runtime --entitlements "$ENT" --sign "$IDENTITY" --timestamp=none \
  /Applications/Puklic.app/Contents/runtime/Contents/Home/lib/jspawnhelper
codesign --force --options runtime --entitlements "$ENT_KEYCHAIN" --sign "$IDENTITY" --timestamp=none \
  /Applications/Puklic.app/Contents/MacOS/Puklic
codesign --force --options runtime --entitlements "$ENT_KEYCHAIN" --sign "$IDENTITY" --timestamp=none \
  /Applications/Puklic.app

codesign --verify --deep --strict /Applications/Puklic.app
INSTALLED="$(plutil -extract CFBundleShortVersionString raw /Applications/Puklic.app/Contents/Info.plist)"
TEAM="$(codesign -dv /Applications/Puklic.app 2>&1 | awk -F= '/TeamIdentifier/ {print $2}')"
echo "[install-local-mac] done — Puklic ${INSTALLED} installed, TeamIdentifier=${TEAM}"
