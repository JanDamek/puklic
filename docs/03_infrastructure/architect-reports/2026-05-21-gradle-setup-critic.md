# Code Critic Report: Gradle Multimodule + KMP Setup Specification

**Date:** 2026-05-21  
**Reviewer role:** Code Critic (pipeline step 3)  
**Reviewed document:** `docs/03_infrastructure/architect-reports/2026-05-21-gradle-setup.md`  
**Scope:** Architectural design review — no code exists yet. Findings are pre-implementation blockers.

---

## Findings

---

### BLOCKER-1

**Finding:** `interface DriverFactory` in `commonMain` combined with `actual class DriverFactory` in platform source sets is a Kotlin compile error — `actual` requires `expect`, not `interface`.

**Evidence (Q9, §5 `shared/persistence-sqldelight/`):**
> `commonMain`: `interface DriverFactory { fun createDriver(): SqlDriver }`  
> `jvmMain`: `actual class DriverFactory(private val path: Path)` using `JdbcSqliteDriver`

**Reasoning:** `actual` declarations are only valid as counterparts to `expect` declarations. A regular `interface` in `commonMain` has no `expect` keyword, so `actual class DriverFactory` in `jvmMain` produces: `error: 'actual' declaration has no corresponding 'expect' declaration`. Any engineer implementing this spec verbatim will hit an immediate compilation error. The same ambiguity infects `ZlibInflater` in Q7/§5: narrative says "use an interface `ZlibInflater`" but file descriptions say "actual using `java.util.zip.Inflater`" in `jvmMain` and "actual using `platform.zlib`" in `iosMain`.

**Recommendation:** Pick one pattern and apply it consistently throughout the spec:
- Option A (expect/actual): `expect class DriverFactory` in `commonMain`, `actual class DriverFactory(val path: Path)` per source set. No `interface` involved.  
- Option B (interface + impl): `interface DriverFactory` in `commonMain`, `class JvmDriverFactory(val path: Path) : DriverFactory` in `jvmMain`. No `actual` keyword.  

Apply the same fix to `ZlibInflater`. The spec must be internally consistent because the claim is "no further architectural consultation is required."

---

### BLOCKER-2

**Finding:** `puklic.kmp-library` applied to iOS-only modules (`ios/app`, `ios/platform`) configures JVM toolchain + Android targets, which don't exist in those modules, causing Gradle sync failure.

**Evidence (Q2 plugin description, §5):**
> `puklic.kmp-library` configures: Kotlin version, **JVM toolchain (21)**, **Android target SDK (35)/minSdk (26)**, iOS targets  
> `ios/app/build.gradle.kts — applies puklic.kmp-library (iOS targets only)`  
> `ios/platform/build.gradle.kts — applies puklic.kmp-library (iOS targets only)`

**Reasoning:** The parenthetical "(iOS targets only)" contradicts the stated convention plugin body, which applies JVM toolchain configuration and Android Gradle Plugin. Applying AGP to a module without an `AndroidManifest.xml` or Android SDK setup fails at Gradle sync. No `puklic.ios-app` or `puklic.ios-library` convention plugin is specified. An engineer following this spec will get a Gradle configuration error on first `./gradlew projects`.

**Recommendation:** Add a `puklic.ios-library.gradle.kts` convention plugin that configures only Kotlin/Native targets (iosArm64, iosX64, iosSimulatorArm64) and the default hierarchy template, without JVM toolchain or AGP. Apply it to `ios/app` and `ios/platform` instead of `puklic.kmp-library`.

---

### BLOCKER-3

**Finding:** Version catalog `libs.versions.toml` contains `0.4.x` as literal version strings for two entries — invalid TOML that will cause Gradle to fail parsing the version catalog before any build step.

**Evidence (§6 Build tooling table):**
> `detekt-compose-rules (io.nlopez)` | `0.4.x (est.)` | Compose-specific rules  
> `ktlint-compose-rules (io.nlopez)` | `0.4.x` | Compose ktlint rules

**Reasoning:** `libs.versions.toml` is a structured TOML file. Gradle's version catalog parser requires a valid semver or exact version string in the `[versions]` table. The literal string `0.4.x` (or `0.4.x (est.)`) is not parseable — Gradle will throw `VersionCatalogBuilder: version '0.4.x' is not a valid version` and halt before any module compiles. The spec claims "A Kotlin engineer can use this document as their sole reference for `gradle init`" — the TOML as written will not load.

**Recommendation:** Pin to a specific version (e.g., `0.4.22` for both, verified against Maven Central before use). Remove `(est.)` annotations from all version catalog entries — move them to a comment block above the table.

---

### CRITICAL-4

**Finding:** Decompose navigation is identified as the "most consequential decision" in the spec summary, but no ADR exists for it — the only ADRs are 0001–0004, none covering navigation library choice.

**Evidence (§1 Summary, §4 Q4):**
> "The most consequential decisions: **Decompose 3.x navigation** — only library with production-ready KMP three-pane adaptive support."  
> ADR index (verified): `0001-compose-mpp-everywhere.md`, `0002-token-paste-login.md`, `0003-cache-strategy.md`, `0004-coroutine-first.md`. No ADR for navigation.

