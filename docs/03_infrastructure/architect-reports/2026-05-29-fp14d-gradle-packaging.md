# FP-14d — Mac App Store Gradle packaging + entitlements + Info.plist + verify task (impl plan)

Status: IMPL ROLE plan + critic + execution (HARD RULE #1 Steps 2 + 3 + 6).
Date: 2026-05-29
Author: impl role — refs Issue #57. Builds on FP-14a (`2026-05-29-fp14a-mac-app-store-architect.md`),
FP-14b (`2026-05-29-fp14b-test-first.md`), FP-14c (`2026-05-29-fp14c-codec-wrappers.md`).

> HARD RULE #2 in full force. No TODO, no stubs, no "phase 2 follow-up".
> Every entitlement / Info.plist key / Gradle task ships its final shape.

---

## 1. Goal

Wire the Compose Desktop / jpackage pipeline for the Mac App Store variant of
`:desktop:app`. Concrete deliverables:

1. `dist/apple/macappstore/Puklic.entitlements` — final plist that satisfies
   `PuklicMacAppStoreEntitlementsTest` (FP-14b).
2. `dist/apple/macappstore/jpackage-resources/Info.plist` — Info.plist override
   template consumed by jpackage `--resource-dir`. Sets
   `LSApplicationCategoryType`, `NSMicrophoneUsageDescription`,
   `NSScreenCaptureUsageDescription`, `LSMinimumSystemVersion`,
   `ITSAppUsesNonExemptEncryption`. Bundle ID + version come from jpackage's
   own substitution (jpackage merges its computed plist with overrides in
   `--resource-dir`).
3. New `macAppStore` source set in `:desktop:app` with
   `MacAppStoreMain.kt` entry point that constructs the dependency graph with
   FP-14c Apple-native voice / screencast / transport factories AND uses
   `NoOpVoiceClient` for places where the iOS-App-Store-style "voice is not
   wired in v1 of the Mac App Store ship" decision applies (decision: voice
   IS wired via FP-14c factories — see §3.2).
4. `packageMacAppStore` Gradle task wrapping jpackage with `--mac-app-store`
   + `--mac-entitlements` + `--mac-app-image-sign-identity` +
   `--mac-installer-sign-identity` + `--resource-dir` + bundled
   `libopus.dylib` via `--app-content`.
5. `verifyMacAppStoreNoGplDeps` Gradle task using `MacAppStoreGplChecker`.
6. Two remaining RED `PuklicMacAppStoreEntitlementsTest` assertions turn
   GREEN.

---

## 2. Locked decisions inherited

| Decision | Value | Source |
|---|---|---|
| Bundle ID | `cz.damek.puklic.app` (same as iOS) | FP-14a §0 |
| Mac App Distribution identity | `3rd Party Mac Developer Application: Jan Damek (GR74KSG8M9)` | FP-14a §3 |
| Mac Installer Distribution identity | `3rd Party Mac Developer Installer: Jan Damek (GR74KSG8M9)` | FP-14a §3 |
| DAVE on App Store build | SKIP — same as iOS | FP-14a §0 |
| libopus path inside .app | `Contents/Resources/libopus.dylib` (jpackage `--app-content`) | mission brief |
| `jna.library.path` at launch | `$APPDIR/../Resources` via `--java-options` | mission brief |
| Version | `puklic.version` (single SoT in `gradle.properties`) | repo convention |

---

## 3. Module structure

### 3.1 `:desktop:app` source set layout

The existing `macAppStoreTest` source set (FP-14b) tests classes that live in
`:desktop:platform-macos-appstore`. FP-14d adds a sibling **`macAppStore`**
**main** source set (compiled to its own output) containing one file:
`MacAppStoreMain.kt`. The source set:

- Compiles AGAINST `sourceSets["main"].output` (reuses the existing
  `DependencyGraph`, `Main.kt`, Compose UI wiring) — NOT by copy-paste, by
  classpath reference.
