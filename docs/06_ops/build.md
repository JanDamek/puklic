# Build

Draft — finální build configuration vznikne při `gradle init` kroku po architect review.

## Toolchain

| Tool | Version |
|---|---|
| JDK (build) | 21 (Temurin / Liberica) |
| Kotlin | 2.x (latest stable) |
| Gradle | 8.x s wrapper |
| Compose Multiplatform | latest stable |
| Android Gradle Plugin | latest stable |
| Xcode | latest stable (jen pro iOS build) |

Version Catalog: `gradle/libs.versions.toml` — single source of truth pro versions.

## Build matrix

| Target | Host requirement | Output |
|---|---|---|
| `:desktop:app` JVM | JDK 21 + Linux/macOS/Windows | jar / AppImage / DMG / MSI |
| `:android:app` | Android SDK | APK / AAB |
| `:ios:app` | macOS + Xcode | Xcode project → IPA |
| `:shared:*` | host-platform sufficient | klein KMP artifacts |

Cross-platform iOS build z Linuxu/Windows = **nemožný** (Apple toolchain constraint). CI musí mít macOS runner pro iOS targets.

## Klíčové Gradle tasky (draft)

| Task | Účel |
|---|---|
| `./gradlew build` | Compile + test all |
| `./gradlew :shared:domain:test` | Per-module test |
| `./gradlew :desktop:app:run` | Spustit desktop app v dev módu |
| `./gradlew :desktop:app:packageDistributionForCurrentOS` | Build AppImage / DMG / MSI |
| `./gradlew :android:app:assembleDebug` | Android APK |
| `./gradlew :android:app:assembleRelease` | Signed release APK / AAB |
| `./gradlew :ios:app:iosDeployIPhone15Debug` | Run iOS app v simulátoru (KMP) |
| `./gradlew detekt ktlintCheck` | Static analysis |
| `./gradlew dependencyUpdates` | Check newer deps (gradle-versions-plugin) |

## Dev workflow

```bash
# První build (~5–10 min, fetches deps + Kotlin compiler)
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
| Desktop AppImage | unsigned (GPG detached signature optional) |
| macOS DMG | Apple Developer ID Application cert + notarize |
| Windows MSI | EV code-sign cert (later) |
| Android APK | Upload key + Play app signing |
| iOS IPA | Apple Distribution cert |

Pro MVP: AppImage unsigned + GPG sig na GitHub Releases. macOS / Windows / Mobile později.

## Reproducible builds

- Zafixovat Kotlin compiler version v `libs.versions.toml`
- Žádné `-SNAPSHOT` dependencies v release builds
- Locked dependency hashes (`gradle.lockfile`) — fáze 5

F-Droid reproducible build dělíme samostatným ADR později.

## Linting & formatting

| Tool | Config |
|---|---|
| ktlint | default rules + 2-space indent |
| detekt | default rules + custom config v `detekt.yml` |
| ktfmt | Google style nebo Kotlin official — TBD |

Pre-commit hook (lokálně): `./gradlew ktlintCheck detekt` před každým commitem. CI gate identický.

## Coverage

- Kover (Kotlin official) pro JVM/Android coverage
- Target: 70 % overall, 90 % pro `:shared:chat-parser` (parser je deterministický, snadno testovatelný)
- HTML report do `build/reports/kover/`

## Build performance

Cíle:
- Clean build < 5 min
- Incremental build < 30 s
- Test suite (`:shared:*` unit) < 60 s

Tipy:
- Enable Gradle build cache (`org.gradle.caching=true`)
- Configuration cache (`org.gradle.configuration-cache=true`)
- Parallel execution (`org.gradle.parallel=true`)
- JDK toolchain — žádné system JDK fallback

## Open questions

- **Convention plugins:** `buildSrc/` vs `build-logic/` included build. Doporučení: `build-logic/` (snazší versioning, no implicit classpath sharing).
- **KMP hierarchy template:** použít default (`hierarchyTemplate { default {} }`) nebo custom pro Apple targets.
- **Compose desktop distribuce:** `jpackage` vs `Conveyor` — vyhodnocení po MVP shipu.
