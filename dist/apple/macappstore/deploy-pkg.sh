#!/usr/bin/env bash
# deploy-pkg.sh — upload existing Mac App Store .pkg to ASC locally.
#
# HARD RULE #4 (CLAUDE.md, 2026-05-31): Apple uploads happen LOCALLY ONLY.
#
# Uses xcrun altool --upload-package with the ASC API key. Targets the
# macOS platform of App Store Connect app 6774288340.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

ASC_KEY_ID="${ASC_KEY_ID:-6C6D4D726S}"
ASC_ISSUER_ID="${ASC_ISSUER_ID:-69a6de7f-7dab-47e3-e053-5b8c7c11a4d1}"
ASC_KEY_PATH="${ASC_KEY_PATH:-${HOME}/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8}"
BUNDLE_ID="${BUNDLE_ID:-cz.damek.puklic.app}"
ASC_APPLE_ID="${ASC_APPLE_ID:-6774288340}"
PKG_DIR="${REPO_ROOT}/desktop/app/build/macAppStore/pkg"
PKG_PATH="${PKG_PATH:-}"

DRY_RUN=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [--dry-run] [--help]

Uploads a Mac App Store .pkg to App Store Connect (app ${ASC_APPLE_ID}, macOS platform).
Auto-detects the latest Puklic-*.pkg in ${PKG_DIR} unless \$PKG_PATH is set.

Options:
  --dry-run   Run pre-flight + print altool command without uploading.
  --help      Show this message.

Environment (defaults shown):
  ASC_KEY_ID       ${ASC_KEY_ID}
  ASC_ISSUER_ID    ${ASC_ISSUER_ID}
  ASC_KEY_PATH     ${ASC_KEY_PATH}
  BUNDLE_ID        ${BUNDLE_ID}
  ASC_APPLE_ID     ${ASC_APPLE_ID}
  PKG_PATH         (auto-detected from ${PKG_DIR}/Puklic-*.pkg)

Required local prerequisites:
  - ASC API key file at \$ASC_KEY_PATH
  - .pkg in \$PKG_DIR (produced by build-pkg.sh)
  - Xcode command line tools (xcrun altool)
EOF
}

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

echo "[deploy-pkg] pre-flight: ASC API key"
[ -f "$ASC_KEY_PATH" ] || { echo "  MISSING: $ASC_KEY_PATH" >&2; exit 1; }

echo "[deploy-pkg] pre-flight: ASC API key directory layout"
# altool with --apiKey expects ~/.appstoreconnect/private_keys/AuthKey_<KID>.p8
EXPECTED_KEY_DIR="${HOME}/.appstoreconnect/private_keys"
[ -f "${EXPECTED_KEY_DIR}/AuthKey_${ASC_KEY_ID}.p8" ] \
  || { echo "  MISSING: ${EXPECTED_KEY_DIR}/AuthKey_${ASC_KEY_ID}.p8 (altool requires this exact path)" >&2; exit 1; }

if [ -z "$PKG_PATH" ]; then
  PKG_PATH="$(ls -1t "${PKG_DIR}"/Puklic-*.pkg 2>/dev/null | head -1 || true)"
fi
echo "[deploy-pkg] pre-flight: .pkg present"
[ -n "$PKG_PATH" ] && [ -f "$PKG_PATH" ] \
  || { echo "  MISSING: no Puklic-*.pkg in ${PKG_DIR} (run build-pkg.sh first)" >&2; exit 1; }

echo "[deploy-pkg] pre-flight: xcrun altool"
command -v xcrun >/dev/null || { echo "  MISSING: xcrun (install Xcode command line tools)" >&2; exit 1; }

CMD=(xcrun altool --upload-package "$PKG_PATH"
     --type macos
     --apple-id "$ASC_APPLE_ID"
     --bundle-id "$BUNDLE_ID"
     --bundle-short-version-string "auto"
     --bundle-version "auto"
     --apiKey "$ASC_KEY_ID"
     --apiIssuer "$ASC_ISSUER_ID")

if [ "$DRY_RUN" -eq 1 ]; then
  echo "[deploy-pkg] DRY-RUN: would execute:"
  printf '  %q' "${CMD[@]}"; echo
  echo "[deploy-pkg] DRY-RUN: would upload ${PKG_PATH} to ASC app ${ASC_APPLE_ID} (macOS)"
  exit 0
fi

# Resolve version strings from the .pkg filename (Puklic-<version>.pkg).
PKG_BASENAME="$(basename "$PKG_PATH" .pkg)"
VERSION="${PKG_BASENAME#Puklic-}"
if [ -n "$VERSION" ] && [ "$VERSION" != "$PKG_BASENAME" ]; then
  CMD=(xcrun altool --upload-package "$PKG_PATH"
       --type macos
       --apple-id "$ASC_APPLE_ID"
       --bundle-id "$BUNDLE_ID"
       --bundle-short-version-string "$VERSION"
       --bundle-version "$VERSION"
       --apiKey "$ASC_KEY_ID"
       --apiIssuer "$ASC_ISSUER_ID")
fi

"${CMD[@]}"
echo "[deploy-pkg] OK: uploaded ${PKG_PATH}"
