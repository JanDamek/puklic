# AUR Linux Distribution — Step 1 Analysis (2026-05-25, issue #20)

## Summary

AUR (Arch User Repository) becomes the **primary Linux distribution channel** per user mandate. We publish **`puklic-bin`** (pre-built binary, downloads `.deb` from GitHub Release + repackages as `.pkg.tar.zst`). Auto-incrementing `pkgrel` via committed file `dist/aur/pkgrel`. CI uses `KSXGitHub/github-actions-deploy-aur` (1k+ stars, MIT, active). User must do one-time AUR account + SSH key setup before Step 2.

## A. Current build state

- Compose Desktop emits `.deb` + `.AppImage` for Linux (build.gradle.kts:62)
- Version `0.1.0` hardcoded (build.gradle.kts:69)
- CI workflow `.github/workflows/build-installers.yml` on push/tag/manual

## B. Library survey

| Component | Choice | Source | License | Verdict |
|---|---|---|---|---|
| **AUR publish action** | `KSXGitHub/github-actions-deploy-aur` | github | MIT | CHOSEN — most stars, active, wide adoption |
| Alternative | `JonasPammer/aur-publish-action` | github | MIT | smaller community, defer |
| Alternative | `aksh-d/aur-publish-action` | github | MIT | low activity |
| Alternative | `0xrishabh/aur-pkg-cd-action` | github | MIT | unmaintained 2023 |
| **.deb → .pkg.tar.zst** | Manual PKGBUILD `dpkg -x` + repack | bash | n/a | CHOSEN — no external tool, CI-friendly |
| Alternative | `debtap` | AUR | n/a | medium reliability, overkill for CI |
| Alternative | jpackage `--type pkg-tar-zst` | OpenJDK | n/a | doesn't exist for Arch |
| **Package name** | `puklic-bin` (binary) | Arch convention | n/a | CHOSEN — bundled JRE/natives too heavy for source build |
| Alternative | `puklic` (source) | Arch convention | n/a | defer to v2 if demand |
| **pkgrel storage** | Committed file `dist/aur/pkgrel` | custom | n/a | CHOSEN — auditable, CI-resilient |
| Alternative | `github.run_number` | GH Actions | n/a | resets on workflow rename |
| Alternative | commits since tag | git | n/a | breaks on rebase |

## C. Versioning semantics

- `pkgver=0.1.0` (matches git tag `v0.1.0` stripped)
- `pkgrel=1` (initial), increment on PKGBUILD changes same upstream version
- Reset `pkgrel=1` when `pkgver` changes (next tag bump)

## D. PKGBUILD structure

```bash
pkgname=puklic-bin
pkgver=0.1.0
pkgrel=1
pkgdesc="Lightweight native Discord client (Compose Multiplatform)"
arch=('x86_64')
url="https://github.com/JanDamek/puklic"
license=('GPL3')  # binary GPL-3.0 from libx264 + Wire core-crypto

depends=('libsecret' 'libdbus')
optdepends=(
    'libayatana-appindicator: System tray icon support'
    'pipewire: Voice chat support'
    'xdg-desktop-portal: Screen sharing'
    'wl-clipboard: Wayland clipboard'
)

source=("${pkgname%-bin}-${pkgver}.deb::https://github.com/JanDamek/puklic/releases/download/v${pkgver}/puklic_${pkgver}-1_amd64.deb")
sha256sums=('SKIP')  # v1; signed releases in v2

package() {
    cd "$srcdir"
    dpkg -x "puklic-${pkgver}.deb" "$pkgdir"
}

install="${pkgname}.install"

# WARNING: This is a third-party Discord client. Use at your own risk.
# Discord ToS prohibits third-party user clients. Account suspension risk.
```

## E. PKGBUILD install script

`dist/aur/puklic-bin.install`:
```bash
post_install() {
    update-desktop-database -q /usr/share/applications || true
    gtk-update-icon-cache -q /usr/share/icons/hicolor || true
}
post_upgrade() { post_install; }
post_remove() { post_install; }
```

## F. CI integration

New job in `.github/workflows/build-installers.yml` (runs after `release` on tag push):

```yaml
aur-publish:
  needs: [release]
  if: startsWith(github.ref, 'refs/tags/v')
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - name: Update PKGBUILD pkgver
      run: |
        TAG="${GITHUB_REF#refs/tags/v}"
        sed -i "s/^pkgver=.*/pkgver=${TAG}/" dist/aur/PKGBUILD
    - name: Publish to AUR
      uses: KSXGitHub/github-actions-deploy-aur@v3
      with:
        pkgname: puklic-bin
        pkgbuild: dist/aur/PKGBUILD
        ssh_private_key: ${{ secrets.AUR_SSH_PRIVATE_KEY }}
        commit_username: puklic-bot
        commit_email: puklic-bot@noreply
        commit_message: "v${{ github.ref_name }}: automated update"
```

## G. License declaration

Puklic binary distribution is **GPL-3.0-or-later** due to bundled libx264 (FFmpeg GPL) + Wire core-crypto (MLS, GPL-3.0).

`license=('GPL3')` in PKGBUILD. Source remains Apache-2.0.

## H. Discord ToS warning

Required in PKGBUILD comment header + `puklic-bin.install` post_install message. Link to README's "Discord protocol — risk acknowledgement" section.

## I. User prerequisites (BLOCKING for Step 2)

**Priority 1 (one-time manual setup):**
1. Create AUR account at https://aur.archlinux.org/register
2. Generate Ed25519 SSH keypair: `ssh-keygen -t ed25519 -f ~/.ssh/aur_rsa -N ""`
3. Add public key (`cat ~/.ssh/aur_rsa.pub`) to AUR account settings
4. Add SSH private key (`cat ~/.ssh/aur_rsa`) to GitHub repo Secrets as `AUR_SSH_PRIVATE_KEY`
5. Confirm `puklic-bin` as package name (vs `puklic` source variant)

**Priority 2 (Step 2 implementation):**
6. Create `dist/aur/PKGBUILD` from template
7. Create `dist/aur/puklic-bin.install`
8. Create `dist/aur/pkgrel` (initial content: `1`)
9. Update build-installers.yml with `aur-publish` job
10. Tag pre-release `v0.1.1-rc.1` for first AUR push smoke test

## J. Phasing

- **Phase 1 (v0.1.0+):** GitHub Releases + AUR `puklic-bin` only
- **Phase 2:** Flathub (vetting required), source PKGBUILD (`puklic`) if demand
- **Phase 3:** Snap (lower priority, less Arch-native)

## K. Risks

1. **Account ban (Discord ToS)** — HIGH user-side. Warn in PKGBUILD header + .install post_install + README.
2. **Stale PKGBUILD** — CI shellcheck/syntax lint test.
3. **Broken GitHub Release link** — accept if puklic deleted; document as risk.
4. **SSH key compromise** — GitHub Secrets encrypted at rest; rotate after suspicious activity. Key restricted to AUR-only.
5. **AUR orphan** — auto-orphan after 6 months inactive. Document co-maintainer in .install.

## L. Must-do before Step 2

See section I prerequisites. **Items 1-5 are blocking** — no Step 2 design until done.
