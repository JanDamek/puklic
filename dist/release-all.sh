#!/usr/bin/env bash
# release-all.sh — full Puklic per-release fan-out
# (Linux + macOS + Windows installers via GitHub Actions → AUR → iOS → Mac App Store).
#
# HARD RULE #4 (CLAUDE.md, 2026-05-31): Apple steps run LOCALLY ONLY.
# Linux + Windows + plain macOS .dmg are built by `.github/workflows/build-installers.yml`
# on push of a `v*` tag; that workflow also creates the GitHub Release (as a draft) and
# attaches every installer. We just push the tag, wait for the workflow, publish the
# release (which fires the AUR publish workflow), then run the Apple steps locally.
#
# Version source of truth: gradle.properties `puklic.version`. Every artefact carries
# the same number.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

DRY_RUN=0
SKIP_IOS=0
SKIP_MAC_APP_STORE=0
SKIP_WAIT=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [--dry-run] [--skip-ios] [--skip-mac-app-store] [--skip-wait] [--help]

Full per-release fan-out:
  1. Read VERSION from gradle.properties (puklic.version).
  2. git tag v\$VERSION + push (triggers build-installers.yml on CI:
     Linux .deb + .AppImage, macOS arm64 .dmg, Windows .exe + .msi.
     The workflow's last step creates a DRAFT GitHub Release with the
     artefacts attached).
  3. Poll until the build-installers workflow finishes (skippable).
  4. Publish the draft release via \`gh release edit --draft=false\`,
     which fires aur-publish.yml on the release-published event →
     AUR \`puklic-bin\` PKGBUILD is updated automatically.
  5. dist/apple/release-ios.sh                (iOS .ipa → TestFlight)  [skippable]
  6. dist/apple/macappstore/release-mac.sh    (Mac .pkg → ASC macOS)   [skippable]

Aborts on first failure (set -e).

Options:
  --dry-run             Validate everything but do not push tags / publish releases / upload.
  --skip-ios            Skip step 5 (iOS TestFlight upload).
  --skip-mac-app-store  Skip step 6 (Mac App Store .pkg upload).
  --skip-wait           Skip waiting for build-installers (assume artefacts are ready).
  --help                Show this message.
EOF
}

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --skip-ios) SKIP_IOS=1 ;;
    --skip-mac-app-store) SKIP_MAC_APP_STORE=1 ;;
    --skip-wait) SKIP_WAIT=1 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

EXTRA=()
[ "$DRY_RUN" -eq 1 ] && EXTRA+=(--dry-run)

echo "[release-all] pre-flight: gh CLI"
command -v gh >/dev/null || { echo "  MISSING: gh (brew install gh)" >&2; exit 1; }

VERSION="$(awk -F= '/^puklic\.version=/ {print $2; exit}' "${REPO_ROOT}/gradle.properties" | tr -d '[:space:]')"
[ -n "$VERSION" ] || { echo "[release-all] FAILED: puklic.version missing from gradle.properties" >&2; exit 1; }
TAG="v${VERSION}"
echo "[release-all] release version = ${VERSION} (tag ${TAG})"

# --- Step 1: tag + push ---------------------------------------------------
echo "[release-all] step 1/6: git tag ${TAG}"
if git -C "$REPO_ROOT" rev-parse "$TAG" >/dev/null 2>&1; then
  echo "  tag ${TAG} already exists locally — skipping create"
else
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "  DRY-RUN: would create + push tag ${TAG}"
  else
    git -C "$REPO_ROOT" tag -a "$TAG" -m "Puklic ${VERSION}"
    git -C "$REPO_ROOT" push origin "$TAG"
  fi
fi

# --- Step 2: wait for build-installers workflow ---------------------------
echo "[release-all] step 2/6: wait for build-installers.yml on ${TAG}"
if [ "$DRY_RUN" -eq 1 ] || [ "$SKIP_WAIT" -eq 1 ]; then
  echo "  $([ "$DRY_RUN" -eq 1 ] && echo "DRY-RUN" || echo "--skip-wait"): not polling"
else
  # Give the workflow a moment to register against the new tag.
  sleep 8
  RUN_ID="$(gh run list --workflow=build-installers.yml --branch="$TAG" --limit=1 --json databaseId --jq '.[0].databaseId' || true)"
  if [ -z "${RUN_ID:-}" ]; then
    echo "  WARN: could not find a build-installers run for ${TAG}; check GitHub Actions manually." >&2
  else
    echo "  watching run ${RUN_ID}"
    gh run watch "$RUN_ID" --exit-status
  fi
fi

# --- Step 3: publish the draft release → fires aur-publish.yml -----------
echo "[release-all] step 3/6: publish draft release ${TAG}"
if [ "$DRY_RUN" -eq 1 ]; then
  echo "  DRY-RUN: would gh release edit ${TAG} --draft=false --latest"
else
  # `gh release edit --draft=false` flips the release from draft → published; the
  # release.published webhook fires aur-publish.yml which downloads the .deb from
  # the same release and commits the new PKGBUILD to AUR.
  gh release edit "$TAG" --draft=false --latest || {
    echo "  WARN: release ${TAG} not in draft state (already published or missing)" >&2
  }
fi

# --- Step 4: iOS TestFlight ----------------------------------------------
if [ "$SKIP_IOS" -eq 1 ]; then
  echo "[release-all] step 4/6: iOS TestFlight SKIPPED (--skip-ios)"
else
  echo "[release-all] step 4/6: iOS TestFlight"
  "${SCRIPT_DIR}/apple/release-ios.sh" ${EXTRA[@]+"${EXTRA[@]}"}
fi

# --- Step 5: Mac App Store -----------------------------------------------
if [ "$SKIP_MAC_APP_STORE" -eq 1 ]; then
  echo "[release-all] step 5/6: Mac App Store SKIPPED (--skip-mac-app-store)"
else
  echo "[release-all] step 5/6: Mac App Store"
  "${SCRIPT_DIR}/apple/macappstore/release-mac.sh" ${EXTRA[@]+"${EXTRA[@]}"}
fi

# --- Step 6: final summary -----------------------------------------------
echo "[release-all] step 6/6: done — version ${VERSION} published"
echo "  AUR: https://aur.archlinux.org/packages/puklic-bin"
echo "  GitHub Release: $(gh release view "$TAG" --json url --jq .url 2>/dev/null || echo "https://github.com/JanDamek/puklic/releases/tag/$TAG")"
