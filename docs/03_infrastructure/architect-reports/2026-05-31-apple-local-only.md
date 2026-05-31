# Apple distribution — LOCAL-ONLY refactor

Date: 2026-05-31
Issue: #70
Author: architect role (orchestrated)

## 1. User mandate

User explicit 2026-05-31:

> "Na Apple store nebude nikdy workflow, buildime vždy lokálně a nasazujeme
> jen z tohoto macu nebo z jiného, ale jen my. Nikdy GitHub !!!. Nic z tohoo
> se tam ukládáat nebude."

Translation: Apple distribution NEVER uses GitHub Actions. Builds + uploads
happen exclusively from a developer Mac. No Apple credential may live as a
GitHub Secret. AUR distribution may stay on GitHub Actions because the AUR
pipeline carries no Apple credentials.

This is codified as **HARD RULE #4** in `CLAUDE.md` (placed right after
HARD RULE #3 UX-approval, before HARD RULE #2 NEVER-TEMPORARY, matching the
chronological order of user-stated rules).

## 2. What gets deleted

| Path | Reason |
|---|---|
| `.github/workflows/apple-testflight.yml` | Apple build on GitHub — forbidden |
| `.github/workflows/mac-app-store.yml` | Apple build on GitHub — forbidden |

`git rm` is used (not raw `rm`) so the history of the previous CI design
remains intact and future contributors can see the supersession.

## 3. What stays

| Path | Reason |
|---|---|
| `.github/workflows/build-installers.yml` | Linux + Windows + macOS .dmg (Developer ID, NOT App Store) — no ASC credential needed; macOS .dmg signing uses a separate Developer ID path that is currently unsigned in CI |
| `.github/workflows/aur-publish.yml` | AUR — no Apple credential |
| `.github/workflows/aur-validate.yml` | AUR pre-flight — no Apple credential |
| `.github/workflows/build-libdave.yml` | GPL native lib — Linux/Windows native — no Apple credential |
| `fastlane/Fastfile` `:ios beta` lane | Still the canonical build+upload sequence, just called locally now |
| `fastlane/Fastfile` `:mac mac_app_store` lane | Same |

The Fastfile lanes are kept as the canonical sequence (jpackage tasks, signing
order, ASC API key wiring) so that the new bash scripts remain thin: they
add local-only pre-flight checks and then delegate to fastlane. This honours
HARD RULE #2 (no half-built parallel paths) by avoiding a second, divergent
build sequence written in pure bash.

## 4. New scripts

All scripts live under `dist/apple/` and follow the same skeleton:

- `#!/usr/bin/env bash` + `set -euo pipefail`
- Mandatory `--help` flag printing usage + required keychain identities + env
- Mandatory `--dry-run` flag that runs the pre-flight checks, prints the
  fastlane / gradle invocation that WOULD run, then exits cleanly without
  touching the build/upload pipeline
- Pre-flight checks fail-fast with a single clear error message naming the
  missing item
- Exit codes: 0 = success / dry-run-ok, 1 = pre-flight failure, anything
  bubbled from gradle/fastlane preserved as-is

```
dist/
├── apple/
│   ├── build-ipa.sh                # iOS archive only
│   ├── deploy-ipa.sh               # iOS upload of existing .ipa
│   ├── release-ios.sh              # build-ipa.sh + deploy-ipa.sh
│   └── macappstore/
│       ├── build-pkg.sh            # gradle :desktop:app:packageMacAppStore
│       ├── deploy-pkg.sh           # altool upload of existing .pkg
│       └── release-mac.sh          # build-pkg.sh + deploy-pkg.sh
└── release-all.sh                  # full per-release fan-out
```

### 4.1 Reuse strategy

`build-ipa.sh` and `deploy-ipa.sh` shell out to two new fastlane lanes:

- `:ios build_only` — archives, writes `build/ios-archive/Puklic.ipa`, does
  not call `pilot`.
- `:ios upload_only` — takes the existing `Puklic.ipa` and uploads to
  TestFlight internal (`skip_submission: true`).

The pre-existing `:ios beta` lane stays — it still does build+upload in one
shot for users who want a single-command flow. The new lanes simply split
that lane on the fastlane action boundary (`build_app` → `pilot`).

`build-pkg.sh` invokes Gradle directly (`./gradlew
:desktop:app:verifyMacAppStoreNoGplDeps :desktop:app:packageMacAppStore`)
because that is what the existing `:mac mac_app_store` lane already does —
no fastlane-specific value at this step. `deploy-pkg.sh` shells out to
`xcrun altool --upload-package` with the ASC API key directly; the existing
`:mac mac_app_store` lane stays as the all-in-one alternative.

### 4.2 release-all.sh flow

