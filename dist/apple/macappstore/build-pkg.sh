#!/usr/bin/env bash
# build-pkg.sh — package Puklic for the Mac App Store as a signed .pkg locally.
#
# HARD RULE #4 (CLAUDE.md, 2026-05-31): Apple builds happen LOCALLY ONLY.
#
# Invokes Gradle :desktop:app:verifyMacAppStoreNoGplDeps and
# :desktop:app:packageMacAppStore (jpackage --type pkg --mac-app-store).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

ASC_KEY_ID="${ASC_KEY_ID:-6C6D4D726S}"
ASC_ISSUER_ID="${ASC_ISSUER_ID:-69a6de7f-7dab-47e3-e053-5b8c7c11a4d1}"
ASC_KEY_PATH="${ASC_KEY_PATH:-${HOME}/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8}"
TEAM_ID="${TEAM_ID:-GR74KSG8M9}"
BUNDLE_ID="${BUNDLE_ID:-cz.damek.puklic.app}"
MAC_APP_IDENTITY="3rd Party Mac Developer Application: Jan Damek (${TEAM_ID})"
MAC_INSTALLER_IDENTITY="3rd Party Mac Developer Installer: Jan Damek (${TEAM_ID})"
PKG_DIR="${REPO_ROOT}/desktop/app/build/macAppStore/pkg"

DRY_RUN=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [--dry-run] [--help]

Builds a signed Mac App Store .pkg via Gradle (jpackage --type pkg --mac-app-store).
Does NOT upload. Use deploy-pkg.sh or release-mac.sh for upload.

Options:
  --dry-run   Run pre-flight + print gradle command without building.
  --help      Show this message.

Environment (defaults shown):
  ASC_KEY_ID       ${ASC_KEY_ID}
  ASC_ISSUER_ID    ${ASC_ISSUER_ID}
  ASC_KEY_PATH     ${ASC_KEY_PATH}
  TEAM_ID          ${TEAM_ID}
  BUNDLE_ID        ${BUNDLE_ID}

Required local prerequisites:
  - Keychain identity: ${MAC_APP_IDENTITY}
  - Keychain identity: ${MAC_INSTALLER_IDENTITY}
  - ASC API key file at \$ASC_KEY_PATH (used downstream by deploy-pkg.sh)
  - Mac App Store provisioning profile in ~/Library/MobileDevice/Provisioning Profiles/

Output:
  ${PKG_DIR}/Puklic-<version>.pkg
EOF
}

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

echo "[build-pkg] pre-flight: ASC API key"
[ -f "$ASC_KEY_PATH" ] || { echo "  MISSING: $ASC_KEY_PATH" >&2; exit 1; }

echo "[build-pkg] pre-flight: Mac App Distribution identity"
security find-identity -v | grep -q "$MAC_APP_IDENTITY" \
  || { echo "  MISSING keychain identity: ${MAC_APP_IDENTITY}" >&2; exit 1; }

echo "[build-pkg] pre-flight: Mac Installer Distribution identity"
security find-identity -v | grep -q "$MAC_INSTALLER_IDENTITY" \
  || { echo "  MISSING keychain identity: ${MAC_INSTALLER_IDENTITY}" >&2; exit 1; }

echo "[build-pkg] pre-flight: gradle wrapper"
[ -x "${REPO_ROOT}/gradlew" ] || { echo "  MISSING: ${REPO_ROOT}/gradlew" >&2; exit 1; }

export ASC_KEY_ID ASC_ISSUER_ID ASC_KEY_PATH TEAM_ID BUNDLE_ID

CMD=(./gradlew :desktop:app:verifyMacAppStoreNoGplDeps :desktop:app:packageMacAppStore)

if [ "$DRY_RUN" -eq 1 ]; then
  echo "[build-pkg] DRY-RUN: would execute (cwd=${REPO_ROOT}):"
  printf '  %q' "${CMD[@]}"; echo
  echo "[build-pkg] DRY-RUN: would produce ${PKG_DIR}/Puklic-<version>.pkg"
  exit 0
fi

