#!/usr/bin/env bash
# build-ipa.sh — archive Puklic iOS app to a signed .ipa locally.
#
# HARD RULE #4 (CLAUDE.md, 2026-05-31): Apple builds happen LOCALLY ONLY.
# No GitHub Actions, no GitHub Secrets, no remote runner.
#
# Wraps the fastlane :ios build_only lane and adds local-only pre-flight
# checks. Designed to be re-runnable; fails fast on missing prerequisites.
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
EXPECTED_IPA="${REPO_ROOT}/build/ios-archive/Puklic.ipa"

DRY_RUN=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [--dry-run] [--help]

Archives the iOS app into a signed .ipa via fastlane :ios build_only.
Does NOT upload. Use deploy-ipa.sh or release-ios.sh for upload.

Options:
  --dry-run   Run pre-flight checks + print the fastlane command without building.
  --help      Show this message.

Environment (defaults shown):
  ASC_KEY_ID       ${ASC_KEY_ID}
  ASC_ISSUER_ID    ${ASC_ISSUER_ID}
  ASC_KEY_PATH     ${ASC_KEY_PATH}
  TEAM_ID          ${TEAM_ID}
  BUNDLE_ID        ${BUNDLE_ID}

Required local prerequisites:
  - Keychain identity: ${IOS_DIST_IDENTITY}
  - Provisioning profile in ~/Library/MobileDevice/Provisioning Profiles/
  - ASC API key file at \$ASC_KEY_PATH
  - dist/apple/ExportOptions-AppStore.filled.plist present (gitignored)
  - bundle install run (fastlane gems installed)

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
security find-identity -v -p codesigning | grep -q "$IOS_DIST_IDENTITY" \
  || { echo "  MISSING keychain identity: ${IOS_DIST_IDENTITY}" >&2; exit 1; }

echo "[build-ipa] pre-flight: provisioning profile present"
PROFILE_DIR="${HOME}/Library/MobileDevice/Provisioning Profiles"
[ -d "$PROFILE_DIR" ] && find "$PROFILE_DIR" -name "*.mobileprovision" -print -quit | grep -q . \
  || { echo "  MISSING: no .mobileprovision in ${PROFILE_DIR}" >&2; exit 1; }

echo "[build-ipa] pre-flight: ExportOptions filled plist"
EXPORT_PLIST="${REPO_ROOT}/dist/apple/ExportOptions-AppStore.filled.plist"
[ -f "$EXPORT_PLIST" ] || { echo "  MISSING: ${EXPORT_PLIST} (fill from template, see dist/apple/README.md)" >&2; exit 1; }

echo "[build-ipa] pre-flight: bundler / fastlane"
command -v bundle >/dev/null || { echo "  MISSING: bundler (gem install bundler)" >&2; exit 1; }

export ASC_KEY_ID ASC_ISSUER_ID ASC_KEY_PATH TEAM_ID BUNDLE_ID

CMD=(bundle exec fastlane ios build_only)

if [ "$DRY_RUN" -eq 1 ]; then
  echo "[build-ipa] DRY-RUN: would execute (cwd=${REPO_ROOT}):"
  printf '  %q' "${CMD[@]}"; echo
  echo "[build-ipa] DRY-RUN: would produce ${EXPECTED_IPA}"
  exit 0
fi

cd "$REPO_ROOT"
"${CMD[@]}"

[ -f "$EXPECTED_IPA" ] || { echo "[build-ipa] FAILED: expected ${EXPECTED_IPA} not produced" >&2; exit 1; }
echo "[build-ipa] OK: ${EXPECTED_IPA}"