**Reasoning:** CLAUDE.md documentation workflow states "every architectural or domain change must update at least one file in `docs/` in the same commit as the code." The navigation choice is a Phase 1 foundational dependency — it affects `:shared:compose-ui`, `:shared:repositories` (ViewModels use Decompose `ComponentContext`), `:desktop:app`, and all stub platform modules. The spec recommends creating ADR-0005 for `ignoreUnknownKeys` (a minor exception) but omits an ADR for the navigation library that shapes the entire component lifecycle. When Phase 2 revisits iOS navigation behavior, there will be no recorded rationale to argue against.

**Recommendation:** Create `docs/01_architecture/adr/0005-decompose-navigation.md` before any code is written. The spec's Q4 contains sufficient rationale. Move the `ignoreUnknownKeys` exception to ADR-0006.

---

### CRITICAL-5

**Finding:** `:shared:repositories` bundles ViewModels (presentation layer) with Repositories (data layer) in a single module — violates SRP and the data-flow layering rule in CLAUDE.md.

**Evidence (§5 `shared/repositories/` file inventory):**
> `MessageRepository.kt`, `GuildRepository.kt`, `ChannelRepository.kt`, `UserRepository.kt`, `EmojiRepository.kt`, `OutboundMessageQueue.kt`, `SessionCache.kt`, `MentionResolver.kt`, `EmojiResolver.kt`,  
> **`MessageListViewModel.kt`**, **`GuildListViewModel.kt`**, **`ChannelListViewModel.kt`**, **`SettingsViewModel.kt`**

**Reasoning:** CLAUDE.md rule 3 states "Discord DTO → Domain → Persistence → **UI state** → Compose" as explicit layering. ViewModels are UI state machines — they hold `StateFlow<MessageListState>` (presentation state) and their lifecycle is tied to Decompose `ComponentContext` (navigation). Repositories are data-access objects. Putting both in one module means: (a) `:shared:repositories` transitively drags in Decompose and Koin-compose as compile dependencies, (b) any domain/schema change requires touching the same module as UI state changes (single module = single PR bottleneck), (c) testing repositories now requires mocking ViewModel infrastructure. The module is named "repositories" but is actually doing the job of a "presentation layer."

**Recommendation:** Move `*ViewModel.kt` files to `:shared:compose-ui` where they belong alongside the screens that consume them. `:shared:repositories` should contain only repositories, resolvers, and the session cache. The DAG already shows `compose-ui` → `repositories`, so ViewModels in `compose-ui` can still depend on repositories.

---

### CRITICAL-6

**Finding:** Risk R7 explicitly states that test fixtures use `DiscordJson` (with `ignoreUnknownKeys = true`) — this breaks schema-drift detection. When Discord adds a new field, all mapper tests silently pass, no one notices, and the field is never mapped.

**Evidence (§8 Risk Register R7):**
> "The DiscordJson instance has ignoreUnknownKeys=true in production. **Test fixtures use the same Json instance** so this is not a test risk."

**Reasoning:** The entire justification for `ignoreUnknownKeys` is that Discord adds new fields continuously. The test suite is the one place where you WANT strict mode so that schema additions are caught immediately (test fails → engineer evaluates whether the new field is needed → explicit decision). Using the lenient production `Json` instance for fixture deserialization defeats this. The spec dismisses this as "not a test risk" but the consequence is: Discord silently extends `MessageCreateEvent` with a new field → existing tests pass → no one maps it → UI is missing data for months until a user reports it.

**Recommendation:** Define a separate `DiscordJsonStrict = Json { ignoreUnknownKeys = false }` in `commonTest` source set (or test fixtures directory). All mapper unit tests use `DiscordJsonStrict`. Production code uses `DiscordJson`. When Discord adds a field and tests break, the engineer makes an explicit choice: add it to the DTO or add it to a documented `extras: JsonObject` field. No silent ignoring.

---

### CRITICAL-7

**Finding:** `compose-material3-adaptive 1.1.0` is described as providing `ChildPanels` — `ChildPanels` is a Decompose API, not from this library. The dependency description is factually wrong and will mislead any engineer implementing the build.

**Evidence (§6 UI dependencies table):**
> `compose-material3-adaptive` | `1.1.0 (est.)` | **WindowSizeClass, ChildPanels**

**Reasoning:** `ChildPanels` is `com.arkivanov.decompose:decompose`'s component for multi-pane navigation (described correctly in Q4 as Decompose's API). `compose-material3-adaptive` provides `ThreePaneScaffold`, `ListDetailPaneScaffold`, `AdaptiveLayoutDirective` — adaptive layout scaffolding, not navigation. An engineer reading §6 will search `compose-material3-adaptive` for `ChildPanels` and fail. More critically, if the engineer questions whether `compose-material3-adaptive` is even needed (Decompose handles the multi-pane navigation), the spec provides no answer — the dependency may be unnecessary overhead that overlaps with Decompose's adaptive support. `WindowSizeClass` also ships bundled with Compose Multiplatform, not exclusively from this library.