- Depends additionally on `:desktop:platform-macos-appstore` (FP-14c Apple-native
  factories) AND on `:shared:screencast` (already a transitive of `:shared:voice`
  on the main classpath; explicit dep for the macAppStore source set keeps the
  graph mechanical).
- **Does NOT add or remove** `:shared:voice` from the main classpath — the main
  classpath remains the GPL-free DMG-and-Linux variant's runtime. `:shared:voice`
  on the main classpath already provides `DefaultVoiceClient` for the DMG ship.
  The Mac App Store variant SHADOWS that wiring at the entry-point level by
  constructing a `DependencyGraph` instance that swaps in Apple-native voice /
  transport / screencast factories.

> Rationale: the request "macAppStore source set OVERRIDES the DI to wire FP-14c
> factories instead" is implemented by overriding at the application entry point,
> not by mutating the `:shared:voice` runtime classpath. `DependencyGraph` is
> already a constructor-based DI seam; we add a small alternative companion
> factory `DependencyGraph.createMacAppStore(...)` that accepts the Apple-native
> factories. The main classpath dep `:shared:voice` is therefore still present
> at compile time but UNUSED by `MacAppStoreMain.kt`.

> Question: does this violate "macAppStore must not pull GPL"? The Mac App Store
> ship is the **jpackage output**, which jpackage assembles from the explicit
> classpath we pass it. `packageMacAppStore` constructs the classpath from
> `macAppStoreRuntimeClasspath` — which we declare WITHOUT `:shared:voice`.
> See §3.3 + §3.4.

### 3.2 Voice wiring on the Mac App Store ship

FP-14c provides `JnaLibopusEncoder` / `JnaLibopusDecoder` /
`JnaVideoToolboxH264Encoder` / `JnaVideoToolboxH264Decoder` /
`JnaNwConnectionUdpTransport`. The existing `dev.puklic.voice.DefaultVoiceClient`
in `:shared:voice` is the abstract over voice gateway + RTP + codec; it accepts
codec/transport factories via constructor. The Mac App Store path constructs
`DefaultVoiceClient` with FP-14c factories.

BUT: `:shared:voice` itself is GPL (libdave + JNA wrappers for libdave + FFmpeg
encoders for non-Apple platforms). Pulling `DefaultVoiceClient` therefore pulls
the GPL transitive closure. To satisfy `verifyMacAppStoreNoGplDeps` we use the
following strategy:

- The Mac App Store source set depends on `:shared:voice-api` (commonMain
  interfaces, Apache-2.0 — already exists) and on `:shared:voice-codec`
  (commonMain interfaces, Apache-2.0).
- A new lightweight composition class `AppleNativeVoiceClient` lives in
  `:desktop:platform-macos-appstore` (FP-14c module) and implements
  `dev.puklic.voice.VoiceClient` directly — it does NOT extend
  `DefaultVoiceClient`. The interface contract: `connect(channelId)`,
  `disconnect()`, `state: StateFlow<VoiceClientState>`. Composes the FP-14c
  encoder + decoder + transport with the existing Discord voice gateway
  protocol — but the Discord voice protocol implementation lives in
  `:shared:protocol-discord` (Apache-2.0, already on the macAppStore graph).

Verification check: this approach was implicit in FP-14a §4.2 / §4.3. The
matching FP-14c module already lists `AppleNativeVoiceFactory` in its design
(§4.2 of FP-14a, §3 module shape of FP-14c).

**Cross-check against current code:** `:shared:voice` ships `VoiceClient` interface
in commonMain. `:shared:voice-api` is the Apache-2.0 split-out. We use
`:shared:voice-api`'s `VoiceClient` interface for the macAppStore classpath.

### 3.3 Configuration / classpath isolation

The macAppStore source set declares its OWN runtime configuration through
the standard Kotlin/Gradle source-set conventions:

```kotlin
val macAppStoreMainSourceSet = sourceSets.create("macAppStore") {
    java.srcDir("src/macAppStore/kotlin")
    compileClasspath += sourceSets["main"].output + configurations["compileClasspath"]
    runtimeClasspath += output + sourceSets["main"].output + configurations["macAppStoreRuntimeClasspath"]
}
```

Then `configurations["macAppStoreRuntimeClasspath"]` extends `runtimeClasspath`
but **excludes** `:shared:voice`:

```kotlin
configurations["macAppStoreImplementation"].extendsFrom(
    configurations["implementation"],
)
configurations["macAppStoreRuntimeClasspath"].exclude(
    mapOf("group" to "puklic.shared", "module" to "voice"),
)
```

…WAIT. Kotlin project deps don't carry a Maven-style `group:module` we can
exclude by. The conventional approach for a project dep exclusion is per-dep
on the consumer side. Since the main source set's `implementation` already
pulls `projects.shared.voice`, and the macAppStore source set inherits from
main's classpath, we must do this differently.

**Selected approach:** the macAppStore source set's runtime classpath is built
EXPLICITLY (not by inheritance). Concretely:

```kotlin
val macAppStoreMainSourceSet = sourceSets.create("macAppStore") {
    java.srcDir("src/macAppStore/kotlin")
    // Compile against main outputs + main compileClasspath — for source-level
    // reference to DependencyGraph + Compose UI types.
    compileClasspath += sourceSets["main"].output + configurations["compileClasspath"]
    // Runtime classpath is built FRESH from a dedicated configuration that does
    // NOT inherit `:shared:voice` or any of its transitives.
    runtimeClasspath = output + sourceSets["main"].output + configurations["macAppStoreRuntimeClasspath"]
}
```

The `macAppStoreImplementation` configuration is hand-curated:
- `projects.shared.composeUi`
- `projects.shared.session`
- `projects.shared.platformApi`
- `projects.shared.voiceApi` (Apache-2.0 — replaces `:shared:voice`)
- `projects.shared.domain`
- `projects.shared.ids`
- `projects.shared.repositories`
- `projects.shared.persistenceApi`
- `projects.shared.persistenceSqldelight`
- `projects.shared.protocolDiscord`
- `projects.shared.screencast` (already Apache-2.0)
- `projects.desktop.platformMacos`
- `projects.desktop.platformMacosAppstore` (FP-14c)
- All the libs (`compose.desktop.currentOs`, ktor, kotlinx, koin, decompose,
  coil, kermit, logback, slf4j)

The `verifyMacAppStoreNoGplDeps` task walks `macAppStoreRuntimeClasspath` and
fails on any forbidden coord per `MacAppStoreGplChecker`.

### 3.4 Why this works

The main `compose.desktop.application { }` block consumes the `main` source
set output + the `main` runtime classpath. It produces `packageDmg` for the
existing macOS ship.

`packageMacAppStore` is a fresh `JavaExec`-style task that invokes `jpackage`
directly with:

```
--module-path <main-output> + <macAppStore-output> + <macAppStoreRuntimeClasspath>
--main-jar / --main-class dev.puklic.desktop.macappstore.MacAppStoreMainKt
```

This bypasses Compose Desktop's auto-classpath assembly (which would pull
`:shared:voice` via `main` deps). The classpath presented to jpackage is the
hand-curated macAppStore one. The .pkg output therefore embeds ONLY those
JARs.

