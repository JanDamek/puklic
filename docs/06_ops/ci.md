# CI

Draft — startovní configuration pro GitHub Actions. Nasazeno až po prvním commitu kódu.

## Goals

- Detekovat regressions před merge
- Build artifacts pro každý release tag
- Coverage report
- Lint gate

## Workflows (plán)

### `ci.yml` — pull requests + push to main

Triggered on: push (main, develop), pull_request.

Jobs:

| Job | Runner | Steps |
|---|---|---|
| `lint` | ubuntu-latest | ktlintCheck, detekt |
| `test-shared` | ubuntu-latest | `./gradlew :shared:**:test` |
| `test-desktop-linux` | ubuntu-latest | `./gradlew :desktop:app:test` |
| `test-desktop-macos` | macos-latest | `./gradlew :desktop:app:test` (fáze 2+) |
| `test-android` | ubuntu-latest | `./gradlew :android:app:testDebugUnitTest` (fáze 2+) |
| `test-ios` | macos-latest | `./gradlew :shared:domain:iosX64Test` (fáze 2+) |
| `coverage` | ubuntu-latest | Kover report → Codecov upload |

Concurrent execution, ~10 min total.

### `release.yml` — git tag `v*`

Triggered on: tag push matching `v*`.

Jobs:

| Job | Output |
|---|---|
| `desktop-linux` | AppImage |
| `desktop-macos` | DMG (signed + notarized, fáze 2+) |
| `desktop-windows` | MSI (fáze 2+) |
| `android` | APK + AAB (fáze 2+) |
| `ios` | IPA → TestFlight (fáze 3+) |
| `github-release` | Vytvoří GitHub Release s artifactama + changelog |

Artifacts uploadnuty do GitHub Release. GPG signing AppImage v release jobu.

### `dependency-audit.yml` — týdně

Triggered on: schedule (Monday 03:00 UTC) + manual.

- `./gradlew dependencyUpdates` (Ben Manes plugin)
- Vytvoří issue / PR pokud jsou updates dostupné

## Caching

- Gradle cache (`~/.gradle/caches`, `~/.gradle/wrapper`)
- Kotlin / Konan cache (pro iOS targets — `~/.konan`)
- Cache key: hash `libs.versions.toml` + Kotlin version

## Secrets

| Secret | Účel |
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
- Require linear history (rebase, ne merge commits)
- Require signed commits (GPG)

## Forks & external PRs

Sekrety **nejsou** dostupné pro PRs z forků (GitHub policy). Release build a deployment jobs skip pro fork PRs.

## Open questions

- **CI provider:** GitHub Actions default. Alternativy (GitLab CI, BuildKite) až bude důvod.
- **Self-hosted runner pro iOS:** pokud GitHub macOS minutes will be a bottleneck.
- **Nightly builds:** main → AppImage daily build → GitHub Pre-release? Fáze 5+.