**Recommendation:** Correct the "Notes" column to "Adaptive scaffold utilities (`ThreePaneScaffold`, `ListDetailPaneScaffold`) — may be needed if Decompose `ChildPanels` layout doesn't directly use M3 adaptive breakpoints." If after verification `compose-material3-adaptive` is not used (Decompose provides its own adaptive handling via `ChildPanels`), drop this dependency. `ChildPanels` description must be moved to the Decompose row.

---

### CRITICAL-8

**Finding:** `Capabilities.kt` hardcodes `16381` with no runtime adaptation mechanism — Discord changes this value periodically, and when it does the gateway receives malformed `IDENTIFY` and the client silently misbehaves.

**Evidence (§9 What This Spec Does NOT Include):**
> "Discord `capabilities` integer value — currently `16381` per discord-protocol.md. Must be kept in sync with Discord's evolving expectation; tracked in `Capabilities.kt`."  
> `discord-protocol.md`: "Discord develops this internally; values change (typically `16381` as of May 2026)"

**Reasoning:** The spec defers the adaptation mechanism to "the implementing engineer" with no design. The hardcoded value affects what `READY_SUPPLEMENTAL` data the gateway sends, how guild members are chunked, and what experimental features activate. When Discord changes the expected value (which they do multiple times per year as features ship), Puklic users will receive incomplete or malformed initial state without any error — not a `401`, not a gateway close code. The failure is silent and data-level. "Tracked in `Capabilities.kt`" means someone must monitor the official client's network traffic to notice, with no alerting.

**Recommendation:** Design a minimal adaptation layer before implementation: (a) expose `CAPABILITIES_VERSION` as a named constant with a comment referencing the discord-protocol.md and the value source, (b) add a gateway `READY` event handler that logs a warning if the response shape differs from expected (missing guild count, missing supplemental events), (c) document the procedure for updating the value in `docs/02_domain/discord-protocol.md`. This is a maintenance SLA question, not just a code question — needs a documented update procedure.

---

### MEDIUM-9

**Finding:** `org.gradle.configuration-cache=true` is enabled in the baseline `gradle.properties` without verification that the full version combination (KMP + Compose MP + SQLDelight + AGP 8.7.x) is actually configuration-cache-compatible.

**Evidence (Appendix: gradle.properties baseline):**
> `org.gradle.configuration-cache=true`

**Reasoning:** Configuration cache compatibility of the Compose Multiplatform Gradle plugin and the SQLDelight Gradle plugin at the specified versions is not confirmed in the spec. The risk register mentions SQLDelight K2 compatibility (R3) but not configuration cache. Known historical issues include: CMP plugin's resource processing tasks, SQLDelight's schema generation task (`generateSqlDelightInterface`), and KMP's `cinteropCommonization` step. Enabling this globally from day 1 with an unverified plugin set means the first `./gradlew build` may silently corrupt the cache and produce false build results, or fail with opaque `ClassCastException` from the cache deserializer.

**Recommendation:** Move `org.gradle.configuration-cache=true` to `gradle.properties.example` or a comment block. Enable it explicitly after confirming each plugin version is compatible. Add a risk register entry: "R11: Configuration cache compatibility with CMP Gradle plugin unverified — enable incrementally after each plugin update."

---

### MEDIUM-10

**Finding:** `android.nonFinalResIds=false` is being actively deprecated in AGP 8.x and will produce build warnings at 8.7.2, potentially becoming an error in future patch versions during Phase 1 development.

**Evidence (Appendix: gradle.properties baseline):**
> `android.nonFinalResIds=false`

**Reasoning:** AGP has been defaulting `nonFinalResIds=true` since AGP 7.x for incremental build performance. The explicit `false` suppresses final resource IDs and exists primarily to maintain compatibility with reflection-based code that assumes `R.id.*` are compile-time constants. Puklic has no such code (it uses Compose, not XML layouts/data binding). Setting it to `false` generates a deprecation warning in AGP 8.x and the property may be removed in AGP 9.x (likely during Phase 2). This creates a warning-on-first-build noise for an Android stub that doesn't yet have any resources.

**Recommendation:** Remove `android.nonFinalResIds=false` from the baseline. AGP default (`true`) is correct for a new Compose-only project.

---

### MEDIUM-11

**Finding:** The Phase 1 implementation order is Linux-only — a macOS developer cannot execute Step 12 (D-Bus + libsecret + libayatana) and the spec provides no alternate path.

**Evidence (§7 Phase 1 Implementation Order, Step 12):**
> "Implement `:desktop:platform-linux` — PlatformPaths (real), SecureStorage (libsecret), NotificationService (D-Bus) | Manual smoke test: token store/retrieve, notification appears"

**Reasoning:** CLAUDE.md identifies Linux desktop as the primary Phase 1 platform, and `macOS/Windows desktop` as "stubs only in Phase 1." But if the sole developer is on macOS (the most common JVM development machine), Step 12 can compile (JVM code) but cannot smoke-test: `libsecret` via JNA requires `libsecret-1.so`, D-Bus requires `libdbus-1.so`, `libayatana-appindicator3.so` — none of which exist on macOS. Step 14 (`./gradlew :desktop:app:run`) on macOS would also pick up Linux platform implementations at runtime (since `desktop:app` depends on `platform-linux` runtime), resulting in JNA `UnsatisfiedLinkError` on first launch. The spec's risk register has no entry for this.