cd "$REPO_ROOT"
# Gradle's configuration-cache STORE step fails on packageMacAppStore (known
# pre-existing issue per FP-14f-fix report), but the task itself runs to
# completion and produces the .pkg. So we tolerate gradle's non-zero exit
# code as long as the .pkg file exists afterwards.
set +e
"${CMD[@]}"
GRADLE_EXIT=$?
set -e

PKG_FILE="$(ls -1 "${PKG_DIR}"/Puklic-*.pkg 2>/dev/null | head -1 || true)"
if [ -z "$PKG_FILE" ]; then
  echo "[build-pkg] FAILED: no .pkg produced in ${PKG_DIR} (gradle exit ${GRADLE_EXIT})" >&2
  exit 1
fi
echo "[build-pkg] gradle output: ${PKG_FILE} (gradle exit ${GRADLE_EXIT}, tolerated)"

# Post-process: jpackage's Info.plist override is silently ignored on some JDK
# 21 builds → LSMinimumSystemVersion lands as 10.11, which Apple rejects for
# arm64-only Mac App Store apps (must be 12.0+). Also embed the Mac App Store
# provisioning profile into the .app bundle (TestFlight requires it). Re-sign
# the .app + re-build the .pkg.
PROFILE="${HOME}/Library/MobileDevice/Provisioning Profiles/Puklic_Mac_App_Store.provisionprofile"
[ -f "$PROFILE" ] || { echo "[build-pkg] FAILED: missing provisioning profile at ${PROFILE}" >&2; exit 1; }

echo "[build-pkg] post-process: expanding .pkg for Info.plist patch + profile embed"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
pkgutil --expand-full "$PKG_FILE" "$WORK/expanded" >/dev/null

APP_PATH="$(find "$WORK/expanded" -maxdepth 4 -name "Puklic.app" -type d | head -1)"
[ -n "$APP_PATH" ] || { echo "[build-pkg] FAILED: Puklic.app not found inside expanded pkg" >&2; exit 1; }

INFO_PLIST="${APP_PATH}/Contents/Info.plist"
echo "[build-pkg] post-process: setting LSMinimumSystemVersion=13.0 in ${INFO_PLIST}"
plutil -replace LSMinimumSystemVersion -string "13.0" "$INFO_PLIST"

# Force CFBundleVersion to match CFBundleShortVersionString.
# Apple's productbuild generates a pkg-ref Distribution.xml that uses
# CFBundleShortVersionString as the request version. If CFBundleVersion
# differs (e.g. monotonic timestamp), altool rejects the upload with error
# 90345 ("Info.plist value mismatch"). To upload a new build, bump
# puklic.version in gradle.properties (e.g. 1.2.2 → 1.2.3) and re-run.
SHORT_VERSION="$(plutil -extract CFBundleShortVersionString raw "$INFO_PLIST")"
echo "[build-pkg] post-process: aligning CFBundleVersion to ${SHORT_VERSION}"
plutil -replace CFBundleVersion -string "$SHORT_VERSION" "$INFO_PLIST"

echo "[build-pkg] post-process: embedding provisioning profile"
cp "$PROFILE" "${APP_PATH}/Contents/embedded.provisionprofile"

ENTITLEMENTS="${REPO_ROOT}/dist/apple/macappstore/Puklic.entitlements"
echo "[build-pkg] post-process: re-signing .app with entitlements"
codesign --force --options runtime --timestamp \
  --sign "$MAC_APP_IDENTITY" \
  --entitlements "$ENTITLEMENTS" \
  --deep \
  "$APP_PATH"

echo "[build-pkg] post-process: building productbuild .pkg + signing"
UNSIGNED_PKG="$WORK/Puklic-unsigned.pkg"
SIGNED_PKG="$WORK/Puklic-signed.pkg"
productbuild --component "$APP_PATH" /Applications "$UNSIGNED_PKG"
productsign --sign "$MAC_INSTALLER_IDENTITY" "$UNSIGNED_PKG" "$SIGNED_PKG"

echo "[build-pkg] post-process: replacing original .pkg"
cp "$SIGNED_PKG" "$PKG_FILE"
echo "[build-pkg] OK: ${PKG_FILE}"
