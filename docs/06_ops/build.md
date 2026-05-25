# Build

Draft — final build configuration will be created during the `gradle init` step after architect review.

## Toolchain

| Tool | Version |
|---|---|
| JDK (build) | 21 (Temurin / Liberica) |
| Kotlin | 2.x (latest stable) |
| Gradle | 8.x with wrapper |
| Compose Multiplatform | latest stable |
| Android Gradle Plugin | latest stable |
| Xcode | latest stable (iOS build only) |

Version Catalog: `gradle/libs.versions.toml` — single source of truth for versions.

## Build matrix

| Target | Host requirement | Output |
|---|---|---|
| `:desktop:app` JVM | JDK 21 + Linux (canonical) / macOS arm64 (dev only) | jar / .deb + .AppImage (Linux) / .dmg (macOS dev) |
| `:android:app` | Android SDK | APK / AAB |
| `:ios:app` | macOS + Xcode | Xcode project → IPA |
| `:shared:*` | host-platform sufficient | KMP artifacts |

Cross-platform iOS build from Linux = **impossible** (Apple toolchain constraint). Future mobile phase will require a macOS runner for iOS targets.

## Key Gradle tasks (draft)

| Task | Purpose |
|---|---|
| `./gradlew build` | Compile + test all |
| `./gradlew :shared:domain:test` | Per-module test |
| `./gradlew :desktop:app:run` | Run desktop app in dev mode |
| `./gradlew :desktop:app:packageDistributionForCurrentOS` | Build .deb + .AppImage (Linux) / .dmg (macOS dev) |
| `./gradlew :android:app:assembleDebug` | Android APK |
| `./gradlew :android:app:assembleRelease` | Signed release APK / AAB |
| `./gradlew :ios:app:iosDeployIPhone15Debug` | Run iOS app in simulator (KMP) |
| `./gradlew detekt ktlintCheck` | Static analysis |
| `./gradlew dependencyUpdates` | Check for newer deps (gradle-versions-plugin) |

## Dev workflow

```bash
# First build (~5–10 min, fetches deps + Kotlin compiler)
./gradlew build

# Run desktop
./gradlew :desktop:app:run

# Run with coroutine debug
./gradlew :desktop:app:run -Dkotlinx.coroutines.debug

# Watch mode (continuous build)
./gradlew :desktop:app:run --continuous

# Clean
./gradlew clean
```

## Sign convention

| Platform | Signing |
|---|---|
| Desktop .deb / .AppImage (Linux, shipped) | unsigned (GPG detached signature optional) |
| macOS .dmg (developer-side, not shipped) | not signed; for local dev only |
| Android APK (future) | Upload key + Play app signing |
| iOS IPA (future) | Apple Distribution cert |

For MVP: Linux .deb + .AppImage unsigned + GPG sig on GitHub Releases. Mobile later. Windows + macOS x86_64 out of scope (issue #22).

## Reproducible builds

- Pin Kotlin compiler version in `libs.versions.toml`
- No `-SNAPSHOT` dependencies in release builds
- Locked dependency hashes (`gradle.lockfile`) — Phase 5

F-Droid reproducible build addressed in a separate ADR later.

## Linting & formatting

| Tool | Config |
|---|---|
| ktlint | default rules + 2-space indent |
| detekt | default rules + custom config in `detekt.yml` |
| ktfmt | Google style or Kotlin official — TBD |

Pre-commit hook (local): `./gradlew ktlintCheck detekt` before every commit. CI gate is identical.

## Coverage

- Kover (Kotlin official) for JVM/Android coverage
- Target: 70 % overall, 90 % for `:shared:chat-parser` (parser is deterministic, easily testable)
- HTML report to `build/reports/kover/`

## Build performance

Goals:
- Clean build < 5 min
- Incremental build < 30 s
- Test suite (`:shared:*` unit) < 60 s

Tips:
- Enable Gradle build cache (`org.gradle.caching=true`)
- Configuration cache (`org.gradle.configuration-cache=true`)
- Parallel execution (`org.gradle.parallel=true`)
- JDK toolchain — no system JDK fallback

## Open questions

- **Convention plugins:** `buildSrc/` vs `build-logic/` included build. Recommendation: `build-logic/` (easier versioning, no implicit classpath sharing).
- **KMP hierarchy template:** use default (`hierarchyTemplate { default {} }`) or custom for Apple targets.
- **Compose desktop distribution:** `jpackage` vs `Conveyor` — evaluate after MVP ship.
