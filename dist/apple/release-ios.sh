#!/usr/bin/env bash
# release-ios.sh — full local iOS release: build .ipa, then upload to TestFlight.
#
# HARD RULE #4 (CLAUDE.md, 2026-05-31): Apple distribution is LOCAL ONLY.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DRY_RUN=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [--dry-run] [--help]

Runs build-ipa.sh then deploy-ipa.sh. Both must succeed.

Options:
  --dry-run   Propagated to children; no archive, no upload.
  --help      Show this message.
EOF
}

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

EXTRA=()
[ "$DRY_RUN" -eq 1 ] && EXTRA+=(--dry-run)

echo "[release-ios] step 1/2: build-ipa.sh"
"${SCRIPT_DIR}/build-ipa.sh" ${EXTRA[@]+"${EXTRA[@]}"}

echo "[release-ios] step 2/2: deploy-ipa.sh"
"${SCRIPT_DIR}/deploy-ipa.sh" ${EXTRA[@]+"${EXTRA[@]}"}

echo "[release-ios] OK"
