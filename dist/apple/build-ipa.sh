#!/usr/bin/env bash
# build-ipa.sh — archive Puklic iOS app to a signed .ipa locally.
#
# HARD RULE #4 (CLAUDE.md, 2026-05-31): Apple builds happen LOCALLY ONLY.
# HARD RULE #2: NEVER TEMPORARY. This script is the conceptual replacement
# for fastlane gym's `build_app` — gym invokes a buggy Xcode 26.5 codesign
# command that misreports `embedded.mobileprovision` as an unsigned code
# subcomponent (issue #73). The flow below archives WITHOUT signing, then
# runs `codesign --deep` ourselves, then zips the Payload manually. This
# gives us full control over the signing call, independent of Xcode/gym.
#
# Flow:
#   1. ./gradlew :ios:app:linkReleaseFrameworkIosArm64
#   2. xcodebuild archive (CODE_SIGNING_ALLOWED=NO)
#   3. codesign --deep the .app inside the .xcarchive
#   4. assemble Payload/iosApp.app and zip → Puklic.ipa
#
# Output: build/ios-archive/Puklic.ipa
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

ASC_KEY_ID="${ASC_KEY_ID:-6C6D4D726S}"
ASC_ISSUER_ID="${ASC_ISSUER_ID:-69a6de7f-7dab-47e3-e053-5b8c7c11a4d1}"
ASC_KEY_PATH="${ASC_KEY_PATH:-${HOME}/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8}"
TEAM_ID="${TEAM_ID:-GR74KSG8M9}"
BUNDLE_ID="${BUNDLE_ID:-cz.damek.puklic.app}"
IOS_DIST_IDENTITY="Apple Distribution: Jan Damek (${TEAM_ID})"
PROFILE_NAME="Puklic App Store"
PROFILE_DIR="${HOME}/Library/MobileDevice/Provisioning Profiles"
ENTITLEMENTS="${REPO_ROOT}/iosApp/iosApp/iosApp.entitlements"

OUT_DIR="${REPO_ROOT}/build/ios-archive"
ARCHIVE_PATH="${OUT_DIR}/Puklic.xcarchive"
EXPECTED_IPA="${OUT_DIR}/Puklic.ipa"

DRY_RUN=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [--dry-run] [--help]

Archives the iOS app into a signed .ipa via a custom xcodebuild + codesign
flow. Does NOT upload. Use deploy-ipa.sh or release-ios.sh for upload.

Options:
  --dry-run   Run pre-flight checks + print commands without building.
  --help      Show this message.

Environment (defaults shown):
  ASC_KEY_ID       ${ASC_KEY_ID}
  ASC_ISSUER_ID    ${ASC_ISSUER_ID}
  ASC_KEY_PATH     ${ASC_KEY_PATH}
  TEAM_ID          ${TEAM_ID}
  BUNDLE_ID        ${BUNDLE_ID}

Required local prerequisites:
  - Keychain identity: ${IOS_DIST_IDENTITY}
  - Provisioning profile "${PROFILE_NAME}" in ${PROFILE_DIR}/
  - ASC API key file at \$ASC_KEY_PATH
  - bundle install run (only needed for downstream deploy, not this script)
  - xcodegen (Homebrew) for regenerating iosApp.xcodeproj

Output:
  ${EXPECTED_IPA}
EOF
}

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

echo "[build-ipa] pre-flight: ASC API key"
[ -f "$ASC_KEY_PATH" ] || { echo "  MISSING: $ASC_KEY_PATH" >&2; exit 1; }

echo "[build-ipa] pre-flight: keychain identity"
IDENTITY_SHA1="$(security find-identity -v -p codesigning \
  | grep -F "$IOS_DIST_IDENTITY" \
  | awk '{print $2; exit}')"
[ -n "$IDENTITY_SHA1" ] || { echo "  MISSING keychain identity: ${IOS_DIST_IDENTITY}" >&2; exit 1; }
echo "  identity SHA1: ${IDENTITY_SHA1}"

echo "[build-ipa] pre-flight: provisioning profile"
PROFILE_FILE=""
while IFS= read -r -d '' candidate; do
  if security cms -D -i "$candidate" 2>/dev/null | grep -q "<string>${PROFILE_NAME}</string>"; then
    PROFILE_FILE="$candidate"
    break
  fi
done < <(find "$PROFILE_DIR" -name "*.mobileprovision" -print0)
[ -n "$PROFILE_FILE" ] || { echo "  MISSING profile named '${PROFILE_NAME}' in ${PROFILE_DIR}" >&2; exit 1; }
echo "  profile: ${PROFILE_FILE}"

echo "[build-ipa] pre-flight: entitlements file"
[ -f "$ENTITLEMENTS" ] || { echo "  MISSING: ${ENTITLEMENTS}" >&2; exit 1; }