```
1. ./gradlew :desktop:app:packageDeb :desktop:app:packageAppImage  (Linux artefacts)
2. gh workflow run aur-publish.yml --ref main                       (AUR — no Apple creds)
3. dist/apple/release-ios.sh                                        (iOS .ipa → TestFlight)
4. dist/apple/macappstore/release-mac.sh                            (Mac .pkg → ASC)
```

`set -e` aborts on first failure. Each child script's pre-flight runs before
any artefact is built, so a missing keychain identity fails the whole release
in < 5 seconds.

### 4.3 Dry-run semantics

`--dry-run` is implemented in every script and propagates to children:

- For pre-flight: actually run all the checks (cheap, read-only). If any
  fails, the dry-run still exits non-zero — the point of dry-run is to
  validate that "if I run this for real, will it work".
- For build steps: print the gradle/fastlane invocation and skip.
- For upload steps: print the altool invocation and skip — no bytes leave
  the Mac.

This matches fastlane's `--skip_submission` mental model and gives the
developer a safe verification path before a real release.

## 5. Forbidden GitHub Secrets (HARD RULE #4 enforcement list)

| Secret pattern | Why forbidden |
|---|---|
| `ASC_KEY_ID` | App Store Connect — Apple credential |
| `ASC_ISSUER_ID` | Apple credential |
| `ASC_KEY_P8` | The .p8 file itself — Apple credential |
| `APPLE_DIST_P12_*` | Apple Distribution cert — Apple credential |
| `MAC_APP_DIST_P12_*` | Mac App Distribution cert — Apple credential |
| `MAC_INSTALLER_DIST_P12_*` | Mac Installer Distribution cert — Apple credential |
| `MAC_KEYCHAIN_PASSWORD` | Temp keychain only used for Apple cert import |
| `APPLE_PROVISIONING_PROFILE_BASE64` | iOS provisioning profile — Apple credential |
| `MAC_PROVISIONING_PROFILE_BASE64` | Mac provisioning profile — Apple credential |
| `PROVISIONING_PROFILE_NAME` | Apple profile name — leaks identity even without cert bytes |

Any new lane / script / workflow that needs one of these MUST live locally.

## 6. Self-critic

| Concern | Resolution |
|---|---|
| Should scripts call fastlane or invoke xcodebuild/altool directly? | Wrap fastlane for iOS (lanes are already the canonical sequence, refs FP-14e). Call Gradle directly for Mac (fastlane lane only wraps Gradle anyway). altool directly for Mac upload (simpler than another lane). |
| Dry-run mode | Implemented uniformly across all 7 scripts; pre-flight runs even in dry-run so missing cert is caught early. |
| AUR on GitHub Actions still OK? | YES — AUR pipeline only needs SSH key for `aur.archlinux.org`, no Apple credentials. Explicit carve-out in HARD RULE #4 wording. |
| HARD RULE #4 placement | Right after HARD RULE #3 (UX approval), before HARD RULE #2 (NEVER-TEMPORARY) — matches chronological order of user mandates. |
| Build-installers.yml macOS .dmg job | Currently builds an UNSIGNED .dmg (Developer ID flow, not App Store). User explicit mandate is about "Apple store" — Developer ID is a separate distribution channel. To be safe, the local-only mandate is interpreted to cover App Store ONLY; the unsigned .dmg CI build stays because (a) it has no Apple credential and (b) signing it for Developer ID is a separate future decision (Slice not yet scoped). When/if a Developer ID signing step is added to the .dmg job, HARD RULE #4 must be reread — at that point ASC creds re-enter and the job must move local. |

## 7. Doc updates in same commit

- `CLAUDE.md` — HARD RULE #4 added
- `iosApp/README.md` — workflow references removed, scripts referenced
- `dist/apple/README.md` — local-only model, 6 scripts listed, troubleshooting
- `docs/06_ops/apple-release.md` — NEW runbook
- `docs/07_roadmap/phases.md` — Slice 6 / Slice 7 / Slice 14 updated
- `docs/03_infrastructure/architect-reports/2026-05-28-ios-xcode-app.md` — supersession note (if exists)
- `docs/03_infrastructure/architect-reports/2026-05-29-fp14a-mac-app-store-architect.md` — supersession note

## 8. Verification (Step 5 of pipeline)

- Each of the 7 scripts: `--help` exits 0.
- `dist/apple/build-ipa.sh --dry-run` runs pre-flight + prints what it
  WOULD do; no archive produced.
- `dist/release-all.sh --dry-run` same, recursive into children.
- `find .github/workflows -name "apple*" -o -name "mac-app-store*"` empty.
- `grep -r "apple-testflight"` returns no hits outside this report.
