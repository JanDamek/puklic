# Scaffold Code Critic Report: Gradle Multimodule + KMP

**Date:** 2026-05-21
**Reviewer role:** Code Critic (scaffold verification pass)
**Reviewed commit:** `8e68178` (merged as `a6b6031`) — `subagent: kotlin-engineer`
**Authoritative spec:** `docs/03_infrastructure/architect-reports/2026-05-21-gradle-setup.md` (r2)
**Scope:** Steps 1–2 only — convention plugins, `libs.versions.toml`, module `build.gradle.kts` files, `gradle.properties`, `.editorconfig`, `detekt.yml`, `AndroidManifest.xml`

---

## Findings

---

### CRITICAL-1

**Severity:** CRITICAL
**Finding:** `detekt-compose-rules` and `ktlint-compose-rules` are declared in `libs.versions.toml` and nowhere else — they are never wired as actual dependencies and therefore run on zero code.

**Evidence:**
```
gradle/libs.versions.toml:39  detekt-compose-rules = "0.4.22"
gradle/libs.versions.toml:41  ktlint-compose-rules  = "0.4.22"
gradle/libs.versions.toml:103 detekt-compose-rules = { module = "io.nlopez.compose.rules:detekt", ... }
gradle/libs.versions.toml:104 ktlint-compose-rules  = { module = "io.nlopez.compose.rules:ktlint",  ... }
```

Grep for `detektPlugins`, `ktlintRuleset`, `detekt.compose.rules`, `ktlint.compose.rules` across all `*.gradle.kts` files in the repository: **zero matches**.

**Reasoning:** The `io.nlopez.compose.rules:detekt` plugin must be on the Detekt classpath via `detektPlugins(libs.detektComposeRules)` in a convention plugin or module file. Without that dependency declaration, the plugin JAR is never loaded, Detekt's rule set never includes the Compose rules, and the enforcement spec Q12 lists ("no mutable state in Composable parameters, modifier parameter required on layout Composables, no side effects outside LaunchedEffect") is entirely inactive. The version catalog entries are dead. The `ktlint-compose-rules` library needs a `ktlintRuleset(libs.ktlintComposeRules)` dependency declaration for the same reason. Both are absent from `puklic.detekt.gradle.kts` and every module file.

**Recommendation:** Add to `puklic.detekt.gradle.kts`:
```kotlin
dependencies {
    detektPlugins(libs.detektComposeRules)
    ktlintRuleset(libs.ktlintComposeRules)
}
```

---

### CRITICAL-2

**Severity:** CRITICAL
**Finding:** `puklic.detekt.gradle.kts` hardcodes `version.set("1.3.1")` as a string literal rather than reading from the version catalog — ktlint binary version drifts silently from `libs.versions.toml`.

**Evidence:**
```
build-logic/src/main/kotlin/puklic.detekt.gradle.kts:18  version.set("1.3.1")
gradle/libs.versions.toml:41                             ktlint = "1.3.1"
```

**Reasoning:** The `build-logic/build.gradle.kts` already wires version catalog type-safe accessors via `implementation(files(libs.javaClass.protectionDomain.codeSource.location))`. Precompiled script plugins in `build-logic/` therefore have access to `libs.versions.ktlint.get()`. Despite this, line 18 uses a raw string. When `ktlint = "1.4.0"` is bumped in `libs.versions.toml`, `./gradlew ktlintCheck` silently continues running 1.3.1. The TOML is the single source of truth for versions; the hardcoded literal breaks that invariant for the one tool that most directly enforces formatting consistency.

**Recommendation:**
```kotlin
ktlint {
    version.set(libs.versions.ktlint.get())
    // ...
}
```

---

### CRITICAL-3

**Severity:** CRITICAL
**Finding:** `puklic.kmp-library.gradle.kts` applies `id("org.jetbrains.kotlin.plugin.serialization")` to all `:shared:*` modules, including modules that have no `@Serializable` usage — a scope deviation from the spec.

**Evidence:**
```
build-logic/src/main/kotlin/puklic.kmp-library.gradle.kts:14
    id("org.jetbrains.kotlin.plugin.serialization")
```

Spec Q2, `puklic.kmp-library` scope:
> "Configures Kotlin version, JVM toolchain (21), Android target SDK (35)/minSdk (26), iOS targets … common test dependencies (kotlin.test, Kotest), Kover coverage setup, and detekt."