echo "[build-ipa] pre-flight: xcodegen"
command -v xcodegen >/dev/null || { echo "  MISSING: xcodegen (brew install xcodegen)" >&2; exit 1; }

if [ "$DRY_RUN" -eq 1 ]; then
  echo "[build-ipa] DRY-RUN: would archive to ${ARCHIVE_PATH} and produce ${EXPECTED_IPA}"
  exit 0
fi

mkdir -p "$OUT_DIR"
rm -rf "$ARCHIVE_PATH" "$EXPECTED_IPA" "${OUT_DIR}/Payload" 2>/dev/null || true

echo "[build-ipa] regenerate Xcode project"
(cd "${REPO_ROOT}/iosApp" && xcodegen generate >/dev/null)

echo "[build-ipa] build Kotlin framework"
(cd "$REPO_ROOT" && ./gradlew :ios:app:linkReleaseFrameworkIosArm64)

BUILD_NUMBER="${GITHUB_RUN_NUMBER:-$(date +%s)}"
echo "[build-ipa] bundle version: ${BUILD_NUMBER}"

echo "[build-ipa] xcodebuild build (unsigned, no archive wrapper)"
# Avoid `xcodebuild archive` because Xcode 26.5's archive-finalize stage
# always runs codesign with the project's CODE_SIGN_IDENTITY and hits the
# embedded.mobileprovision regression (issue #73). `xcodebuild build` with
# CODE_SIGNING_ALLOWED=NO produces a clean unsigned .app, which we sign
# ourselves and package as .ipa manually below.
BUILD_DIR="${OUT_DIR}/xcbuild"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
xcodebuild build \
  -project "${REPO_ROOT}/iosApp/iosApp.xcodeproj" \
  -scheme iosApp \
  -configuration Release \
  -destination "generic/platform=iOS" \
  -derivedDataPath "$BUILD_DIR" \
  CURRENT_PROJECT_VERSION="$BUILD_NUMBER" \
  CODE_SIGNING_ALLOWED=NO

APP_PATH="$(find "${BUILD_DIR}/Build/Products" -maxdepth 4 -name "iosApp.app" -type d | head -1)"
[ -n "$APP_PATH" ] && [ -d "$APP_PATH" ] || { echo "[build-ipa] FAILED: iosApp.app not produced under ${BUILD_DIR}" >&2; exit 1; }
echo "  .app: ${APP_PATH}"

FRAMEWORK_PATH="${APP_PATH}/Frameworks/PuklicShared.framework"
[ -d "$FRAMEWORK_PATH" ] || { echo "[build-ipa] FAILED: ${FRAMEWORK_PATH} missing" >&2; exit 1; }

echo "[build-ipa] embed provisioning profile"
cp "$PROFILE_FILE" "${APP_PATH}/embedded.mobileprovision"

echo "[build-ipa] sign framework binary first (Apple submission cert chain)"
codesign --force --timestamp \
  --sign "$IDENTITY_SHA1" \
  --generate-entitlement-der \
  "$FRAMEWORK_PATH"

echo "[build-ipa] sign app bundle (--deep, with profile moved out of bundle)"
# Xcode 26.5 codesign regresses on embedded.mobileprovision (#73): when
# present, it is misclassified as an unsigned code subcomponent and signing
# either fails or produces a signature Apple's submission validator rejects
# (ITMS-90034). Workaround: temporarily move the profile out of the bundle
# while signing, then restore it. The profile is a resource (CMS-signed
# plist), not code; the App Store accepts it without inclusion in the
# CodeDirectory seal.
mv "${APP_PATH}/embedded.mobileprovision" "${OUT_DIR}/embedded.mobileprovision.staged"
codesign --force --timestamp --deep \
  --sign "$IDENTITY_SHA1" \
  --entitlements "$ENTITLEMENTS" \
  --generate-entitlement-der \
  "$APP_PATH"
mv "${OUT_DIR}/embedded.mobileprovision.staged" "${APP_PATH}/embedded.mobileprovision"

echo "[build-ipa] verify signature deep+strict"
codesign --verify --deep --strict --verbose=2 "$APP_PATH"

echo "[build-ipa] assemble Payload + zip → ipa"
PAYLOAD_DIR="${OUT_DIR}/Payload"
rm -rf "$PAYLOAD_DIR"
mkdir -p "$PAYLOAD_DIR"
cp -R "$APP_PATH" "${PAYLOAD_DIR}/iosApp.app"
(cd "$OUT_DIR" && zip -qry --symlinks "Puklic.ipa" "Payload")
rm -rf "$PAYLOAD_DIR"

[ -f "$EXPECTED_IPA" ] || { echo "[build-ipa] FAILED: ${EXPECTED_IPA} not produced" >&2; exit 1; }
echo "[build-ipa] OK: ${EXPECTED_IPA}"
ls -lh "$EXPECTED_IPA"
