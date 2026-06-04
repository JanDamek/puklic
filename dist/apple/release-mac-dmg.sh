#!/usr/bin/env bash
# release-mac-dmg.sh — build a SIGNED + NOTARIZED distributable macOS .dmg locally.
#
# HARD RULE #4 (CLAUDE.md, 2026-05-31): Apple builds + uploads happen on a developer
# Mac ONLY, never on CI. CI (build-installers.yml) produces the Linux + Windows
# installers; this script produces the macOS arm64 .dmg, signed with the local
# "Developer ID Application: Jan Damek (GR74KSG8M9)" identity and notarized via the
# App Store Connect API key on this Mac. No Apple credential is ever committed or
# placed in a GitHub Secret.
#
# Flow:
#   1. Read VERSION from gradle.properties, verify signing identity present.
#   2. Build the (ad-hoc) unsigned .dmg with Compose Desktop packageDmg.
#   3. Mount it, copy Puklic.app out, detach.
#   4. Re-sign the app exactly like install-local-mac.sh, but with a secure
#      --timestamp (required for notarization).
#   5. Install the signed app to /Applications.
#   6. Build a fresh distributable .dmg from the signed app, sign the .dmg.
#   7. Notarize the .dmg (notarytool --wait) + staple. (--skip-notarize skips this.)
#   8. Print the final .dmg path. (--upload attaches it to the GitHub release.)
#   9. Verify signature + Gatekeeper assessment.
#
# Usage:
#   dist/apple/release-mac-dmg.sh [--skip-notarize] [--upload] [--help]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

SKIP_NOTARIZE=0
UPLOAD=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [--skip-notarize] [--upload] [--help]

Build a signed (+ notarized) distributable macOS arm64 .dmg locally.

Options:
  --skip-notarize  Sign only; skip notarization + stapling (step 7).
  --upload         After build, gh release upload v<VERSION> <dmg> --clobber.
  --help           Show this message.
EOF
}

for arg in "$@"; do
  case "$arg" in
    --skip-notarize) SKIP_NOTARIZE=1 ;;
    --upload) UPLOAD=1 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

# --- Step 1: version + identity ------------------------------------------
VERSION="$(awk -F= '/^puklic\.version=/ {print $2; exit}' "${REPO_ROOT}/gradle.properties" | tr -d '[:space:]')"
[ -n "$VERSION" ] || { echo "[release-mac-dmg] puklic.version missing from gradle.properties" >&2; exit 1; }
TAG="v${VERSION}"
echo "[release-mac-dmg] version=${VERSION}"

IDENTITY="Developer ID Application: Jan Damek (GR74KSG8M9)"
if ! security find-identity -v -p codesigning | grep -q "$IDENTITY"; then
  echo "[release-mac-dmg] codesigning identity missing: $IDENTITY" >&2
  echo "  Restore from the project .p12 backup (HARD RULE #7) — do NOT regenerate." >&2
  exit 1
fi

ENT="${SCRIPT_DIR}/local-mac.entitlements"
ENT_KEYCHAIN="${SCRIPT_DIR}/local-mac-keychain.entitlements"
PROFILE="${SCRIPT_DIR}/Puklic_macApp_DeveloperID.provisionprofile"
for f in "$ENT" "$ENT_KEYCHAIN" "$PROFILE"; do
  [ -f "$f" ] || { echo "[release-mac-dmg] missing required file: $f" >&2; exit 1; }
done

# ASC API key for notarization (lives on this Mac only).
ASC_KEY_ID="6C6D4D726S"
ASC_ISSUER="69a6de7f-7dab-47e3-e053-5b8c7c11a4d1"
ASC_KEY="${HOME}/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8"

# --- Step 2: build the unsigned .dmg -------------------------------------
# The Gradle desktop build trips over a dangling ~/.android symlink (it points
# at an unmounted volume). Replace it with an empty dir for the build, restore
# the original symlink on exit.
LINK_TARGET="$(readlink "${HOME}/.android" 2>/dev/null || true)"
trap '[ -n "$LINK_TARGET" ] && { rm -rf "${HOME}/.android"; ln -s "$LINK_TARGET" "${HOME}/.android"; }' EXIT
rm -rf "${HOME}/.android" && mkdir -p "${HOME}/.android"

echo "[release-mac-dmg] building unsigned .dmg (:desktop:app:packageDmg)"
( cd "$REPO_ROOT" && ./gradlew --init-script /tmp/init-maven-central.gradle \
    :desktop:app:packageDmg --no-daemon )

DMG_UNSIGNED="${REPO_ROOT}/desktop/app/build/compose/binaries/main/dmg/Puklic-${VERSION}.dmg"
[ -f "$DMG_UNSIGNED" ] || {
  DMG_UNSIGNED="$(ls -1 "${REPO_ROOT}/desktop/app/build/compose/binaries/main/dmg/"*.dmg 2>/dev/null | head -1 || true)"
}
[ -n "${DMG_UNSIGNED:-}" ] && [ -f "$DMG_UNSIGNED" ] || {
  echo "[release-mac-dmg] unsigned .dmg not found under desktop/app/build/compose/binaries/main/dmg/" >&2
  exit 1
}
echo "[release-mac-dmg] unsigned .dmg = $DMG_UNSIGNED"