Serialization is absent from this list. Modules `:shared:ids`, `:shared:platform-api`, and `:shared:chat-parser` have no `@Serializable` declarations or `kotlinx-serialization` in their `commonMain.dependencies`.

**Reasoning:** The Kotlin serialization compiler plugin is added to every KMP compilation in all three modules, adding unnecessary overhead to each incremental build. More concretely, it establishes a false contract: any engineer inspecting `:shared:ids` will see the serialization plugin applied and assume IDs are expected to be serializable, potentially adding `@Serializable` annotations that break the "value classes only" scope intended for `:shared:ids`. The plugin should be applied only to modules that declare `implementation(libs.kotlinxSerializationJson)` — currently `:shared:domain`, `:shared:protocol-discord`, and `:shared:persistence-api`.

**Recommendation:** Remove `id("org.jetbrains.kotlin.plugin.serialization")` from `puklic.kmp-library`. Create a `puklic.kmp-serialization-library` variant that extends `puklic.kmp-library` and adds the serialization plugin, OR apply it explicitly in each module's `build.gradle.kts` alongside the `kotlinx-serialization-json` dependency.

---

### CRITICAL-4

**Severity:** CRITICAL
**Finding:** `:desktop:app` directly applies two platform-level plugins inline, violating the "one convention plugin per module" principle and bypassing the `build-logic/` classpath isolation that the spec's design rationale depends on.

**Evidence:**
```
desktop/app/build.gradle.kts:1-5
    plugins {
        id("puklic.jvm-library")
        alias(libs.plugins.compose.multiplatform)         // direct application
        alias(libs.plugins.kotlin.compose.compiler)       // direct application
    }
```

Spec Q2 plugin inventory lists 7 convention plugins — none is `puklic.desktop-app`. Spec §5:
> "desktop/app/build.gradle.kts — applies puklic.jvm-library; deps: compose-ui, session, …, Compose Desktop"

**Reasoning:** The spec's entire justification for `build-logic/` over `buildSrc/` is classpath isolation: "subprojects only gain access to what they explicitly apply via `plugins { id("puklic.xxx") }`, preventing accidental dependency leakage." `:desktop:app` sidesteps this by applying the Compose Multiplatform and Compose Compiler plugins directly — these plugins are already on the classpath from the root `build.gradle.kts`'s `apply false` declarations, so this works, but it sets a precedent where any future developer can apply plugins directly in module files. If a `puklic.desktop-app` convention plugin existed, Compose Desktop configuration would be centralized: version pinning, Compose Desktop JVM entrypoint config, and window defaults would be in one place rather than fragmented across module files as the desktop grows.

**Recommendation:** Create `puklic.desktop-app.gradle.kts` in `build-logic/` that applies `puklic.jvm-library` + both Compose plugins. Add it to the `build-logic/build.gradle.kts` dependencies. `:desktop:app` then applies only `id("puklic.desktop-app")`.

---

### MEDIUM-5

**Severity:** MEDIUM
**Finding:** `.editorconfig` sets `indent_size = 4` for Kotlin files; spec Q12 says "2-space indent (per build.md)."

**Evidence:**
```
.editorconfig:8-9
    [*.{kt,kts}]
    indent_size = 4
```

Spec Q12: "2-space indent (per build.md), max line length 120."

`docs/06_ops/build.md:86`: "ktlint | default rules + 2-space indent"

**Reasoning:** 4-space indent is the JetBrains-recommended Kotlin style (and what the `intellij_idea` ktlint code style enforces by default per `.editorconfig:15 ktlint_code_style = intellij_idea`). The spec's "2-space" claim appears to be an error in the draft `build.md`. However, the current `.editorconfig` and `build.md` are now contradictory, and `detekt.yml:479 maxLineLength: 120` was written assuming the same line-length budget regardless of indent. The contradiction needs resolution — either update spec and `build.md` to acknowledge 4-space, or correct `.editorconfig`. Leaving both in place means the spec cannot serve as the "sole reference."

**Recommendation:** Update `build.md` line 86 to "ktlint | default rules + 4-space indent" to match the implemented `.editorconfig`. Do not change `.editorconfig` — 4-space is correct for Kotlin.

---

### MEDIUM-6

**Severity:** MEDIUM
**Finding:** `sourceSets.findByName("iosMain")?.apply { ... }` is defensive dead code with a factually incorrect explanatory comment in two module files.

