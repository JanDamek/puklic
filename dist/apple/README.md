# `dist/apple/` — Apple distribution scripts (LOCAL ONLY)

> **HARD RULE #4 (CLAUDE.md, 2026-05-31):** Apple distribution is LOCAL ONLY.
> No GitHub Actions workflow may build or upload to App Store Connect. No
> Apple credential (.p8, .p12, .mobileprovision, .provisionprofile) may
> be added as a GitHub Secret. All TestFlight + Mac App Store uploads run
> from a developer Mac.

Architect report:
[`docs/03_infrastructure/architect-reports/2026-05-31-apple-local-only.md`](../../docs/03_infrastructure/architect-reports/2026-05-31-apple-local-only.md).

Runbook: [`docs/06_ops/apple-release.md`](../../docs/06_ops/apple-release.md).

## Scripts

| Script | What |
|---|---|
| `build-ipa.sh` | Archives iOS app → `build/ios-archive/Puklic.ipa` (wraps fastlane `:ios build_only`). |
| `deploy-ipa.sh` | Uploads existing `Puklic.ipa` to TestFlight internal (wraps fastlane `:ios upload_only`). |
| `release-ios.sh` | `build-ipa.sh` then `deploy-ipa.sh`. |
| `macappstore/build-pkg.sh` | Builds signed Mac App Store `.pkg` via `:desktop:app:packageMacAppStore`. |
| `macappstore/deploy-pkg.sh` | Uploads `.pkg` to ASC app `6774288340` (macOS platform) via `xcrun altool`. |
| `macappstore/release-mac.sh` | `build-pkg.sh` then `deploy-pkg.sh`. |

Every script supports `--help` and `--dry-run`. Dry-run runs the pre-flight
checks (cheap, read-only) and prints the underlying command without
building or uploading.

The full per-release fan-out lives at `../release-all.sh` (Linux + AUR +
iOS + Mac App Store from one invocation).

## Template files (kept for first-time setup)

| File | Purpose |
|---|---|
| `ExportOptions-AppStore.plist` | Template for `xcodebuild -exportArchive` placeholders. Copy to `ExportOptions-AppStore.filled.plist` (gitignored). |
| `Fastfile.template` | Historical template; the live `fastlane/Fastfile` is the canonical source. |

## One-time local setup

1. Install Xcode command line tools (`xcode-select --install`).
2. Drop ASC API key at `~/.appstoreconnect/private_keys/AuthKey_6C6D4D726S.p8`.
3. Install Apple Distribution cert + iOS provisioning profile via Xcode
   (Settings → Accounts).
4. Install Mac App Distribution + Mac Installer Distribution certs (Apple
   Developer portal → Certificates → download → double-click).
5. Install `Puklic_Mac_App_Store.provisionprofile` (double-click).
6. `bundle install` in repo root.
7. Fill the export options plist:
   ```bash
   sed -e 's/TEAM_ID_PLACEHOLDER/GR74KSG8M9/g' \
       -e 's/BUNDLE_ID_PLACEHOLDER/cz.damek.puklic.app/g' \
       -e 's/PROVISIONING_PROFILE_NAME_PLACEHOLDER/<your profile name>/g' \
       dist/apple/ExportOptions-AppStore.plist \
       > dist/apple/ExportOptions-AppStore.filled.plist
   ```

## Troubleshooting

- **`productbuild` prompts for keychain password.** macOS prompts when an
  identity ACL doesn't pre-trust `productbuild`. After the first run, tick
  "Always allow" or add `productbuild` to the cert ACL via Keychain Access
  → cert → Get Info → Access Control → Always allow. CI workarounds used
  `security set-key-partition-list`; locally just click through once.
- **Apple Distribution cert expired.** Apple Distribution certs are valid
  for 1 year. Renewal: Xcode → Settings → Accounts → Team → Manage
  Certificates → ➕ Apple Distribution. Existing TestFlight builds keep
  working; only future archives need the new cert.
- **`xcrun altool` rejects the .pkg.** Common causes:
  - Bundle version did not increment vs the last upload — bump
    `puklic.version` in `gradle.properties`.
  - Signing identity mismatch — ensure both 3rd-Party Mac Developer
    Application + Installer certs are present.
  - Provisioning profile expired — re-download from Apple Developer portal.
- **Provisioning profile not found in pre-flight.** Profiles live in
  `~/Library/MobileDevice/Provisioning Profiles/`. Double-click the
  `.mobileprovision` / `.provisionprofile` to install.

## What is NOT here

- ❌ `.github/workflows/apple-testflight.yml` (deleted 2026-05-31, issue #70)
- ❌ `.github/workflows/mac-app-store.yml` (deleted 2026-05-31, issue #70)
- ❌ Any GitHub Secret name matching `ASC_*`, `APPLE_DIST_*`, `MAC_APP_DIST_*`,
  `MAC_INSTALLER_DIST_*`, `MAC_PROVISIONING_*`, `APPLE_PROVISIONING_*`

## Push (APN) infra

Push key provisioning is documented in [`../push/README.md`](../push/README.md).
Push prep is **independent** from TestFlight upload — TestFlight works without
a configured APN key.
