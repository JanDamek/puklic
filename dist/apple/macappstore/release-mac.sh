#!/usr/bin/env bash
# release-mac.sh — full local Mac App Store release: build .pkg, then upload.
#
# HARD RULE #4 (CLAUDE.md, 2026-05-31): Apple distribution is LOCAL ONLY.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DRY_RUN=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [--dry-run] [--help]

Runs build-pkg.sh then deploy-pkg.sh. Both must succeed.

Options:
  --dry-run   Propagated to children; no .pkg built, no upload.
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

echo "[release-mac] step 1/2: build-pkg.sh"
"${SCRIPT_DIR}/build-pkg.sh" ${EXTRA[@]+"${EXTRA[@]}"}

echo "[release-mac] step 2/2: deploy-pkg.sh"
"${SCRIPT_DIR}/deploy-pkg.sh" ${EXTRA[@]+"${EXTRA[@]}"}

echo "[release-mac] OK"