> Note: this means MacAppStoreMain.kt cannot reference any symbol from
> `:shared:voice` because the macAppStore classpath excludes it at compile
> time too (compileClasspath is `main.output + main.compileClasspath` —
> `main.compileClasspath` DOES include `:shared:voice`, so compile-time
> references work but won't link at runtime).
>
> The cleaner contract: macAppStore source code uses only symbols available
> on `macAppStoreRuntimeClasspath`. We enforce this by ALSO removing
> `:shared:voice` from the macAppStore source set's compileClasspath:
>
> ```kotlin
> compileClasspath = output + configurations["macAppStoreCompileClasspath"]
> ```
>
> where `macAppStoreCompileClasspath` mirrors the curated list. We DROP the
> `+ sourceSets["main"].output` chunk — the macAppStore source set re-imports
> the few `DependencyGraph` / Main constants it needs by package path, or we
> avoid re-using `DependencyGraph` directly and build a parallel
> `MacAppStoreDependencyGraph` (~80 LOC) in the macAppStore source set.

**Final choice (minimum-complexity):** build a parallel
`MacAppStoreDependencyGraph` in the macAppStore source set. It's ~80 LOC and
duplicates the main DependencyGraph's plumbing minus the voice-related
DefaultVoiceClient construction (replaced by `AppleNativeVoiceClient`). This
guarantees the macAppStore source set has ZERO compile-time reference to
`:shared:voice`. The duplication is mechanical and the drift risk is bounded
(the platform-macos-appstore module has its own tests).

…ALTERNATIVE: re-use `DependencyGraph` but only in compileOnly + skip via
classpath assembly. Rejected: more magic, less honest. The duplication is the
honest expression of "Mac App Store is a different ship".

### 3.5 Info.plist override mechanism

jpackage `--resource-dir <dir>` looks for files with specific names that
override its built-in templates. For Info.plist on macOS the file is named
`Info.plist` directly inside the resource dir AND jpackage MERGES the
override with its computed values rather than fully replacing. The keys we
override take precedence; jpackage still injects bundle ID + version
correctly.

The committed override template:

```
dist/apple/macappstore/jpackage-resources/Info.plist
```

Contains ONLY the keys we want to set/override. jpackage substitution tokens
`DEPLOY.BUNDLE_IDENTIFIER`, `DEPLOY.PACKAGE_VERSION`, `DEPLOY.APP_VERSION`,
`DEPLOY.APP_NAME` are honored.

---

## 4. Concrete task / file inventory

| File | Action | Bytes (est) |
|---|---|---|
| `dist/apple/macappstore/Puklic.entitlements` | NEW | ~1500 |
| `dist/apple/macappstore/jpackage-resources/Info.plist` | NEW | ~1200 |
| `desktop/app/src/macAppStore/kotlin/dev/puklic/desktop/macappstore/MacAppStoreMain.kt` | NEW | ~3500 |
| `desktop/app/build.gradle.kts` | EDIT — add `macAppStore` source set, deps, `packageMacAppStore`, `verifyMacAppStoreNoGplDeps` | +~150 LOC |
| `docs/03_infrastructure/architect-reports/2026-05-29-fp14d-gradle-packaging.md` | NEW (this file) | ~7000 |

---

## 5. Entitlements (final)

8 keys, all set to `<true/>`, matching `PuklicMacAppStoreEntitlementsTest`:

1. `com.apple.security.app-sandbox`
2. `com.apple.security.network.client`
3. `com.apple.security.network.server`
4. `com.apple.security.device.audio-input`
5. `com.apple.security.files.user-selected.read-write`
6. `com.apple.security.files.downloads.read-write`
7. `com.apple.security.cs.allow-jit`
8. `com.apple.security.cs.allow-unsigned-executable-memory`

These match FP-14a §5 exactly. No additions, no removals.

---

## 6. Info.plist keys (final)

| Key | Value |
|---|---|
| `LSApplicationCategoryType` | `public.app-category.social-networking` |
| `LSMinimumSystemVersion` | `13.0` |
| `NSMicrophoneUsageDescription` | "Puklic uses the microphone for Discord voice channels." |
| `NSScreenCaptureUsageDescription` | "Puklic uses ScreenCaptureKit to share your screen in Discord voice channels." |
| `ITSAppUsesNonExemptEncryption` | `<false/>` |

These match FP-14a §5 exactly. No `NSCameraUsageDescription` (camera is not a
Phase-1 feature; HARD RULE #2 — do not pre-add usage strings for features we
don't ship).

---

## 7. jpackage invocation (final argv)

```
jpackage
  --type pkg
  --mac-app-store
  --name Puklic
  --app-version <puklic.version>
  --vendor "Jan Damek"
  --copyright "© 2026 Jan Damek. Apache-2.0."
  --dest <project>/build/macAppStore
  --input <staged jar dir>
  --main-jar puklic-mac-app-store.jar
  --main-class dev.puklic.desktop.macappstore.MacAppStoreMainKt
  --mac-package-identifier cz.damek.puklic.app
  --mac-package-name Puklic
  --mac-sign
  --mac-app-image-sign-identity "3rd Party Mac Developer Application: Jan Damek (GR74KSG8M9)"
  --mac-installer-sign-identity "3rd Party Mac Developer Installer: Jan Damek (GR74KSG8M9)"
  --mac-entitlements <project>/dist/apple/macappstore/Puklic.entitlements
  --resource-dir <project>/dist/apple/macappstore/jpackage-resources
  --app-content <staged libopus dir>           # places libopus.dylib in .app/Contents/Resources
  --java-options "-Djna.library.path=$APPDIR/../Resources"
  --java-options "-Dpuklic.flavor=macAppStore"
  --runtime-image <bundled-jdk>                # or jpackage default (Temurin)
```

The `--input` directory is constructed at task-config time: we copy every JAR
on `macAppStoreRuntimeClasspath` into a single staged dir, then jpackage
copies them under `Contents/app/`.

The `--app-content` directory contains `libopus.dylib` — jpackage copies its
contents verbatim under `.app/Contents/`. We place the dylib under a
subfolder `Resources/` so the final path is `.app/Contents/Resources/libopus.dylib`.

---

## 8. verifyMacAppStoreNoGplDeps

Pattern identical to `verifyIosNoGplDeps`. Walks every Gradle configuration
whose name contains `macAppStore` AND is resolvable; flattens module
dependencies; reports any coord matching `isForbiddenMacAppStoreArtifact`.

---

## 9. Self-critic

### 9.1 HARD RULE #2 — no temporary

- Entitlements: every key is justified by a shipped v1 feature.
- Info.plist: no "camera placeholder for later".
- Gradle: no `// TODO: switch to Compose Desktop's appStore = true once it
  exposes the necessary knobs". The current Compose Desktop 1.9.x does
  support `appStore = true` BUT it does not expose `--mac-installer-sign-identity`
  or `--app-content` separately. We use a hand-rolled `Exec` task. This is the
  CONCEPTUAL right answer until Compose Desktop catches up.

### 9.2 Minimum-complexity

- 5 new files (3 dist + 1 main + this report).
- Parallel `MacAppStoreDependencyGraph` is the simplest honest expression of
  the GPL-boundary split. Avoids classpath reflection magic.
- No new Gradle plugin.
- jpackage `Exec` task is ~80 LOC of Kotlin in `build.gradle.kts`.

### 9.3 Library-first

- Reuses jpackage (JDK-bundled tooling).
- Reuses FP-14c's Apple-native factories.
- Reuses `MacAppStoreGplChecker` from FP-14b.
- Reuses existing `puklic.jvm-library` convention plugin.

### 9.4 GPL boundary

- Curated `macAppStoreImplementation` configuration.
- `verifyMacAppStoreNoGplDeps` runs against the resolved
  `macAppStoreRuntimeClasspath` and fails build on violation.
- jpackage `--input` is built from that same classpath.

### 9.5 Findings

- None blocking. Plan ready for execution.

---

## 10. References

- `docs/03_infrastructure/architect-reports/2026-05-29-fp14a-mac-app-store-architect.md`
- `docs/03_infrastructure/architect-reports/2026-05-29-fp14b-test-first.md`
- `docs/03_infrastructure/architect-reports/2026-05-29-fp14c-codec-wrappers.md`
- `build-logic/src/main/kotlin/MacAppStoreGplChecker.kt`
- `ios/app/build.gradle.kts` (verifyIosNoGplDeps pattern)
- Issue #57