**Evidence:**
```
shared/protocol-discord/build.gradle.kts:24-28
    // iosMain only exists on Apple hosts; guarded to support Linux CI.
    sourceSets.findByName("iosMain")?.apply {
        dependencies { implementation(libs.ktor.client.darwin) }
    }

shared/persistence-sqldelight/build.gradle.kts:22-26
    // On Linux CI, iOS targets are disabled — iosMain source set is not created.
    sourceSets.findByName("iosMain")?.apply {
        dependencies { implementation(libs.sqldelight.native.driver) }
    }
```

`gradle.properties:12`:
```
kotlin.native.ignoreDisabledTargets=true
```

`puklic.kmp-library.gradle.kts:30-33` unconditionally declares:
```kotlin
iosArm64()
iosX64()
iosSimulatorArm64()
```

**Reasoning:** `kotlin.native.ignoreDisabledTargets=true` tells the Kotlin Gradle plugin to suppress the "disabled native target" warning but still register the targets. When a KMP module declares `iosArm64()`, `iosX64()`, and `iosSimulatorArm64()`, the KMP default hierarchy template creates the `iosMain` source set unconditionally — the source set exists in the project model even on Linux where the iOS targets cannot produce binaries. `findByName("iosMain")` will never return `null` with this configuration. The null-safe `?.apply` is dead code, and the comment "iosMain source set is not created" is factually wrong. A future engineer will read this comment, misunderstand the build model, and copy the pattern incorrectly for new source sets.

**Recommendation:** Replace `sourceSets.findByName("iosMain")?.apply { dependencies { ... } }` with the standard:
```kotlin
iosMain.dependencies {
    implementation(...)
}
```
Remove the misleading comment. This is safe because `iosMain` is always present given the convention plugin.

---

### MEDIUM-7

**Severity:** MEDIUM
**Finding:** `puklic.ios-library.gradle.kts` applies Kover to iOS-only modules; the spec explicitly states iOS coverage via Kover is unavailable.

**Evidence:**
```
build-logic/src/main/kotlin/puklic.ios-library.gradle.kts:7
    id("org.jetbrains.kotlinx.kover")
```

Spec Q11: "**Coverage:** Kover (JVM + Android). **iOS coverage via Kover is not available** — use code review as the iOS test quality gate."

**Reasoning:** Kover's coverage instrumentation works on JVM and Android bytecode. For Kotlin/Native (iOS) targets, Kover has no instrumentation mechanism. Applying Kover to `:ios:app` and `:ios:platform` (the two modules that use `puklic.ios-library`) adds Kover task registrations that will never produce coverage data and will confuse any future Kover merge report. The spec was explicit on this point: Kover is JVM/Android only.

**Recommendation:** Remove `id("org.jetbrains.kotlinx.kover")` from `puklic.ios-library.gradle.kts`.

---

### MEDIUM-8

**Severity:** MEDIUM
**Finding:** `build-logic/settings.gradle.kts` omits the JetBrains Compose dev repository; the `compose-gradle-plugin` artifact used in `build-logic/build.gradle.kts` may not resolve from Maven Central alone for all versions.

**Evidence:**
```
build-logic/settings.gradle.kts:3-6
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        // JetBrains Compose dev repo ABSENT
    }

build-logic/build.gradle.kts:38
    implementation("org.jetbrains.compose:compose-gradle-plugin:$composeMpVersion")
```

Root `settings.gradle.kts:7-9` correctly includes:
```
maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
```

**Reasoning:** `build-logic/` is an included build with its own isolated repository set. The `org.jetbrains.compose:compose-gradle-plugin` artifact is published to Maven Central for stable CMP releases, but JetBrains has historically published some CMP versions exclusively to the JetBrains Space dev repository before promoting them to Maven Central. If CMP 1.8.0 (or any future version) is not yet mirrored to Maven Central when the `build-logic/` classpath is resolved, the build fails with a dependency resolution error that is non-obvious because the main project's repo config is unrelated to `build-logic/`. This asymmetry between `settings.gradle.kts` and `build-logic/settings.gradle.kts` is a latent reliability issue.

**Recommendation:** Add `maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")` to `build-logic/settings.gradle.kts`'s `dependencyResolutionManagement.repositories`.

---

### MEDIUM-9

