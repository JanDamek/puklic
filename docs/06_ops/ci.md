# CI

Draft — initial configuration for GitHub Actions. Deployed after the first code commit.

## Goals

- Detect regressions before merge
- Build artifacts for every release tag
- Coverage report
- Lint gate

## Workflows (plan)

### `ci.yml` — pull requests + push to main

Triggered on: push (main, develop), pull_request.

Jobs:

| Job | Runner | Steps |
|---|---|---|
| `lint` | ubuntu-latest | ktlintCheck, detekt |
| `test-shared` | ubuntu-latest | `./gradlew :shared:**:test` |
| `test-desktop-linux` | ubuntu-latest | `./gradlew :desktop:app:test` |
| `test-desktop-macos` | macos-latest | `./gradlew :desktop:app:test` (phase 2+) |
| `test-android` | ubuntu-latest | `./gradlew :android:app:testDebugUnitTest` (phase 2+) |
| `test-ios` | macos-latest | `./gradlew :shared:domain:iosX64Test` (phase 2+) |
| `coverage` | ubuntu-latest | Kover report → Codecov upload |

Concurrent execution, ~10 min total.

### `release.yml` — git tag `v*`

Triggered on: tag push matching `v*`.

Jobs:

| Job | Output |
|---|---|
| `desktop-linux` | AppImage |
| `desktop-macos` | DMG (signed + notarized, phase 2+) |
| `desktop-windows` | MSI (phase 2+) |
| `android` | APK + AAB (phase 2+) |
| `ios` | IPA → TestFlight (phase 3+) |
| `github-release` | Creates GitHub Release with artifacts + changelog |

Artifacts uploaded to GitHub Release. GPG signing of AppImage in the release job.

### `dependency-audit.yml` — weekly

Triggered on: schedule (Monday 03:00 UTC) + manual.

- `./gradlew dependencyUpdates` (Ben Manes plugin)
- Creates an issue / PR if updates are available

## Caching

- Gradle cache (`~/.gradle/caches`, `~/.gradle/wrapper`)
- Kotlin / Konan cache (for iOS targets — `~/.konan`)
- Cache key: hash of `libs.versions.toml` + Kotlin version

## Secrets

| Secret | Purpose |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Android release signing |
| `ANDROID_KEYSTORE_PASSWORD` | |
| `APPLE_API_KEY` | macOS notarization, App Store upload |
| `APPLE_API_KEY_ID` | |
| `APPLE_API_ISSUER` | |
| `GPG_PRIVATE_KEY` | AppImage signing |
| `GPG_PASSPHRASE` | |
| `CODECOV_TOKEN` | Coverage upload |

## Branch protection (main)

- Require PR before merge
- Require status checks: `lint`, `test-shared`, `test-desktop-linux`
- Require linear history (rebase, not merge commits)
- Require signed commits (GPG)

## Forks & external PRs

Secrets are **not** available for PRs from forks (GitHub policy). Release build and deployment jobs are skipped for fork PRs.

## Open questions

- **CI provider:** GitHub Actions default. Alternatives (GitLab CI, BuildKite) if there is a reason to switch.
- **Self-hosted runner for iOS:** if GitHub macOS minutes become a bottleneck.
- **Nightly builds:** main → AppImage daily build → GitHub Pre-release? Phase 5+.
