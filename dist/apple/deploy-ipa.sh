#!/usr/bin/env bash
# deploy-ipa.sh — upload existing Puklic.ipa to TestFlight (internal) locally.
#
# HARD RULE #4 (CLAUDE.md, 2026-05-31): Apple uploads happen LOCALLY ONLY.
#
# Wraps the fastlane :ios upload_only lane (which calls pilot with
# skip_submission). Input is the .ipa produced by build-ipa.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

ASC_KEY_ID="${ASC_KEY_ID:-6C6D4D726S}"
ASC_ISSUER_ID="${ASC_ISSUER_ID:-69a6de7f-7dab-47e3-e053-5b8c7c11a4d1}"
ASC_KEY_PATH="${ASC_KEY_PATH:-${HOME}/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8}"
TEAM_ID="${TEAM_ID:-GR74KSG8M9}"
BUNDLE_ID="${BUNDLE_ID:-cz.damek.puklic.app}"
IPA_PATH="${IPA_PATH:-${REPO_ROOT}/build/ios-archive/Puklic.ipa}"

DRY_RUN=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [--dry-run] [--help]

Uploads an existing .ipa to TestFlight (internal testers; skip_submission).
Does NOT build. Use build-ipa.sh first, or release-ios.sh for both.

Options:
  --dry-run   Run pre-flight + print fastlane command without uploading.
  --help      Show this message.

Environment (defaults shown):
  ASC_KEY_ID       ${ASC_KEY_ID}
  ASC_ISSUER_ID    ${ASC_ISSUER_ID}
  ASC_KEY_PATH     ${ASC_KEY_PATH}
  TEAM_ID          ${TEAM_ID}
  BUNDLE_ID        ${BUNDLE_ID}
  IPA_PATH         ${IPA_PATH}

Required local prerequisites:
  - ASC API key file at \$ASC_KEY_PATH
  - .ipa at \$IPA_PATH (produced by build-ipa.sh)
  - bundle install run
EOF
}

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

echo "[deploy-ipa] pre-flight: ASC API key"
[ -f "$ASC_KEY_PATH" ] || { echo "  MISSING: $ASC_KEY_PATH" >&2; exit 1; }

echo "[deploy-ipa] pre-flight: .ipa present"
[ -f "$IPA_PATH" ] || { echo "  MISSING: $IPA_PATH — run build-ipa.sh first" >&2; exit 1; }

echo "[deploy-ipa] pre-flight: bundler / fastlane"
command -v bundle >/dev/null || { echo "  MISSING: bundler (gem install bundler)" >&2; exit 1; }

export ASC_KEY_ID ASC_ISSUER_ID ASC_KEY_PATH TEAM_ID BUNDLE_ID IPA_PATH

CMD=(bundle exec fastlane ios upload_only)

if [ "$DRY_RUN" -eq 1 ]; then
  echo "[deploy-ipa] DRY-RUN: would execute (cwd=${REPO_ROOT}):"
  printf '  %q' "${CMD[@]}"; echo
  echo "[deploy-ipa] DRY-RUN: would upload ${IPA_PATH} to TestFlight internal"
  exit 0
fi

cd "$REPO_ROOT"
"${CMD[@]}"
echo "[deploy-ipa] OK: uploaded ${IPA_PATH}"