**Severity:** MEDIUM
**Finding:** `gradle.properties` adds `kotlin.native.ignoreDisabledTargets=true` — a line absent from the spec Appendix that defines the "verbatim" baseline.

**Evidence:**
```
gradle.properties:11-12
    # Suppress "Disabled Kotlin/Native Targets" warning on Linux CI (iOS targets can't build on Linux)
    kotlin.native.ignoreDisabledTargets=true
```

Spec Appendix `gradle.properties` baseline — this property is absent from the listed 9 entries.

**Reasoning:** The merge commit message (`a6b6031`) acknowledges implementation deviations but doesn't list this one. The spec Appendix explicitly claims these flags "must be set from day 1" — implying the list is complete. A developer using the spec as a reference to recreate the build environment will miss this flag. Without `ignoreDisabledTargets=true`, every `./gradlew build` on Linux prints a wall of "Kotlin/Native target was disabled" warnings for iosArm64/iosX64/iosSimulatorArm64, which makes CI output noisy. The flag was correctly added; it just needs spec documentation.

**Recommendation:** Add `kotlin.native.ignoreDisabledTargets=true` with its comment to the spec Appendix in `2026-05-21-gradle-setup.md` §Appendix. This is a documentation sync, not a code change.

---

### MEDIUM-10

**Severity:** MEDIUM
**Finding:** `android.nonFinalResIds=false` remains in `gradle.properties` — this is MEDIUM-10 from the prior critic round, explicitly deferred without resolution in spec r2.

**Evidence:**
```
gradle.properties:19
    android.nonFinalResIds=false
```

Prior critic finding MEDIUM-10: "AGP has been defaulting `nonFinalResIds=true` since AGP 7.x … Setting it to `false` generates a deprecation warning in AGP 8.x."

**Reasoning:** The previous critic recommended removing this property for a new Compose-only project. The spec r2 did not adopt this recommendation; the property remains in the Appendix and in the implementation. Every `./gradlew build` and `./gradlew sync` on Android Studio will emit a deprecation warning. For a project that is currently an Android stub with zero resources, this is especially unnecessary noise. The property will require attention before Phase 2 (Android implementation), when the deprecation warning turns into a hard error in a future AGP patch.

**Recommendation:** Remove `android.nonFinalResIds=false` from `gradle.properties`. Update spec Appendix to remove it. AGP default (`true`) is correct for a Compose-only project.

---

### NIT-11

**Severity:** NIT
**Finding:** Five entries in `libs.versions.toml [versions]` table are dead declarations — values are hardcoded in convention plugins rather than referenced from the catalog.

**Evidence:**
```
gradle/libs.versions.toml:6-10
    jvm-toolchain        = "21"
    android-min-sdk      = "26"
    android-target-sdk   = "35"
    android-compile-sdk  = "35"
    ios-deployment-target = "14.0"
```

```
puklic.kmp-library.gradle.kts:25,34,48,49,52-53
    jvmTarget.set(JvmTarget.JVM_21)   // hardcoded
    jvmToolchain(21)                   // hardcoded
    compileSdk = 35                    // hardcoded
    minSdk = 26                        // hardcoded
    sourceCompatibility = JavaVersion.VERSION_21  // hardcoded
    targetCompatibility = JavaVersion.VERSION_21  // hardcoded
```

**Reasoning:** Precompiled script plugins in `build-logic/` can access the version catalog via `libs.versions.<key>.get()` because `build-logic/build.gradle.kts` includes `implementation(files(libs.javaClass.protectionDomain.codeSource.location))`. The catalog entries for toolchain versions exist but are never consumed. When toolchain version needs to be updated, a developer must change both `libs.versions.toml` (the apparent source of truth) AND each convention plugin (the actual implementation) — a two-file change where the TOML change gives false confidence that updating it alone is sufficient.

**Recommendation:** Replace hardcoded values in convention plugins:
```kotlin
jvmToolchain(libs.versions.jvmToolchain.get().toInt())
compileSdk = libs.versions.androidCompileSdk.get().toInt()
minSdk = libs.versions.androidMinSdk.get().toInt()
```
Or remove the dead `[versions]` entries and document the values in convention plugin comments.

---

### NIT-12

**Severity:** NIT
**Finding:** `android/app/src/main/AndroidManifest.xml:11` uses `android:theme="@android:style/Theme.Black.NoTitleBar"` — a deprecated View-system theme that will cause a runtime crash when Compose Material3 content is set.

