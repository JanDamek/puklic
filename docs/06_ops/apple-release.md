# Apple release runbook

> **HARD RULE #4 (CLAUDE.md, 2026-05-31):** Apple distribution is LOCAL ONLY.
> All commands below run from a developer Mac. There is no CI variant.

> **HARD RULE (CLAUDE.md, 2026-06-01):** Apple Distribution cert is ONE
> shared cert for all apps under one Apple Developer Team ID. Puklic's
> provisioning profile embeds the team-shared SHA1 pinned in
> `iosApp/project.yml`; never regenerate via App Store Connect web UI.

Architect report:
[`../03_infrastructure/architect-reports/2026-05-31-apple-local-only.md`](../03_infrastructure/architect-reports/2026-05-31-apple-local-only.md).

## Apple Distribution cert — team-shared (single cert for all apps under team GR74KSG8M9)

Apple Distribution cert is **shared** across every iOS / macOS app published
under Team ID `GR74KSG8M9` — ASC's hard limit of 2 active certs per type
makes per-application certs unworkable at scale. Every project's
provisioning profile embeds the same SHA1.

| Field | Value |
|-------|-------|
| Display name | `Apple Distribution: Jan Damek (GR74KSG8M9)` |
| **SHA1 (pinned)** | `87C2C002603CAACDC619BA32762945AB03C0BCA0` |
| Team ID | `GR74KSG8M9` |
| Used by (apps) | Puklic (cz.damek.puklic.app), Jervis (com.jervis.*), … |
| Puklic provisioning profile | `Puklic App Store` (UUID `e9ae0eef-e9b8-47c0-9391-7430d4ccaad2`) |
| Pinned in this repo | `iosApp/project.yml` `CODE_SIGN_IDENTITY` |
| Profile expiry | 2027-05-28 |
| Cert expiry | check `security find-certificate -c "Apple Distribution: Jan Damek" -p \| openssl x509 -noout -dates` |
| `.p12` backup | **central 1Password vault item "Apple Distribution Cert — Team GR74KSG8M9"** (shared with Jervis + other Apple projects) |

### Backup procedure (one-time, central — single backup for all projects)

1. Open **Keychain Access.app** → My Certificates
2. Right-click `Apple Distribution: Jan Damek (GR74KSG8M9)` (SHA1 above) → Export
3. Save as `AppleDistribution-GR74KSG8M9-87C2C002.p12`, set strong passphrase
4. Store the `.p12` + passphrase in **1Password** central vault item
   `Apple Distribution Cert — Team GR74KSG8M9` (same backup referenced by
   Puklic + Jervis + every other Apple project under this team)
5. Verify restore on a fresh keychain before retiring the original Mac

### Forbidden operations

- ❌ **Regenerate** this cert via ASC web UI — revoke is irreversible and
  every project under team GR74KSG8M9 with profiles embedding this SHA1
  will fail to sign until profiles are regenerated against the replacement.
