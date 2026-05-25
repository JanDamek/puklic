# AUR release runbook

The `puklic-bin` package on the Arch User Repository (AUR) is the primary
distribution channel for Arch Linux users. This document describes the
operational lifecycle.

## Overview

- **Package:** [`puklic-bin`](https://aur.archlinux.org/packages/puklic-bin)
- **Source:** PKGBUILD downloads the official `.deb` from the corresponding
  GitHub Release and repackages it as `.pkg.tar.zst`.
- **Publish workflow:** `.github/workflows/aur-publish.yml`
- **Validation workflow:** `.github/workflows/aur-validate.yml`
- **Files:** everything lives under `dist/aur/`.

## Prerequisites (one-time setup)

Tracked in issue #20. Required before the first AUR push can succeed:

1. **Create an AUR account** at <https://aur.archlinux.org/register>.
2. **Generate a dedicated SSH key** (do not reuse personal keys):
   ```bash
   ssh-keygen -t ed25519 -f ~/.ssh/aur_puklic -C "puklic-bot@aur"
   ```
3. **Register the public key** in the AUR account profile
   (Settings -> SSH Public Key).
4. **Add the private key as a GitHub Secret** named `AUR_SSH_PRIVATE_KEY`
   (Repository Settings -> Secrets and variables -> Actions).
5. (First push only) The AUR repository `puklic-bin` will be created
   automatically by the `KSXGitHub/github-actions-deploy-aur` action when it
   pushes for the first time.

Until step 4 is complete the `aur-publish` workflow will fail with a clear
"missing secret" error. This is intentional — there is no temporary
placeholder; the deploy is gated on the real secret being present.

## Normal release flow (new `pkgver`)

1. Tag a new release in git (e.g. `v0.1.1`) and let
   `build-installers.yml` produce the `.deb` asset on the GitHub Release page.
2. When the GitHub Release is *published* (not just drafted), the
   `aur-publish.yml` workflow fires automatically:
   - reads `dist/aur/pkgrel` (must be `1` for a fresh `pkgver`)
   - updates `PKGBUILD` `pkgver` and `pkgrel`
   - downloads the `.deb` and computes its SHA-256
   - regenerates `.SRCINFO` inside an Arch container
   - pushes the commit to `ssh://aur@aur.archlinux.org/puklic-bin.git`
3. Verify the package appears at
   <https://aur.archlinux.org/packages/puklic-bin>.

## `pkgrel` bump (same `pkgver`, packaging fix only)

If the upstream version is unchanged but the PKGBUILD itself needs an update
(e.g. a missing dependency, install hook fix):

1. Edit `dist/aur/pkgrel` and increment the integer (e.g. `1` -> `2`).
2. Commit and push to `main`.
3. Manually trigger `aur-publish.yml` via
   `gh workflow run aur-publish.yml -f version=<current_pkgver>`.

When the next `pkgver` bump happens, **reset** `dist/aur/pkgrel` back to `1`
in the same commit that updates whatever else needs updating.

## Rollback

If a published AUR push is broken (build failures reported by AUR users):

1. Fix the underlying issue in `dist/aur/` (typically `PKGBUILD`).
2. Bump `dist/aur/pkgrel`.
3. Re-run the publish workflow as described in the `pkgrel` bump section.

There is no first-class "delete release" path — AUR is append-only from our
side. Truly fatal errors should be communicated via the AUR package
comments thread and a fast-follow `pkgrel` bump.

## Validation

`aur-validate.yml` runs on any PR or push touching `dist/aur/`:

- runs `namcap` over the PKGBUILD (static linter)
- regenerates `.SRCINFO` and confirms it matches the committed one
  (prevents drift between `PKGBUILD` and `.SRCINFO`)

A red build here MUST be fixed before merging — otherwise the next release
push will likely fail on AUR's server-side validation.
