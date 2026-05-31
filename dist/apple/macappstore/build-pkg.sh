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
"${CMD[@]}"

PKG_FILE="$(ls -1 "${PKG_DIR}"/Puklic-*.pkg 2>/dev/null | head -1 || true)"
[ -n "$PKG_FILE" ] || { echo "[build-pkg] FAILED: no .pkg produced in ${PKG_DIR}" >&2; exit 1; }
echo "[build-pkg] OK: ${PKG_FILE}"