- ❌ **Create a duplicate** Apple Distribution cert (a second "Apple
  Distribution: Jan Damek" with a different SHA1) — keychain ambiguity
  causes codesign to pick the wrong one and builds fail.
- ❌ **Delete** any cert in keychain marked `Apple Distribution: Jan Damek`
  as "duplicate" — every SHA1 corresponds to a real cert that is or was
  active in ASC. Verify first.
- ❌ **Use generic `"Apple Distribution"`** as `CODE_SIGN_IDENTITY` — falls
  back to the keychain's first match by display name, which is non-
  deterministic when more than one cert with that subject exists.

### If `security find-identity` no longer shows SHA1 `87C2…`

1. **STOP** building — do not regenerate
2. Restore from 1Password central backup:
   `security import AppleDistribution-GR74KSG8M9-87C2C002.p12 -P <passphrase> -k login.keychain-db -T /usr/bin/codesign`
3. Verify: `security find-identity -v -p codesigning | grep 87C2C002`
4. Re-run `dist/apple/build-ipa.sh`

### Rotation (cert approaching 5-year expiry — plan 30 days ahead)

1. ASC → Certificates → New → Apple Distribution → use the empty backup slot
2. Download new `.cer`, install in keychain
3. Export new `.p12` → replace 1Password central backup (keep old in a versioned attachment until full migration complete)
4. Update SHA1 pins in every Apple project's build config (search every repo for the old SHA1, replace with the new one)
5. Regenerate every project's provisioning profile to embed the new cert
6. After all projects build green on the new cert → revoke the old one in ASC

## Pre-release checklist (one-time per Mac)

- [ ] Xcode command line tools installed (`xcode-select --install`)
- [ ] `~/.appstoreconnect/private_keys/AuthKey_6C6D4D726S.p8` present
- [ ] Keychain identity `Apple Distribution: Jan Damek (GR74KSG8M9)`
- [ ] Keychain identity `3rd Party Mac Developer Application: Jan Damek (GR74KSG8M9)`
- [ ] Keychain identity `3rd Party Mac Developer Installer: Jan Damek (GR74KSG8M9)`
- [ ] iOS provisioning profile in `~/Library/MobileDevice/Provisioning Profiles/`
- [ ] Mac App Store provisioning profile in same directory
- [ ] `bundle install` succeeded in repo root
- [ ] `dist/apple/ExportOptions-AppStore.filled.plist` present (gitignored)
- [ ] `gh` CLI authenticated (for AUR workflow trigger)
- [ ] `puklic.version` in `gradle.properties` bumped vs last release

## Per-release flow

### Full fan-out (recommended)

```bash
# Dry-run first — validates all prerequisites without shipping.
dist/release-all.sh --dry-run

# Real release.
dist/release-all.sh
```

This runs:

1. Linux `.deb` + `.AppImage` via Gradle.
2. AUR publish workflow via `gh workflow run aur-publish.yml --ref main`
   (AUR has no Apple credentials, allowed on GitHub Actions per HARD RULE #4).
3. iOS `.ipa` build + TestFlight upload via `dist/apple/release-ios.sh`.
4. Mac App Store `.pkg` build + ASC upload via
   `dist/apple/macappstore/release-mac.sh`.

`set -e` aborts on the first failure.

### Apple only

```bash
dist/apple/release-ios.sh             # iOS .ipa → TestFlight internal
dist/apple/macappstore/release-mac.sh # Mac .pkg → ASC macOS platform
```

### Finer control

```bash
dist/apple/build-ipa.sh                  # archive only
dist/apple/deploy-ipa.sh                 # upload an existing .ipa
dist/apple/macappstore/build-pkg.sh      # build .pkg only
dist/apple/macappstore/deploy-pkg.sh     # upload an existing .pkg
```

## Post-release verification

1. **TestFlight (iOS):**
   - App Store Connect → Apps → Puklic → TestFlight → check build appears
     with status `Processing`. Typically clears in 5-20 minutes.
   - When ready: Internal Testing → add tester group.
   - First-ever upload for a new App Store Connect app record may require
     a one-time Beta App Review submission; Apple auto-routes this.
2. **Mac App Store:**
   - App Store Connect → Apps → Puklic → macOS → check build appears in
     `Processing`.
   - Once processed (10-60 minutes), submit for review or assign to internal
     testers via TestFlight (macOS) — same flow as iOS.
3. **AUR:** `aur-publish.yml` triggers `aur.archlinux.org` push; verify at
   `https://aur.archlinux.org/packages/puklic`.
4. **Linux GitHub Releases:** if releasing via tag, the `build-installers.yml`
   workflow attaches `.deb` / `.AppImage` / `.dmg` / `.exe` to the release.

## Failure modes

| Symptom | Cause | Remedy |
|---|---|---|
| Pre-flight: `MISSING keychain identity` | Cert not in login keychain | Install via Apple Developer portal → double-click `.cer`, or restore from `.p12` backup. |
| Pre-flight: `MISSING: AuthKey_<KID>.p8` | ASC API key absent | Download from App Store Connect → Users and Access → Keys, save under `~/.appstoreconnect/private_keys/`. |
| `altool` rejects `.pkg`: "Bundle version must be higher" | Version not bumped | Edit `gradle.properties` → `puklic.version`; rebuild. |
| `productbuild` prompts for keychain password mid-build | Identity ACL doesn't pre-trust productbuild | Click "Always allow" once; or in Keychain Access set cert Access Control → Always allow. |
| `bundle exec fastlane` complains about missing gems | `bundle install` not run | Run `bundle install` at repo root. |
| `gh workflow run` denied | Token without `workflow` scope | `gh auth refresh -s workflow`. |

## What is forbidden

- ❌ Re-introducing `.github/workflows/apple-*.yml` or `.github/workflows/mac-app-store.yml`.
- ❌ Adding any GitHub Secret matching `ASC_*`, `APPLE_DIST_*`,
  `MAC_APP_DIST_*`, `MAC_INSTALLER_DIST_*`, `MAC_PROVISIONING_*`,
  `APPLE_PROVISIONING_*`.
- ❌ Running fastlane lanes that upload from a remote runner.
- ❌ Uploading the ASC `.p8` or any `.p12` to a third-party service.

See HARD RULE #4 in `CLAUDE.md` and the architect report linked above.