**Recommendation:** Add to the risk register: "R11: Phase 1 implementation order assumes Linux host. macOS developer must skip Step 12 smoke tests and use `platform-macos` stubs for local dev." Add a note to Step 12 and Step 14 clarifying the host OS requirement. The `AppModule.kt` OS-detection logic (which platform impl to use at runtime) must be implemented before Step 14 regardless of host OS.

---

### MEDIUM-12

**Finding:** The convention plugin for static analysis is named `puklic.detekt.gradle.kts` but handles both detekt AND ktlint — the architect acknowledges this is misleading but leaves it unfixed in the same document.

**Evidence (Q12):**
> "Applied via `jlleitschuh/gradle-ktlint` plugin in the `puklic.detekt` convention plugin (**name is misleading — the convention plugin handles both**)"

**Reasoning:** The spec claims to be the definitive setup reference. Naming an artifact incorrectly while acknowledging the error is documenting a known defect. Every future engineer who asks "where is ktlint configured?" will not look in `puklic.detekt.gradle.kts`. The plugin inventory in Q2 lists only six plugins by ID — changing a name now costs zero implementation effort, whereas fixing it after 15 modules have applied it costs a rename + test + commit.

**Recommendation:** Rename to `puklic.quality.gradle.kts` (or `puklic.static-analysis.gradle.kts`). Update Q2 plugin inventory table and Q12 body. Zero cost before implementation, non-trivial after.

---

### MEDIUM-13

**Finding:** The stated advantage of `build-logic/` over `buildSrc/` for configuration cache is factually overstated — in Gradle 8.x, both are included builds and both invalidate the configuration cache when their sources change.

**Evidence (Q2 table):**
> `buildSrc`: "any change invalidates the entire main build configuration cache"  
> `build-logic/`: "Cached as a separate included build; **only rebuilds when plugin source changes**"

**Reasoning:** The table implies `buildSrc` invalidates the config cache on EVERY run, while `build-logic/` only invalidates on plugin source changes. This is incorrect. In Gradle 8.x, BOTH buildSrc and any included build invalidate the configuration cache when their source files change. The difference is: `buildSrc` automatically goes on the classpath of every subproject; `build-logic/` requires explicit `plugins { id("puklic.xxx") }`. The real justification for `build-logic/` is classpath isolation (avoiding accidental dependency leakage), not differential cache invalidation. The caching argument as written would not hold up to scrutiny from a Gradle expert on the team.

**Recommendation:** Correct the Q2 rationale to state the actual deciding factor: classpath isolation (`build-logic/` explicit plugin application prevents accidental classpath leakage). Remove the incorrect cache invalidation differential from the table.

---

### MEDIUM-14

**Finding:** The `ZlibInflater` JVM and Android implementations are described as identical (`java.util.zip.Inflater` in both) but are specified as separate files — this duplicates code that will diverge over time.

**Evidence (§5 `shared/protocol-discord/`):**
> `ZlibInflaterJvm.kt — actual using java.util.zip.Inflater; createHttpClient() with CIO engine`  
> `ZlibInflaterAndroid.kt — actual using java.util.zip.Inflater; createHttpClient() with OkHttp engine`

**Reasoning:** `java.util.zip.Inflater` is available on both JVM and Android — the implementations are identical. The only difference between `jvmMain` and `androidMain` for zlib is that they use different Ktor engines. If zlib decompression logic is extracted into a shared file (a `jvmCommon` internal file shared between `jvmMain` and `androidMain`, or by using expect/actual only for the Ktor engine factory), the duplication is eliminated. As written, any bug fix in zlib buffer handling must be applied twice.