**Evidence:**
```xml
android/app/src/main/AndroidManifest.xml:11
    android:theme="@android:style/Theme.Black.NoTitleBar">
```

**Reasoning:** `Theme.Black.NoTitleBar` is a pre-Material era Android system theme from the View framework. When `setContent { PuklicTheme { ... } }` is called with Compose Material3 components, the activity's window background and type values must be compatible with Compose's `ComposeView`. `Theme.Black.NoTitleBar` does not configure `android:windowSoftInputMode`, `android:windowBackground`, or `windowActionBar = false` in a way that's compatible with Material3's `dynamicColorScheme()` API. The correct theme for a pure Compose app is `Theme.AppCompat.Light.NoActionBar` (if not using a fully custom theme) or a style defined in `res/values/themes.xml`.

**Recommendation:** Replace with:
```xml
android:theme="@style/Theme.AppCompat.Light.NoActionBar"
```
Or define a proper `PuklicTheme` XML style in `android/app/src/main/res/values/themes.xml` before step 13 (Compose UI implementation).

---

### NIT-13

**Severity:** NIT
**Finding:** `detekt.yml` has no `compose:` configuration block for `io.nlopez.compose.rules` — when the plugin is wired (see CRITICAL-1), rules run with library-internal defaults, some of which will flag scaffold-era empty Composables.

**Evidence:** `detekt.yml` — 614 lines covering all standard Detekt rule groups; no `compose:` section present.

**Reasoning:** `io.nlopez.compose.rules:detekt` registers rules under a `compose` ruleset. Without an explicit `compose:` block in `detekt.yml`, when the plugin is finally wired, all Compose rules activate with library defaults. Some defaults are aggressive for a scaffold: `ComposableNaming` will flag placeholder composables, `ModifierMissing` will flag skeleton screens that intentionally omit `modifier` parameters. Without the `compose:` section, there is no way to tune thresholds or disable rules that are inappropriate for the current phase. Adding the section now is zero-cost; adding it after source code lands requires discovering which rules fired and why.

**Recommendation:** Add a minimal `compose:` block to `detekt.yml` after wiring the plugin (CRITICAL-1 fix), tuning at minimum:
```yaml
compose:
  ComposableNaming:
    active: true
  ModifierMissing:
    active: true
  MutableStateAutoboxing:
    active: true
  ViewModelInjection:
    active: false  # Decompose components, not ViewModel; would false-positive
```

---

### NIT-14

**Severity:** NIT
**Finding:** `desktop/platform-macos/build.gradle.kts` and `desktop/platform-windows/build.gradle.kts` omit `testImplementation(libs.kotlinx.coroutines.test)` — inconsistent with the sibling `desktop/platform-linux/build.gradle.kts`.

**Evidence:**
```
desktop/platform-linux/build.gradle.kts:12
    testImplementation(libs.kotlinx.coroutines.test)   // present

desktop/platform-macos/build.gradle.kts:7-8            // absent
desktop/platform-windows/build.gradle.kts:7-8          // absent
```

**Reasoning:** Platform implementations of `SecureStorage`, `NotificationService`, etc. will involve coroutines (they implement interfaces from `:shared:platform-api` which uses `suspend` and `Flow`). When stub implementations in `platform-macos` and `platform-windows` gain tests (Phase 2), the missing `coroutines-test` dependency will cause a test compilation failure that looks like an unexplained classpath problem rather than a missing dependency.

**Recommendation:** Add `testImplementation(libs.kotlinx.coroutines.test)` to both platform-macos and platform-windows `build.gradle.kts` for consistency.

---

### NIT-15

**Severity:** NIT
**Finding:** `gradle-versions-plugin` is applied in root `build.gradle.kts` with `apply false` and never applied anywhere else — `./gradlew dependencyUpdates` does not exist.

**Evidence:**
```
build.gradle.kts:19
    alias(libs.plugins.gradle.versions) apply false
```

`docs/06_ops/build.md` lists `./gradlew dependencyUpdates` as a standard task.

**Reasoning:** The `com.github.ben-manes.versions` plugin generates the `dependencyUpdates` task only on the project it is applied to. With `apply false` at root and no subproject applying it, the task is absent from `./gradlew tasks`. This contradicts `build.md`'s task list. The intent is presumably to apply it at root level for whole-project dependency update checking — for which `apply false` is wrong.

