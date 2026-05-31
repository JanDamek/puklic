#!/usr/bin/env bash
# release-all.sh — full Puklic per-release fan-out (Linux + AUR + iOS + Mac App Store).
#
# HARD RULE #4 (CLAUDE.md, 2026-05-31): Apple steps run LOCALLY ONLY.
# AUR uses the GitHub Actions workflow because it carries no Apple credentials.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

DRY_RUN=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [--dry-run] [--help]

Full per-release fan-out:
  1. Linux .deb + .AppImage via ./gradlew :desktop:app:packageDeb :desktop:app:packageAppImage
  2. AUR publish workflow via gh workflow run aur-publish.yml --ref main
     (AUR carries no Apple credentials, allowed on GitHub Actions per HARD RULE #4.)
  3. dist/apple/release-ios.sh        (iOS .ipa → TestFlight internal)
  4. dist/apple/macappstore/release-mac.sh   (Mac .pkg → ASC macOS platform)

Aborts on first failure (set -e).

Options:
  --dry-run   Propagated to all children; no artefacts shipped.
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

echo "[release-all] pre-flight: gh CLI for AUR trigger"
command -v gh >/dev/null || { echo "  MISSING: gh (brew install gh)" >&2; exit 1; }

echo "[release-all] pre-flight: gradle wrapper"
[ -x "${REPO_ROOT}/gradlew" ] || { echo "  MISSING: ${REPO_ROOT}/gradlew" >&2; exit 1; }

GRADLE_CMD=(./gradlew :desktop:app:packageDeb :desktop:app:packageAppImage)
AUR_CMD=(gh workflow run aur-publish.yml --ref main)

echo "[release-all] step 1/4: Linux .deb + .AppImage"
if [ "$DRY_RUN" -eq 1 ]; then
  echo "  DRY-RUN: would execute (cwd=${REPO_ROOT}):"
  printf '    %q' "${GRADLE_CMD[@]}"; echo
else
  ( cd "$REPO_ROOT" && "${GRADLE_CMD[@]}" )
fi

echo "[release-all] step 2/4: AUR publish workflow"
if [ "$DRY_RUN" -eq 1 ]; then
  echo "  DRY-RUN: would execute:"
  printf '    %q' "${AUR_CMD[@]}"; echo
else
  "${AUR_CMD[@]}"
fi

echo "[release-all] step 3/4: iOS release"
"${SCRIPT_DIR}/apple/release-ios.sh" "${EXTRA[@]}"

echo "[release-all] step 4/4: Mac App Store release"
"${SCRIPT_DIR}/apple/macappstore/release-mac.sh" "${EXTRA[@]}"

echo "[release-all] OK"