# --- Step 3: extract the .app --------------------------------------------
WORK_DIR="${REPO_ROOT}/desktop/app/build/dist-mac/work"
rm -rf "$WORK_DIR" && mkdir -p "$WORK_DIR"
echo "[release-mac-dmg] mounting $DMG_UNSIGNED"
hdiutil attach "$DMG_UNSIGNED" -nobrowse -quiet
APP_SRC="$(ls -d /Volumes/Puklic*/Puklic.app 2>/dev/null | head -1)"
[ -n "$APP_SRC" ] || { echo "[release-mac-dmg] Puklic.app not found inside mounted DMG" >&2; exit 1; }
APP="${WORK_DIR}/Puklic.app"
cp -R "$APP_SRC" "$APP"
hdiutil detach "$(dirname "$APP_SRC")" -force >/dev/null
xattr -dr com.apple.quarantine "$APP" 2>/dev/null || true

# --- Step 4: re-sign (secure timestamp, distribution) --------------------
# Embed the Developer ID provisioning profile so AMFI authorizes the keychain /
# application-identifier entitlements at launch.
cp "$PROFILE" "${APP}/Contents/embedded.provisionprofile"

echo "[release-mac-dmg] re-signing $APP with $IDENTITY (secure timestamp)"
# Nested binaries that run JIT (JVM dylibs) get the JIT-only entitlements; the
# main launcher + bundle get the keychain entitlements. --timestamp (NOT
# --timestamp=none) because notarization requires a secure timestamp.
find "$APP" -type f \( -name "*.dylib" -o -name "*.jnilib" -o -name "*.so" \) \
  -exec codesign --force --options runtime --entitlements "$ENT" \
    --sign "$IDENTITY" --timestamp {} \;
codesign --force --options runtime --entitlements "$ENT" --sign "$IDENTITY" --timestamp \
  "${APP}/Contents/runtime/Contents/Home/lib/jspawnhelper"
codesign --force --options runtime --entitlements "$ENT_KEYCHAIN" --sign "$IDENTITY" --timestamp \
  "${APP}/Contents/MacOS/Puklic"
codesign --force --options runtime --entitlements "$ENT_KEYCHAIN" --sign "$IDENTITY" --timestamp \
  "$APP"

# --- Step 5: install the signed app to /Applications ---------------------
echo "[release-mac-dmg] installing signed app to /Applications/Puklic.app"
pkill -f "/Applications/Puklic.app/" 2>/dev/null || true
sleep 1
rm -rf /Applications/Puklic.app
cp -R "$APP" /Applications/Puklic.app

# --- Step 6: build a fresh distributable .dmg from the signed app --------
DIST_DIR="${REPO_ROOT}/desktop/app/build/dist-mac"
DMG="${DIST_DIR}/Puklic-${VERSION}.dmg"
rm -f "$DMG"
echo "[release-mac-dmg] creating distributable .dmg from signed /Applications/Puklic.app"
hdiutil create -volname "Puklic" -srcfolder /Applications/Puklic.app -ov -format UDZO "$DMG"
codesign --force --sign "$IDENTITY" --timestamp "$DMG"

# --- Step 7: notarize + staple -------------------------------------------
if [ "$SKIP_NOTARIZE" -eq 1 ]; then
  echo "[release-mac-dmg] --skip-notarize: signing only, not notarizing"
else
  [ -f "$ASC_KEY" ] || { echo "[release-mac-dmg] ASC API key missing: $ASC_KEY" >&2; exit 1; }
  echo "[release-mac-dmg] notarizing $DMG (notarytool --wait)"
  xcrun notarytool submit "$DMG" \
    --key "$ASC_KEY" --key-id "$ASC_KEY_ID" --issuer "$ASC_ISSUER" --wait
  echo "[release-mac-dmg] stapling notarization ticket"
  xcrun stapler staple "$DMG"
fi

# --- Step 8: upload (optional) -------------------------------------------
if [ "$UPLOAD" -eq 1 ]; then
  echo "[release-mac-dmg] uploading $DMG to GitHub release ${TAG}"
  gh release upload "$TAG" "$DMG" --clobber -R JanDamek/puklic
fi

# --- Step 9: verify -------------------------------------------------------
echo "[release-mac-dmg] verifying signature + Gatekeeper assessment"
codesign --verify --strict /Applications/Puklic.app && echo "  codesign --verify: OK"
spctl -a -vvv /Applications/Puklic.app || echo "  spctl app assessment returned non-zero (see above)"
if [ "$SKIP_NOTARIZE" -eq 0 ]; then
  spctl -a -t open --context context:primary-signature "$DMG" \
    || echo "  spctl dmg assessment returned non-zero (see above)"
fi

echo "[release-mac-dmg] DONE — signed .dmg: $DMG"