**Recommendation:** Either apply the plugin to the root project without `apply false`:
```kotlin
alias(libs.plugins.gradle.versions)  // remove "apply false"
```
Or note this as a step-3 task ("apply versions plugin to root once the root has tasks to check").

---

## Spec §5 File Inventory Verification

| Spec §5 required item | Present? | Notes |
|---|---|---|
| `settings.gradle.kts` (19 modules) | ✅ | All 19 modules included |
| `build.gradle.kts` (root) | ✅ | |
| `gradle/libs.versions.toml` | ✅ | |
| `gradle.properties` | ✅ | Extra line (MEDIUM-9) |
| `gradlew` / `gradlew.bat` | ✅ | |
| `gradle/wrapper/gradle-wrapper.properties` | ✅ | Gradle 8.12-bin |
| `gradle/wrapper/gradle-wrapper.jar` | ✅ | `validateDistributionUrl=true` |
| `detekt.yml` | ✅ | No compose: section (NIT-13) |
| `.editorconfig` | ✅ | 4-space vs 2-space (MEDIUM-5) |
| `build-logic/settings.gradle.kts` | ✅ | Missing compose dev repo (MEDIUM-8) |
| `build-logic/build.gradle.kts` | ✅ | |
| All 7 convention plugins | ✅ | Issues per CRITICAL-1,3,4, MEDIUM-7 |
| All 19 module `build.gradle.kts` | ✅ | |
| `shared/persistence-api` SQLDelight dir | ✅ | `src/commonMain/sqldelight/dev/puklic/db/` |
| `android/app/src/main/AndroidManifest.xml` | ✅ | Deprecated theme (NIT-12) |
| `android/platform/src/main/` stub | ✅ | No manifest; namespace set; AGP generates |
| `ios/app/src/iosMain/kotlin/` | ✅ | |
| `ios/platform/src/iosMain/kotlin/` | ✅ | |

## Spec §6 Version Verification

All versions in `gradle/libs.versions.toml` match spec §6 exactly. No `+`, `SNAPSHOT`, `latest`, or `(est.)` strings present. `kotlinx-coroutines = "1.10.1"` matches `kotlinx-coroutines-test = "1.10.1"` ✅ (both use `version.ref = "kotlinx-coroutines"`). Wrapper pinned to Gradle 8.12 ✅.

Extra TOML entry not in spec: `jlleitschuh-ktlint-gradle = "12.1.2"` — required by the ktlint Gradle plugin; correct addition, not a version drift.

---

<SCAFFOLD_CRITIC_RESULT>
counts:
  blockers: 0
  critical: 4
  medium: 6
  nit: 5

top_line_summary: >
  No hard compile-time BLOCKERs. The scaffold builds and lists 19 modules as
  specified. The four CRITICAL findings are all quality-gate failures that will
  become active problems when source code lands in step 3: (1) the Compose
  static-analysis rules (detektPlugins + ktlintRuleset) are declared in the
  version catalog but never wired as dependencies, making them completely
  inactive; (2) the ktlint binary version is hardcoded as a string literal in
  the convention plugin, bypassing the version catalog; (3) the Kotlin
  serialization plugin is applied to all KMP shared modules including ones that
  will never use @Serializable; (4) :desktop:app directly applies two platform
  Compose plugins, violating the one-convention-plugin contract and the
  build-logic classpath-isolation rationale. Medium findings are operational
  risks (missing Compose dev repo in build-logic, Kover on iOS-only modules,
  incorrect iosMain guard comments, unresolved prior MEDIUM-10) that do not
  prevent step-3 start but will each require a fix before their affected
  feature is exercised.

recommendation: >
  FIX SCAFFOLD FIRST — specifically CRITICAL-1 (wire Compose rules as
  detektPlugins/ktlintRuleset dependencies), CRITICAL-2 (replace hardcoded
  ktlint version with libs.versions.ktlint.get()), and CRITICAL-3 (remove
  serialization plugin from puklic.kmp-library base plugin). These three
  changes are confined to build-logic/ and gradle/libs.versions.toml — no
  module source code is affected. CRITICAL-4 (desktop-app convention plugin)
  can be deferred until :desktop:app gains source code but must be done before
  step 13. PROCEED TO STEP 3 only after CRITICAL-1, CRITICAL-2, CRITICAL-3
  are addressed.
</SCAFFOLD_CRITIC_RESULT>