**Recommendation:** Factor out `ZlibInflaterJvmBase.kt` in a shared `jvmCommon` internal source set (which doesn't exist natively in the KMP hierarchy but can be emulated with a manual `sourceSets` configuration), or use expect/actual only for the Ktor engine factory and share the zlib implementation. At minimum, add a comment "these two files must stay in sync" so the duplication is intentional, not accidental.

---

### MEDIUM-15

**Finding:** Kermit's `FileLogWriter` rotation capability (`2 MB, 3 files`) is stated as fact but needs verification — `kermit-io` 2.x's file writer rotation configuration API may differ from or not support the described parameters out of the box.

**Evidence (Q10):**
> "Kermit's `FileLogWriter` (available in `kermit-io` artifact) writes to `PlatformPaths.crashDir / "puklic.log"` with **rotation at 2 MB (keep last 3 files)**"

**Reasoning:** Kermit 2.x `kermit-io` ships a `FileLogWriter` but the public API for configuring rotation size and file count is not universally documented. The spec presents these parameters as settled configuration, but if `FileLogWriter` requires a custom implementation of rolling logic (wrapping a `RollingFileWriter` or similar), that's an implementation task that should appear in the implementation order, not be assumed. Incorrectly assuming this works out-of-the-box risks a crash-log implementation that either writes to a single unbounded file (violating ADR-0003 cache-bounded principle) or requires a custom log writer.

**Recommendation:** Add to the risk register: "R12: Kermit `kermit-io` file rotation parameters — verify `FileLogWriter` supports `maxFileSizeBytes` and `maxFiles` configuration before Step 10." If not natively supported, design a `RollingKermitFileWriter` wrapper as a distinct implementation task.

---

### NIT-16

**Finding:** `DiscordJson.kt` is listed twice under the same `commonMain/kotlin/` path in the `shared/protocol-discord/` file inventory.

**Evidence (§5 `shared/protocol-discord/`):**
> First block: `shared/protocol-discord/src/commonMain/kotlin/` → `DiscordJson.kt`, `dto/`, `mapper/`, `rest/`, `gateway/`, `Capabilities.kt`  
> Second block (same path): `shared/protocol-discord/src/commonMain/kotlin/` → `ZlibInflater.kt — interface (commonMain)`

**Reasoning:** The second block re-opens the same source path. `ZlibInflater.kt` should be in the first block alongside `DiscordJson.kt`. The duplicate section heading suggests the spec was assembled from parts without a final consistency pass.

**Recommendation:** Merge the two `commonMain/kotlin/` blocks. Add `ZlibInflater.kt` to the first block alongside `DiscordJson.kt`.

---

### NIT-17

**Finding:** "Test-first principle" claimed for Step 5 (`:shared:platform-api`) is not meaningful — the module is pure interfaces + test doubles, which have nothing to TDD against.

**Evidence (§7 Step 5 + test-first note):**
> "Step 5: Implement `:shared:platform-api` — interfaces + test doubles (FakeSecureStorage, etc.) | `:shared:platform-api:test` green"  
> "Steps 3–11 follow the test-first principle: write the interface + test doubles + failing tests BEFORE implementing the production code."

**Reasoning:** Test-first (TDD) means: write a failing test, then write production code to make it pass. For a module that IS ONLY interfaces and test doubles, there is no production code to drive. `FakeSecureStorage` is itself the test double — it has no prior "failing test." The claim that Step 5 follows TDD is dogmatic application of a label that doesn't fit. This is harmless by itself, but a spec that conflates "writing test doubles" with "TDD" may cause the implementing engineer to waste time writing meaningless tests for interface declarations.

**Recommendation:** Narrow the test-first claim: "Steps 3–4 (ids, domain) and Step 8 (chat-parser) follow TDD strictly. Steps 5 (platform-api) and 9 (protocol DTOs) use contract-first design: define interfaces, then write tests against fake implementations."

---

### NIT-18

**Finding:** `./gradlew build` as the success criterion for every step conflates "compiles" with "tests pass" and will be trivially true for stub-only modules.

**Evidence (§7 Step 2):**
> "Add all module `build.gradle.kts` to `settings.gradle.kts`. Create empty `src/` dirs. | `./gradlew projects` lists all modules"  
> (Implied by "do NOT proceed to the next step until the current one passes `./gradlew build`")

**Reasoning:** `./gradlew build` on an empty module with no source files produces a green result. At Step 3 (`:shared:ids`), the spec says the success criterion is `:shared:ids:test` green — which requires actual tests to have been written and pass. But at Step 9 (protocol-discord — DTOs and mappers), no live network is available, so "mapper unit tests green" can only test the mapper logic. The universal `./gradlew build` criterion doesn't distinguish between "builds" and "meaningful behavior verified." Steps with smoke-test criteria (Step 12, Step 14) are better specified than pure `build`-based criteria.

**Recommendation:** Per-step success criteria should specify whether tests are expected to exist and pass, or whether compilation-only is sufficient. Existing criteria for Steps 12 and 14 are the right model — apply the same precision to Steps 3–11.

---

## Summary Table

| # | Severity | One-line finding |
|---|---|---|
| 1 | BLOCKER | `interface` + `actual` in `DriverFactory`/`ZlibInflater` — Kotlin compile error |
| 2 | BLOCKER | `puklic.kmp-library` on iOS-only modules configures Android targets → Gradle sync error |
| 3 | BLOCKER | `0.4.x` literal in `libs.versions.toml` — invalid TOML, catalog parse failure |
| 4 | CRITICAL | No ADR for Decompose navigation — most consequential decision lacks formal record |
| 5 | CRITICAL | ViewModels in `:shared:repositories` — SRP violation mixing data and presentation layer |
| 6 | CRITICAL | Test fixtures use `DiscordJson` (lenient) — schema drift never caught by tests |
| 7 | CRITICAL | `compose-material3-adaptive` described as providing `ChildPanels` — wrong library |
| 8 | CRITICAL | `Capabilities.kt` hardcoded with no adaptation mechanism for Discord's evolving value |
| 9 | MEDIUM | `configuration-cache=true` compatibility with this version set is unverified |
| 10 | MEDIUM | `android.nonFinalResIds=false` is deprecated in AGP 8.x |
| 11 | MEDIUM | Phase 1 implementation order is Linux-only, no alternate path for macOS developer |
| 12 | MEDIUM | `puklic.detekt.gradle.kts` acknowledged misleading, unfixed |
| 13 | MEDIUM | `build-logic/` caching benefit over `buildSrc` is factually incorrect in Gradle 8.x |
| 14 | MEDIUM | JVM and Android `ZlibInflater` implementations are identical — needless duplication |
| 15 | MEDIUM | Kermit `FileLogWriter` rotation parameters stated as fact, not verified |
| 16 | NIT | `DiscordJson.kt` listed twice under same `commonMain/kotlin/` path |
| 17 | NIT | "Test-first" claim for `platform-api` (interface-only module) is not TDD |
| 18 | NIT | `./gradlew build` success criterion is ambiguous for stub-only modules |

---

<JERVIS_CRITIC_RESULT>
report_path: docs/03_infrastructure/architect-reports/2026-05-21-gradle-setup-critic.md

counts:
  blockers: 3
  critical: 5
  medium: 7
  nit: 3

top_line_summary: >
  The spec contains three compile-time blockers that will prevent any engineer from 
  producing a working initial build: the `interface`+`actual` contradiction in 
  `DriverFactory`/`ZlibInflater`, the `puklic.kmp-library` plugin applied to 
  iOS-only modules (which will configure Android targets that don't exist), and 
  literal `0.4.x` version strings in the TOML version catalog. Beyond the blockers, 
  the most structurally damaging design decisions are bundling ViewModels into 
  `:shared:repositories` (mixing presentation and data layers), the absence of an 
  ADR for the Decompose navigation choice (the spec's own "most consequential 
  decision"), and the test fixture strategy using the lenient `DiscordJson` instance 
  which permanently disables schema-drift detection in CI. The version table also 
  contains a factual error (ChildPanels attributed to `compose-material3-adaptive` 
  rather than Decompose) that will cause direct implementation confusion.

top_3_must_address_before_code:
  1: >
    Fix the `interface`+`actual` contradiction (BLOCKER-1): choose expect/actual 
    OR interface+impl throughout the spec for `DriverFactory` and `ZlibInflater`, 
    and add a missing `puklic.ios-library.gradle.kts` convention plugin for 
    iOS-only modules (BLOCKER-2).
  2: >
    Fix `libs.versions.toml` (BLOCKER-3): replace all `0.4.x` and `(est.)` 
    annotations with pinned, verified version strings before the version catalog 
    is created — the spec cannot serve as the claimed "sole reference for 
    gradle init" with unparseable version entries.
  3: >
    Create ADR-0005 for Decompose navigation (CRITICAL-4) and move ViewModels out 
    of `:shared:repositories` into `:shared:compose-ui` (CRITICAL-5) — both must 
    be resolved in the design phase because they affect the module dependency graph 
    and every subsequent implementation step builds on it.
</JERVIS_CRITIC_RESULT>

---

## R2 Verification (2026-05-21)

MEDIUM/NIT findings (9–18) explicitly deferred per orchestrator — not re-verified this round.

---

### BLOCKER-1 — RESOLVED

**Evidence from r2 (Q7/§5 `shared/protocol-discord/`, Q9/§5 `shared/persistence-sqldelight/`):**

> `commonMain`: `interface ZlibInflater` — declares the inflation contract  
> `jvmMain`: `class JvmZlibInflater : ZlibInflater` — uses `java.util.zip.Inflater`  
> `androidMain`: `class AndroidZlibInflater : ZlibInflater` — uses `java.util.zip.Inflater`  
> `iosMain`: `class IosZlibInflater : ZlibInflater` — uses `platform.zlib` CInterop

> `commonMain`: `interface DriverFactory { fun createDriver(): SqlDriver }` (interface+impl pattern, NOT expect/actual)  
> `jvmMain`: `class JvmDriverFactory(private val path: Path) : DriverFactory`  
> `androidMain`: `class AndroidDriverFactory(private val context: Context) : DriverFactory`  
> `iosMain`: `class IosDriverFactory : DriverFactory`

Both `DriverFactory` and `ZlibInflater` now use "interface + impl" consistently throughout Q7, Q9, and §5 file inventory. The word `actual` does not appear for either type anywhere in the r2 spec (grep confirmed zero matches). Option B from the r1 recommendation was applied.

---

### BLOCKER-2 — RESOLVED

**Evidence from r2 (Q2 plugin inventory + §5):**

> `puklic.ios-library` | `puklic.ios-library.gradle.kts` | `:ios:app`, `:ios:platform` — iOS-only modules

> **`puklic.ios-library` scope:** Configures Kotlin/Native targets only — iosArm64, iosX64, iosSimulatorArm64 — with the default hierarchy template. Does NOT configure JVM toolchain and does NOT apply the Android Gradle Plugin.

> **`puklic.kmp-library` scope:** Applied to all `:shared:*` modules. **NOT applied to `:ios:*` modules** — those use `puklic.ios-library` instead.

> `ios/app/build.gradle.kts — applies puklic.ios-library`  
> `ios/platform/build.gradle.kts — applies puklic.ios-library`

The `puklic.ios-library.gradle.kts` convention plugin was added exactly as recommended. Q1's "Implication" section also reinforces: "The iOS-only app modules (`:ios:app`, `:ios:platform`) use the separate `puklic.ios-library` convention plugin."

---

### BLOCKER-3 — RESOLVED

**Evidence from r2 (§6 Build tooling table):**

> `detekt-compose-rules (io.nlopez)` | **0.4.22** | Compose-specific rules  
> `ktlint-compose-rules (io.nlopez)` | **0.4.22** | Compose ktlint rules

Both entries now carry the pinned version `0.4.22`. The `(est.)` annotation is gone from `compose-material3-adaptive` (now `1.1.0`). Grep for `0.4.x` and `est.)` in the r2 spec returns zero matches.

---

### CRITICAL-4 — RESOLVED

**Evidence from r2:**

- `docs/01_architecture/adr/0005-decompose-navigation.md` created (verified file exists, full content read).
- ADR README updated: row `| [0005](0005-decompose-navigation.md) | Decompose as the navigation library | accepted |` present.
- Spec §1 Summary: "Decompose 3.x navigation — only library with production-ready KMP three-pane adaptive support (see ADR-0005)."
- Spec Q4: "This decision is formally recorded in ADR-0005 (`docs/01_architecture/adr/0005-decompose-navigation.md`)."

The ADR covers all required sections: Context, four Options considered with pros/cons, Decision with rationale, Consequences (including explicit lifecycle/scope implications), and Related documents. The `ignoreUnknownKeys` exception was correctly moved to ADR-0006 (per r1 recommendation), though ADR-0006 itself is deferred — see New Issues below.

---

### CRITICAL-5 — RESOLVED

**Evidence from r2 (§5 `shared/repositories/` and `shared/compose-ui/`):**

`:shared:repositories/` file inventory contains only:
> `MessageRepository.kt`, `GuildRepository.kt`, `ChannelRepository.kt`, `UserRepository.kt`, `EmojiRepository.kt`, `OutboundMessageQueue.kt`, `SessionCache.kt`, `MentionResolver.kt`, `EmojiResolver.kt`

No `*ViewModel.kt` files. The four ViewModels are now in `:shared:compose-ui/src/commonMain/kotlin/viewmodels/`:
> `MessageListViewModel.kt — StateFlow<MessageListState>; Decompose ComponentContext lifecycle owner`  
> `GuildListViewModel.kt`, `ChannelListViewModel.kt`, `SettingsViewModel.kt`

DAG (§3) updated: `:shared:repositories` annotated "repositories + resolvers only — no ViewModels"; `:shared:compose-ui` annotated "owns ViewModels (presentation layer)." Q4 also explicitly states: "ViewModels reside in `:shared:compose-ui` (presentation layer), not in `:shared:repositories` (data layer)."

---

### CRITICAL-6 — RESOLVED

**Evidence from r2 (Q8, §5 `shared/protocol-discord/commonTest/`):**

> **Decision: Two `Json` instances — one lenient (production), one strict (tests).** `ignoreUnknownKeys = true` scoped exclusively to the production Discord DTO `Json` instance in `:shared:protocol-discord`.

> `DiscordJsonStrict.kt` — `internal val DiscordJsonStrict = Json { ignoreUnknownKeys = false }`; used by ALL mapper tests

Risk Register R7 updated:
> "Intended behavior: CI failure prompts explicit DTO update decision. Update DTOs and/or fixtures when new fields appear. Do NOT switch test fixtures to the lenient `DiscordJson` instance."

The strict test instance is placed in `commonTest` exactly as recommended. Q8 also adds an explicit "Test fixture rule": all mapper unit tests and fixture-based deserialization use `DiscordJsonStrict`.

---

### CRITICAL-7 — RESOLVED

**Evidence from r2 (§6 UI table):**

> `compose-material3-adaptive` | `1.1.0` | Adaptive scaffold utilities: ThreePaneScaffold, ListDetailPaneScaffold, adaptive breakpoints — **does NOT provide ChildPanels (that is Decompose's API)**

> `decompose` | `3.3.0` | Navigation; **ChildPanels multi-pane API**; ComponentContext lifecycle

The factual error is corrected. Q4 also states: "The `ChildPanels` component in Decompose 3.x (part of the `decompose` library, not `compose-material3-adaptive`)". ADR-0005 consequences: "🔒 `ChildPanels` comes from the `decompose` library (not from `compose-material3-adaptive`, which provides `ThreePaneScaffold`)."

---

### CRITICAL-8 — RESOLVED

**Evidence from r2 (§5 `Capabilities.kt` note + §9):**

> **Note on `Capabilities.kt`:** The file exposes `const val CAPABILITIES_VERSION = 16381` with a doc-comment referencing `docs/02_domain/discord-protocol.md` and the procedure for updating this value (see §9). The `GatewayConnection` `READY` event handler logs a `Warn`-level message if the response shape indicates an unexpected capabilities configuration (e.g., `READY_SUPPLEMENTAL` absent, guild count zero when guilds are expected).

> **§9:** The update procedure must be documented in `docs/02_domain/discord-protocol.md` before Phase 1 code freeze. Minimum required documentation: (a) how to observe the current value from the official client's gateway traffic, (b) which `READY` / `READY_SUPPLEMENTAL` fields to check to detect a mismatch, (c) the Git commit message template to use when updating the constant so it's traceable.

All three items from the r1 recommendation are addressed: (a) named constant with doc-comment referencing `discord-protocol.md`, (b) `READY` handler logs `Warn` on unexpected response shape, (c) §9 mandates full update procedure documentation before Phase 1 code freeze. The mechanism is now designed in the spec; the `discord-protocol.md` documentation task is explicitly in-scope for Phase 1.

---

## New issues introduced in r2

### NEW-1 — ADR-0006 dangling forward reference (Minor)

**[spec:§1 Summary, spec:§5 DiscordJson.kt, spec:Q8]**

The r2 spec references ADR-0006 in three places as if it exists: `§1 Summary` says "(see ADR-0006)", `§5` DiscordJson.kt annotation says "see ADR-0006", and Q8 code block says "// Discord external API exception (see ADR-0006)". However, ADR-0006 does not exist — the file is absent and the ADR README has no ADR-0006 row. §9 contradicts these present-tense references by stating "ADR-0006 (ignoreUnknownKeys exception) — this spec recommends creating it... Left to the engineer."

An implementing engineer following the spec will look for ADR-0006 to understand the `DiscordJson` exception and find nothing. The spec's own claim of being the "sole reference for gradle init" is undermined by a broken reference. This is not a compile blocker — the DI and serialization design are fully specified in Q8 — but the dangling `(see ADR-0006)` annotations misrepresent document completeness.

- **Recommendation:** Change the three "(see ADR-0006)" references to "(see Q8 in this spec; ADR-0006 to be created)" until ADR-0006 is actually written. Alternatively, write ADR-0006 now — Q8 already contains the full rationale, it is a one-paragraph job.

### NEW-2 — Tense conflict on `Capabilities.kt` update procedure (Minor)

**[spec:§5 line 620 vs spec:§9]**

§5 `Capabilities.kt` note states: "The update procedure **is documented** in `docs/02_domain/discord-protocol.md`" (present tense, implying the document already contains this). §9 states: "The update procedure **must be documented** in `docs/02_domain/discord-protocol.md` before Phase 1 code freeze" (future obligation, task not yet done).

If the update procedure is not yet in `discord-protocol.md`, the §5 present-tense reference is factually false and will cause the implementing engineer to look for documentation that doesn't exist, potentially skipping writing it themselves since they believe it's already there.

- **Recommendation:** Change §5 to "The update procedure will be documented in `docs/02_domain/discord-protocol.md` (see §9 for required content)" to match the §9 future obligation.

---

<JERVIS_CRITIC_R2_RESULT>
r1_findings_verified:
  BLOCKER-1: RESOLVED
    method: interface+impl pattern applied consistently to both DriverFactory and ZlibInflater; zero `actual` occurrences for these types in r2 spec
  BLOCKER-2: RESOLVED
    method: puklic.ios-library.gradle.kts added to plugin inventory; kmp-library explicitly excludes :ios:* modules; both ios/app and ios/platform apply ios-library in §5
  BLOCKER-3: RESOLVED
    method: detekt-compose-rules and ktlint-compose-rules pinned to 0.4.22; all (est.) annotations removed; zero unparseable version strings remain
  CRITICAL-4: RESOLVED
    method: ADR-0005 file created and complete; ADR README updated; spec Q4 and §1 reference it
  CRITICAL-5: RESOLVED
    method: ViewModels removed from :shared:repositories §5 inventory; placed in :shared:compose-ui viewmodels/; DAG annotations updated; Q4 explicitly confirms placement
  CRITICAL-6: RESOLVED
    method: DiscordJsonStrict defined in commonTest; all mapper tests directed to use strict instance; Risk R7 rewritten to describe CI-gate intent; production/test separation is explicit
  CRITICAL-7: RESOLVED
    method: compose-material3-adaptive notes column corrected ("does NOT provide ChildPanels"); decompose row now lists "ChildPanels multi-pane API"; ADR-0005 consequences reinforce correct attribution
  CRITICAL-8: RESOLVED
    method: §5 adds CAPABILITIES_VERSION named constant with doc-comment + READY handler Warn log; §9 mandates update procedure documentation with three explicit sub-items before Phase 1 code freeze

new_issues_in_r2:
  NEW-1:
    severity: Minor (not a blocker)
    finding: ADR-0006 referenced as existing in §1/§5/Q8 but file does not exist and is not in ADR README; §9 contradicts by deferring creation to engineer
  NEW-2:
    severity: Minor (not a blocker)
    finding: §5 present-tense "The update procedure is documented in discord-protocol.md" contradicts §9 future-tense "must be documented before Phase 1 code freeze"

new_blockers_in_r2: NONE

summary: >
  All 3 BLOCKERS and all 5 CRITICAL findings from the r1 review are fully resolved in r2.
  The two new issues introduced are minor documentation inconsistencies (dangling ADR-0006 
  reference, tense conflict on Capabilities update procedure) — neither prevents gradle init, 
  compilation, or correct architectural implementation. No new BLOCKERs or CRITICALs 
  were introduced.

recommendation: PROCEED TO IMPL
</JERVIS_CRITIC_R2_RESULT>
