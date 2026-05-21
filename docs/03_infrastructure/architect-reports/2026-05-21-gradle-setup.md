# Gradle Multimodule + KMP Setup Specification

**Date:** 2026-05-21  
**Feature slug:** `gradle-setup`  
**Status:** OK — all 12 open questions resolved  
**Sources read:** CLAUDE.md, product-vision.md, module-map.md, ADR-0001 through ADR-0004, chat-model.md, discord-protocol.md, richtext-ast.md, persistence-schema.md, cache-policy.md, platform-abstractions.md, design-system.md, adaptive-layouts.md, component-library.md, linux-wayland.md, android.md, ios.md, build.md, phases.md

---

## 1. Summary

This document is the authoritative blueprint for initializing the Puklic Gradle multimodule + Kotlin Multiplatform project. It resolves all open architectural questions raised in `module-map.md` and `build.md`, specifies every Gradle file that must exist, pins library versions, and defines the Phase 1 implementation order. A Kotlin engineer can use this document as their sole reference for `gradle init` and initial module scaffolding — no further architectural consultation is required for the build setup itself.

The most consequential decisions:
- **iOS stubs from day 1** — prevents Phase 2 refactor of all shared modules.
- **`build-logic/` included build** — modern convention plugin pattern, better classpath isolation.
- **Decompose 3.x navigation** — only library with production-ready KMP three-pane adaptive support (see ADR-0005).
- **Koin 4.x DI** — matches the scope hierarchy in ADR-0004 without manual wiring complexity.
- **`ignoreUnknownKeys = true` scoped to Discord DTO parser only** — explicit exception for external API; all internal serialization uses strict mode (see ADR-0006).

---

## 1a. Revision history

- **2026-05-21 r1** — original draft
- **2026-05-21 r2** — revised per code-critic findings: fixed 3 blockers + 5 critical, MEDIUM/NIT deferred to implementation
- **2026-05-21 r3** — full Czech-to-English translation of all repo docs (bulk remediation).

---

## 2. Module List

### Phase 1 — active (compiled and tested)

| Module path | Gradle name | Targets | Purpose |
|---|---|---|---|
| `shared/ids/` | `:shared:ids` | JVM, Android, iosArm64, iosX64, iosSimulatorArm64 | Type-safe ID value classes (snowflake) |
| `shared/domain/` | `:shared:domain` | JVM, Android, iOS* | Domain types: ChatMessage, Guild, Channel, RichTextDocument |
| `shared/platform-api/` | `:shared:platform-api` | JVM, Android, iOS* | Kotlin interface contracts: SecureStorage, NotificationService, PlatformPaths, etc. |
| `shared/chat-parser/` | `:shared:chat-parser` | JVM, Android, iOS* | Pure function `parseRichText(String): RichTextDocument` |
| `shared/protocol-discord/` | `:shared:protocol-discord` | JVM, Android, iOS* | Discord DTOs, mappers, Gateway client, REST client |
| `shared/persistence-api/` | `:shared:persistence-api` | JVM, Android, iOS* | Repository interfaces + SQLDelight `.sq` schema files |
| `shared/persistence-sqldelight/` | `:shared:persistence-sqldelight` | JVM, Android, iOS* | Generated SQLDelight code + per-platform driver wiring |
| `shared/repositories/` | `:shared:repositories` | JVM, Android, iOS* | MessageRepository, GuildRepository, ChannelRepository, UserRepository, EmojiRepository, OutboundMessageQueue, SessionCache, MentionResolver, EmojiResolver |
| `shared/session/` | `:shared:session` | JVM, Android, iOS* | DiscordSession lifecycle, gateway connect/resume state machine |
| `shared/compose-ui/` | `:shared:compose-ui` | JVM, Android, iOS* | All Compose Multiplatform UI components + scaffolds; ViewModels per screen |
| `desktop/app/` | `:desktop:app` | JVM | Entry point `main()`, DI wiring, Compose Desktop window |
| `desktop/platform-linux/` | `:desktop:platform-linux` | JVM | Linux `actual` implementations: libsecret, D-Bus, libayatana |
| `desktop/platform-macos/` | `:desktop:platform-macos` | JVM | Stub Phase 1 (clipboard + paths only) |
| `desktop/platform-windows/` | `:desktop:platform-windows` | JVM | Stub Phase 1 (clipboard + paths only) |
| `android/app/` | `:android:app` | Android | Stub: Application + MainActivity skeleton (compiles, no UI logic) |
| `android/platform/` | `:android:platform` | Android | Stub: Android platform-api implementations (NotImplementedError bodies) |
| `ios/app/` | `:ios:app` | iosArm64, iosX64, iosSimulatorArm64 | Stub: Kotlin/Native entry point (compiles, no UI logic) |
| `ios/platform/` | `:ios:platform` | iosArm64, iosX64, iosSimulatorArm64 | Stub: iOS platform-api implementations (NotImplementedError bodies) |
| `tools/parser-fixtures-gen/` | `:tools:parser-fixtures-gen` | JVM | CLI for generating parser golden fixtures |

*iOS* = iosArm64 + iosX64 + iosSimulatorArm64 targets, sharing `iosMain` source set.

### Phase 2+ — deferred (NOT in initial `settings.gradle.kts`)

| Module | Phase | Notes |
|---|---|---|
| `:desktop:media-pipewire` | 3 | PipeWire audio |
| `:desktop:media-portal` | 4 | xdg-desktop-portal screenshare |
| `:shared:media-api` | 3 | `expect` audio capture/playback |

---

## 3. Dependency Graph (DAG)

Arrows mean "depends on". Read left → right for layering.

```
:shared:ids
    ▲
    └─────────────────────────────────────────────────────────────┐
                                                                   │
:shared:domain ──────────────────────────────────────────────┐    │
    ▲ (kotlinx-datetime, kotlinx-serialization)              │    │
    │                                                         │    │
    ├─ :shared:chat-parser                                    │    │
    │       (pure functions, Dispatchers.Default)             │    │
    │                                                         │    │
    ├─ :shared:protocol-discord                               │    │
    │       (Ktor Client, kotlinx-serialization)              │    │
    │                                                         │    │
    └─ :shared:persistence-api                                │    │
            (SQLDelight runtime, coroutines)                  │    │
                    ▲                                         │    │
                    │                                         │    │
         :shared:persistence-sqldelight                       │    │
             (JdbcSqliteDriver / AndroidSqliteDriver /        │    │
              NativeSqliteDriver  per source set)             │    │
                    ▲                                         │    │
                    │                                         │    │
:shared:platform-api ────────────────────────────────────────┘    │
    (pure Kotlin interfaces, coroutines only)                      │
    ▲                                                              │
    ├─ :desktop:platform-linux  (JNA, dbus-java)                  │
    ├─ :desktop:platform-macos  (stub)                            │
    ├─ :desktop:platform-windows (stub)                           │
    ├─ :android:platform         (stub)                           │
    └─ :ios:platform             (stub)                           │
                                                                   │
:shared:repositories ──────────────────────────────────────────┐  │
    ▲  (depends: protocol-discord, persistence-api,             │  │
    │            chat-parser, domain, ids)                       │  │
    │   repositories + resolvers only — no ViewModels           │  │
    │                                                            │  │
:shared:session                                                  │  │
    ▲  (depends: protocol-discord, repositories, platform-api)  │  │
    │                                                            │  │
:shared:compose-ui ─────────────────────────────────────────────┘  │
    ▲  (depends: domain, repositories, session, platform-api,      │
    │            Compose Multiplatform, Decompose, Coil, Koin)      │
    │   owns ViewModels (presentation layer)                        │
    │                                                               │
    ├─ :desktop:app  (depends: compose-ui, session, platform-linux/macos/windows, Koin)
    ├─ :android:app  (stub depends: compose-ui, android-platform, Koin)
    └─ :ios:app      (stub depends: compose-ui, ios-platform, Koin)

:tools:parser-fixtures-gen  (depends: chat-parser only)
```

**No cycles.** The layering from bottom to top is:
`ids → domain → {chat-parser, protocol-discord, persistence-api} → persistence-sqldelight → repositories → session → compose-ui → platform-apps`

`platform-api` feeds into `persistence-sqldelight`, `session`, and `compose-ui` via Koin injection; it does not cause cycles because it has no dependencies on higher layers.

---

## 4. Resolved Open Questions

### Q1 — Compose iOS scope for Phase 1

**Decision: Create `:ios:platform` and `:ios:app` as stub modules from day 1. All `:shared:*` modules declare iOS targets immediately. Stub actuals throw `NotImplementedError`.**

**Rationale:** ADR-0001 commits to Compose MPP everywhere. If iOS targets are NOT declared in Phase 1, adding them in Phase 2 requires touching every shared module's `build.gradle.kts`, every `expect` declaration, and the entire CI matrix — a large coordinated refactor. Adding stubs on day 1 costs one afternoon but eliminates that refactor entirely.

The stub strategy:
- `:ios:platform` — each `expect` interface in `:shared:platform-api` is `actual`-implemented as a Kotlin object that throws `NotImplementedError("Phase 2: requires iOS implementation")`. The code compiles and tests pass on JVM/Android; iOS simulator builds compile but are not run in Phase 1 CI.
- `:ios:app` — minimal `main.kt` (or Kotlin/Native entry point) that compiles but displays a static placeholder label.
- CI Phase 1 — JVM + Android targets only in `./gradlew build`. iOS targets gated to a separate CI job marked `allow_failure` until Phase 2.

**Alternative rejected — "iOS only when Phase 2 starts":** Saves two hours up front but creates a multi-day refactor in Phase 2 just to add source sets. Does not respect the minimum-complexity principle applied across the project lifetime.

**Implication:** Every convention plugin for `:shared:*` modules must include `iosArm64`, `iosX64`, `iosSimulatorArm64` targets from the start. The iOS-only app modules (`:ios:app`, `:ios:platform`) use the separate `puklic.ios-library` convention plugin — see Q2.

---

### Q2 — Convention plugins: buildSrc/ vs build-logic/

**Decision: `build-logic/` included build.**

**Rationale:**

| Criterion | `buildSrc/` | `build-logic/` |
|---|---|---|
| Classpath isolation | `buildSrc` output is automatically on the classpath of every subproject — risk of accidental dependency leakage | Explicit: subprojects only get what `plugins { id("puklic.xxx") }` imports |
| Gradle cache | Not cached separately — any change invalidates the entire main build configuration cache | Cached as a separate included build; only rebuilds when plugin source changes |
| Version catalog access | Requires explicit wiring (`libs` alias works since Gradle 8.1+) | Same — requires `files("../gradle/libs.versions.toml")` in `build-logic/settings.gradle.kts` |
| IDE support | Well-understood by IntelliJ | Equally good |

The decisive advantage of `build-logic/` is **classpath isolation**: subprojects only gain access to what they explicitly apply via `plugins { id("puklic.xxx") }`, preventing accidental dependency leakage that `buildSrc` allows.

**`build-logic/` plugin inventory:**

| Plugin ID | File | Applied to |
|---|---|---|
| `puklic.kmp-library` | `puklic.kmp-library.gradle.kts` | All `:shared:*` multiplatform library modules |
| `puklic.compose-library` | `puklic.compose-library.gradle.kts` | `:shared:compose-ui` (extends kmp-library, adds Compose MP plugin) |
| `puklic.ios-library` | `puklic.ios-library.gradle.kts` | `:ios:app`, `:ios:platform` — iOS-only modules |
| `puklic.jvm-library` | `puklic.jvm-library.gradle.kts` | JVM-only modules: `:desktop:platform-*`, `:tools:*` |
| `puklic.android-library` | `puklic.android-library.gradle.kts` | `:android:platform` |
| `puklic.android-app` | `puklic.android-app.gradle.kts` | `:android:app` |
| `puklic.detekt` | `puklic.detekt.gradle.kts` | All modules (applied via root + each convention plugin) |

**`puklic.kmp-library` scope:** Configures Kotlin version, JVM toolchain (21), Android target SDK (35)/minSdk (26), iOS targets (iosArm64, iosX64, iosSimulatorArm64) with default hierarchy template, common test dependencies (kotlin.test, Kotest), Kover coverage setup, and detekt. Applied to all `:shared:*` modules. **NOT applied to `:ios:*` modules** — those use `puklic.ios-library` instead.

**`puklic.ios-library` scope:** Configures Kotlin/Native targets only — iosArm64, iosX64, iosSimulatorArm64 — with the default hierarchy template. Does NOT configure JVM toolchain and does NOT apply the Android Gradle Plugin. This plugin exists specifically for modules that are iOS-only and would fail Gradle sync if Android targets were configured on them.

**Alternative rejected — `buildSrc/`:** Simpler initial setup but suboptimal for CI performance and classpath hygiene. Kotlin KMP builds are already slow; we should not make the config cache situation worse.

---

### Q3 — KMP hierarchy template

**Decision: Use Kotlin 2.x default hierarchy template. No custom configuration needed.**

In Kotlin 2.x, the default hierarchy template is applied automatically when you declare KMP targets. For the target set `jvm + android + iosArm64 + iosX64 + iosSimulatorArm64`, the generated source set hierarchy is:

```
commonMain / commonTest
    ├── jvmMain / jvmTest
    ├── androidMain / androidUnitTest / androidInstrumentedTest
    └── nativeMain
            └── appleMain
                    └── iosMain / iosTest
                            ├── iosArm64Main
                            ├── iosX64Main
                            └── iosSimulatorArm64Main
```

`iosMain` is the shared source set for all three iOS targets — this is exactly where iOS platform implementations live. There is no need for per-target source sets in `iosArm64Main` / `iosX64Main` / `iosSimulatorArm64Main` except for very rare cases (specific linker flags for hardware vs simulator, which we do not have).

The `kotlin { applyDefaultHierarchyTemplate() }` call is redundant in Kotlin 2.x — the template is applied automatically. The convention plugin should NOT call it explicitly to avoid confusion.

**What `iosMain` is used for:**
- iOS platform-api implementations (Keychain, UNUserNotificationCenter, etc.)
- `NativeSqliteDriver` instantiation in `:shared:persistence-sqldelight`
- iOS-specific zlib decompression via `platform.zlib` (in `:shared:protocol-discord`)

**`jvmMain` vs `desktopMain`:** There is no `desktopMain` source set in this hierarchy. Desktop-specific code lives in `jvmMain`. Since Android also has `androidMain`, there is no ambiguity: `jvmMain` = desktop JVM only.

**Alternative rejected — custom hierarchy:** Custom hierarchies are needed when targets do not fit the standard Apple/JVM/Android groupings (e.g., adding `linuxX64`, `mingwX64`). We have no such targets in Phase 1–3. Adding them speculatively would be premature.

---

### Q4 — Navigation library

**Decision: Decompose 3.x — formally recorded in ADR-0005.**

**Rationale — why three-pane adaptive layout drives this decision:**

The adaptive-layouts.md mandates a three-pane Discord-style layout (guild rail | channel list | messages) that collapses to two-pane (Medium) and three-screen stack (Compact). This is not a simple linear navigation stack — it is a **multi-pane back-stack** where each pane has independent navigation history.

| Criterion | Decompose | Voyager | Compose Navigation (Jetpack) |
|---|---|---|---|
| KMP production readiness | ✅ Desktop + Android + iOS production | ✅ KMP but Desktop is secondary focus | ⚠️ KMP added recently, Desktop gaps |
| Three-pane support | ✅ `ChildPanels` API maps directly | ❌ Single-stack only | ❌ Single-stack only |
| State restoration (Android process death) | ✅ `instanceKeeper` + `StateKeeper` | ⚠️ Manual in KMP | ✅ Native on Android, N/A desktop |
| Back stack per pane | ✅ Per-pane `ComponentContext` | ❌ Global stack | ❌ Global stack |
| Deep links (discord:// URLs) | ✅ `deepLinks` parameter | ⚠️ Manual | ✅ Native on Android |
| iOS back gesture | ✅ Compose swipe back via `ChildStack` | ⚠️ Partial | ⚠️ Limited |

The `ChildPanels` component in Decompose 3.x (part of the `decompose` library, not `compose-material3-adaptive`) represents the three-pane layout as:
- `SINGLE` — Compact (one active pane at a time, back stack)
- `DUAL` — Medium (two panes visible)
- `TRIPLE` — Expanded (all three panes)

This maps exactly to the window size class breakpoints in adaptive-layouts.md. Implementing the same behavior with Voyager or Compose Navigation would require a custom navigation coordinator of comparable complexity — at which point we have reinvented a worse Decompose.

**Integration:**
- Decompose `ComponentContext` is the owner of each screen's `CoroutineScope` — this aligns with ADR-0004 (`ViewModelScope` is disposed when navigating away from the screen).
- `instanceKeeper` replaces ViewModel factory on Android and desktop: each Decompose component IS the ViewModel equivalent. ViewModels reside in `:shared:compose-ui` (presentation layer), not in `:shared:repositories` (data layer).
- Koin provides dependencies to Decompose components via constructor injection (manual DI in component `init`).

**Alternative rejected — Voyager:** Simpler API, but `ChildPanels` equivalent does not exist. Three-pane layout would require manual state management that duplicates Decompose's core value.

**Alternative rejected — Compose Navigation:** Primarily Android-origin. Multi-pane support in KMP is incomplete as of 2026. No `ChildPanels` equivalent.

**Alternative rejected — Custom navigation:** Only valid if no library covers the need. Decompose covers it fully.

**This decision is formally recorded in ADR-0005** (`docs/01_architecture/adr/0005-decompose-navigation.md`).

---

### Q5 — DI approach

**Decision: Koin 4.x**

**Rationale:**

CLAUDE.md prohibits global scope and global event bus. DI is orthogonal — it provides object graphs, not shared mutable state. Koin 4.x is KMP-native, DSL-based, and supports scoped instances that align with ADR-0004's scope hierarchy:

| ADR-0004 scope | Koin mapping |
|---|---|
| `ApplicationScope` | Koin `single { }` in root module |
| `SessionScope` | Koin `scope<DiscordSession> { scoped { } }` |
| `GatewayScope` / `RepositoryScope` | Injected into session scope |
| `ViewModelScope` | Koin `factory { }` or Decompose `instanceKeeper` |

The session scope is opened on login and closed on logout — this maps directly to `KoinScope.close()` which disposes all `scoped` instances. Repository and gateway dependencies are automatically cleaned up.

**Why NOT manual constructor injection:**

With the current module graph (10 shared modules, each with 2-5 injectable types), a manual "wiring file" in `:desktop:app` requires creating ~30 objects in the correct order with explicit dependencies. This is maintainable for 3-5 components (the usual threshold) but becomes a single-file bottleneck for 30+. Koin keeps each module's dependencies declared at the module level, not centralized in one massive wiring function.

**Why NOT kotlin-inject:**

kotlin-inject uses KSP annotation processing, which adds per-platform KSP configuration (JVM + Android + iOS) to every module that declares injections. Build configuration complexity increases significantly. For a project this size, compile-time DI safety is not worth the overhead. Koin's runtime resolution errors appear immediately on first test run, not silently in production.

**Why NOT Kodein:**

Less active maintenance trajectory than Koin. Koin 4.x added explicit KMP improvements with Compose integration. Either would work; Koin has a larger community.

---

### Q6 — Image loading

**Decision: Coil 3.x with `coil-network-ktor3` on all platforms.**

ADR-0003 specifies Coil explicitly. This decision confirms and specifies the networking backend.

Coil 3.x provides two network backends:
- `coil-network-okhttp` — for JVM/Android (OkHttp)
- `coil-network-ktor3` — for KMP (Ktor HttpClient)

**Chosen: `coil-network-ktor3` everywhere** (single dependency, Ktor already present in `:shared:protocol-discord`).

Coil `ImageLoader` configuration per platform:
- **Memory cache:** 25% heap, capped at 64 MB (matches cache-policy.md)
- **Disk cache:** 200 MB in `PlatformPaths.cacheDir / "images"` (matches cache-policy.md `cache.images.maxBytes`)
- **Ktor HttpClient:** a dedicated instance separate from the Discord API client (Coil needs no auth headers, different timeouts: 30 s connect / 60 s read)

The `ImageLoader` singleton is provided via Koin `single { }` in the application scope. It is NOT the Discord API `HttpClient` — they are separate instances to avoid cross-contamination of request headers and interceptors.

**Alternative rejected — Kamel:** Smaller community, less actively maintained than Coil 3.x. Coil 3.x has Compose Multiplatform `AsyncImage` component as a first-class API.

**Alternative rejected — Compose Multiplatform built-in image loader:** No disk cache, no LRU eviction, no CDN URL fetching with proper redirects. Does not satisfy ADR-0003 disk cache requirement.

---

### Q7 — WebSocket engine per platform

**Decision:**

| Platform | Engine | Reasoning |
|---|---|---|
| Desktop (JVM) | `ktor-client-cio` | Pure Kotlin coroutines, no native deps, avoids OkHttp on desktop (smaller binary) |
| Android | `ktor-client-okhttp` | OkHttp is the de-facto Android HTTP standard, well-tested on Android HTTP stack |
| iOS | `ktor-client-darwin` | Only option; backed by `NSURLSession`, WebSocket supported since iOS 13 (our min is 14.0) |

WebSocket support validation:
- CIO engine: `ktor-client-websockets` plugin adds WebSocket support over CIO's TCP layer ✅
- OkHttp engine: WebSocket via OkHttp's `WebSocket` API, surfaced through Ktor abstraction ✅
- Darwin engine: `NSURLSessionWebSocketTask` (iOS 13+), wrapped by Ktor's Darwin engine ✅

**Zlib-stream decompression** (discord-protocol.md requires `compress=zlib-stream`):
- JVM/Android: `java.util.zip.Inflater` — built-in, no dependency
- iOS: `platform.zlib.inflateInit` via Kotlin/Native CInterop — available in `iosMain`, no extra dependency

The single `Inflater` instance per gateway connection lives in `:shared:protocol-discord`'s `GatewayConnection` class. The `ZlibInflater` interface uses the **interface + impl pattern** (NOT expect/actual). The choice is deliberate: an `interface` in `commonMain` with named implementations per source set avoids the strict constructor-signature constraint of `expect class` and produces cleaner Koin-injectable types.

Pattern applied consistently:
- `commonMain`: `interface ZlibInflater` — declares the inflation contract
- `jvmMain`: `class JvmZlibInflater : ZlibInflater` — uses `java.util.zip.Inflater`
- `androidMain`: `class AndroidZlibInflater : ZlibInflater` — uses `java.util.zip.Inflater` (same JVM runtime)
- `iosMain`: `class IosZlibInflater : ZlibInflater` — uses `platform.zlib` CInterop

**Ktor HttpClient factory pattern:**

The `:shared:protocol-discord` module uses **expect/actual** for the HTTP client factory (appropriate here: same function signature, platform-specific engine choice):
- `expect fun createHttpClient(): HttpClient` in `commonMain`
- `actual fun createHttpClient(): HttpClient` in `jvmMain` (uses CIO)
- `actual fun createHttpClient(): HttpClient` in `androidMain` (uses OkHttp)
- `actual fun createHttpClient(): HttpClient` in `iosMain` (uses Darwin)

This factory is called once per session by Koin's `scoped { }` binding.

**Risk:** Darwin WebSocket stability — see Risk Register §8.

---

### Q8 — JSON serialization without ignoreUnknownKeys

**Decision: Two `Json` instances — one lenient (production), one strict (tests). `ignoreUnknownKeys = true` scoped exclusively to the production Discord DTO `Json` instance in `:shared:protocol-discord`. All other `Json` instances use strict mode. All test fixtures use the strict instance.**

**Justification for the exception:**

`feedback-no-ignoreunknownkeys` is correctly applied to internal schemas under our control: SQLite JSON columns, settings TOML, local state. We must not silently drop fields we defined ourselves — that indicates a mapper or versioning bug.

Discord's API is an external third-party service that adds new fields continuously without notice and without versioning the responses. The alternative approaches are all untenable:

| Alternative | Problem |
|---|---|
| Hand-code every Discord field including future ones | Impossible — Discord adds ~10–20 new fields per month |
| `@JsonClassDiscriminator` fallback variant | Handles discriminated union evolution, not unknown field evolution |
| `val extras: JsonObject` catch-all field per DTO | Requires custom `KSerializer` per class, ~50 DTOs = unmaintainable |
| Deserialize to `JsonObject` then map manually | Defeats the purpose of kotlinx.serialization type safety |
| Crash in production when Discord adds a field | Unacceptable for a daily-driver client |

**Implementation — two Json instances:**

In `:shared:protocol-discord`, one file (`DiscordJson.kt`) provides:

```
// Production instance — Discord external API exception (see ADR-0006)
internal val DiscordJson = Json {
    ignoreUnknownKeys = true          // Discord external API: new fields added without notice
    isLenient = false                 // strict value parsing
    coerceInputValues = false         // no silent null coercion
    encodeDefaults = false            // compact serialization
}
```

A second file (`DiscordJsonStrict.kt`) in the **`commonTest`** source set provides:

```
// Test-only strict instance — used by all mapper tests and fixture deserialization
// When Discord adds a field and the test fixture contains it, CI fails → engineer
// makes an explicit decision: add to DTO or document as intentionally ignored.
internal val DiscordJsonStrict = Json {
    ignoreUnknownKeys = false         // strict: unknown fields fail immediately in tests
    isLenient = false
    coerceInputValues = false
    encodeDefaults = false
}
```

**Test fixture rule:** All mapper unit tests and fixture-based deserialization use `DiscordJsonStrict`. When Discord adds a new field and a test fixture includes it, CI fails. The engineer then decides: add the field to the DTO (and mapper), or document the field as intentionally unmapped. No silent schema drift.

**Production code rule:** `DiscordJson` is `internal` to `:shared:protocol-discord`. No other module may use it. Every other `Json` instance in the project (settings serialization, SQLite JSON columns, non-Discord fixtures) must NOT set `ignoreUnknownKeys`.

The architectural decision is documented in [ADR-0006](../../01_architecture/adr/0006-discord-json-leniency-exception.md).

**Invariant:** The `:shared:protocol-discord` module boundary is the firewall. Discord DTO objects never escape into other modules (see CLAUDE.md rule 3: "Discord DTOs must not leak into the UI"). Mappers in `protocol-discord` convert DTOs to domain objects at the module boundary. Once converted, the domain objects have no unknown-field concerns.

---

### Q9 — SQLDelight driver per target

**Decision: SQLDelight 2.1.x**

| Target | Source set | Driver class | Additional dependency |
|---|---|---|---|
| JVM (desktop) | `jvmMain` | `JdbcSqliteDriver` | `org.xerial:sqlite-jdbc:3.47.0` |
| Android | `androidMain` | `AndroidSqliteDriver` | None (uses Android system SQLite) |
| iOS | `iosMain` | `NativeSqliteDriver` | None (uses iOS system SQLite) |

**Kotlin 2.x compatibility:** SQLDelight 2.x migrated away from KAPT to its own codegen (`.sq` → generated Kotlin files). It is fully compatible with Kotlin 2.x and the K2 compiler. The SQLDelight Gradle plugin 2.x explicitly declares compatibility with Kotlin 2.x.

**Driver wiring pattern — interface + impl (NOT expect/actual):**

`DriverFactory` uses the **interface + impl** pattern. The rationale: each platform implementation requires different constructor parameters (`Path` on JVM, `Context` on Android, no-arg on iOS), making `expect class` inappropriate — `expect class` requires the same constructor signature across all actuals. An `interface` with named per-platform implementations is clean and Koin-injectable.

- `commonMain`: `interface DriverFactory { fun createDriver(): SqlDriver }` — the contract
- `jvmMain`: `class JvmDriverFactory(private val path: Path) : DriverFactory` — uses `JdbcSqliteDriver`
- `androidMain`: `class AndroidDriverFactory(private val context: Context) : DriverFactory` — uses `AndroidSqliteDriver`
- `iosMain`: `class IosDriverFactory : DriverFactory` — uses `NativeSqliteDriver`

`PlatformPaths.databaseFile()` is injected into `JvmDriverFactory` via Koin in the desktop app's DI module. The database is initialized with all SQLite PRAGMAs from persistence-schema.md (WAL, foreign keys, mmap, etc.).

**WAL mode on iOS:** `NativeSqliteDriver` supports WAL mode via pragma. iOS app sandbox allows WAL files alongside the main `.db`. No special handling needed.

**SQLDelight schema placement:** `.sq` files live in `:shared:persistence-api`'s `commonMain/sqldelight/` directory. The SQLDelight Gradle plugin is applied to `:shared:persistence-api`, and `:shared:persistence-sqldelight` depends on it to access the generated `Database` class.

---

### Q10 — Logging stack

**Decision: Kermit 2.x**

| Criterion | Kermit | Napier | kotlin-logging + SLF4J |
|---|---|---|---|
| KMP: Desktop + Android + iOS | ✅ All three | ✅ All three | ❌ JVM only |
| File writer (crash logs) | ✅ `FileLogWriter` available | ❌ Manual | ✅ Logback appender |
| Structured tags | ✅ First-class `Tag` support | ✅ | ✅ SLF4J MDC |
| Token redaction | Custom `LogWriter` wrapper | Custom `Antilog` wrapper | Custom appender |
| Active maintenance | ✅ Touchlab, active | ⚠️ Slowing | N/A (JVM only) |

**Token redaction:**

A `RedactingLogWriter` wraps the platform `LogWriter` and replaces any string matching the Discord token pattern (starts with `M`, `N`, or `O`, base64 encoded 24+ chars) with `[REDACTED]`. This `RedactingLogWriter` is applied as the outermost `LogWriter` in the Kermit `Logger` chain.

**File logging:**

Kermit's `FileLogWriter` (available in `kermit-io` artifact) writes to `PlatformPaths.crashDir / "puklic.log"` with rotation at 2 MB (keep last 3 files). This satisfies the crash reporting requirement from phases.md.

**Log levels:**

| Level | When |
|---|---|
| `Verbose` | Gateway frame content (DEBUG builds only) |
| `Debug` | Connection state changes, cache hits/misses |
| `Info` | Session start/stop, guild/channel switches |
| `Warn` | Recoverable errors (rate limit hit, reconnect) |
| `Error` | Unrecoverable errors (token invalid, crash) |

Production builds: `Warning` level and above only.

---

### Q11 — Test stack

**Decision: kotlin.test (base) + Kotest 5.9.1 (assertions + property) + kotlinx.coroutines-test**

| Layer | Library | Applied to |
|---|---|---|
| Assertions (all platforms) | `kotlin.test` + `kotest-assertions-core` | All modules |
| Property-based tests | `kotest-property` (KMP-compatible) | `:shared:chat-parser` primarily |
| Coroutine testing | `kotlinx.coroutines.test` (`runTest`, `TestScope`) | All modules with coroutines |
| JVM runner | JUnit 5 (`junit-jupiter`) | Desktop CI runs |
| Android runner | `kotlin.test-junit` via AGP test runner | Android CI |
| iOS runner | kotlin.test native runner (built-in) | iOS CI |

**rationale for Kotest over pure kotlin.test:**

richtext-ast.md explicitly requires property-based tests for the parser (`Kotest property: parse(render(parse(x))) == parse(x)`). Kotest's `kotest-property` module is the only mature KMP property-based testing library. `kotest-assertions-core` provides richer matchers (shouldBe, shouldContain, shouldThrow) with better failure messages than kotlin.test's minimal `assertEquals`.

**rationale for NOT using full Kotest test engine on iOS:**

Kotest's test engine (as distinct from its assertion and property modules) has limited iOS support. We use `kotlin.test`'s native runner on iOS with Kotest assertions as a companion library — this combination is known-stable.

**Coroutines test version:** must match `kotlinx.coroutines` version exactly. Both pinned in `libs.versions.toml` as a version pair (see §6).

**Coverage:** Kover (JVM + Android). Target: 70% overall, 90% for `:shared:chat-parser`. iOS coverage via Kover is not available — use code review as the iOS test quality gate.

---

### Q12 — Detekt + ktlint setup

**Decision: Root-level config files + applied via `puklic.detekt` convention plugin to all modules. Compose-specific rules via `io.nlopez.compose.rules:detekt`.**

**Detekt:**
- Single `detekt.yml` at repo root
- Applied via the `puklic.detekt` convention plugin (which all other convention plugins include)
- Per-module override via `detekt { config.from(files("$rootDir/detekt.yml", "detekt-override.yml")) }` — only if genuinely needed for that module's context (e.g., `:shared:protocol-discord` might need relaxed `MagicNumber` rules for Discord opcode constants)
- Compose rules: `io.nlopez.compose.rules:detekt:0.4.22` — enforces: no mutable state in composable parameters, `Composable` naming PascalCase, `modifier` parameter required on layout composables, no side effects outside `LaunchedEffect`/`SideEffect`

**ktlint:**
- Single `.editorconfig` at repo root
- Applied via `jlleitschuh/gradle-ktlint` plugin in the `puklic.detekt` convention plugin (name is misleading — the convention plugin handles both)
- Version: ktlint 1.3.x
- Compose rules: `io.nlopez.compose.rules:ktlint:0.4.22`
- 2-space indent (per build.md), max line length 120

**Pre-commit hook:** `./gradlew ktlintCheck detekt` as documented in build.md. The hook is set up via Gradle task in the root `build.gradle.kts` that installs `.git/hooks/pre-commit` on `./gradlew tasks`.

**Formatter:** ktlint `--format` mode (NOT ktfmt). Reason: ktfmt and ktlint conflict on some formatting decisions and require separate config. A single tool is less friction.

---

## 5. Concrete File Inventory

Every Gradle-related file that must exist after initial project setup. Source files (`*.kt`) are scaffolding — they are created with the minimum content required to compile, not with business logic.

### Root level

```
settings.gradle.kts                         — project name "puklic", module includes, pluginManagement
build.gradle.kts                            — root: allprojects repositories, ktlint root, Kover merge
gradle/libs.versions.toml                   — version catalog: ALL library versions + aliases
gradle.properties                           — JVM args, build performance flags, KMP target toggles
gradlew                                     — Gradle wrapper shell script (auto-generated)
gradlew.bat                                 — Gradle wrapper Windows (auto-generated)
gradle/wrapper/gradle-wrapper.properties    — wrapper URL for Gradle 8.12
gradle/wrapper/gradle-wrapper.jar           — binary (auto-generated)
.gitignore                                  — .gradle/, build/, .kotlin/, *.iml, local.properties
detekt.yml                                  — detekt rule configuration (root)
.editorconfig                               — ktlint + IDE formatting rules (2-space indent)
```

### build-logic/

```
build-logic/settings.gradle.kts             — include this as standalone project, access libs.versions.toml
build-logic/build.gradle.kts               — dependencies: kotlin-gradle-plugin, agp, compose-mp plugin
build-logic/src/main/kotlin/
    puklic.kmp-library.gradle.kts          — KMP lib convention: targets (JVM+Android+iOS), JVM 21 toolchain, test deps, Kover
    puklic.compose-library.gradle.kts      — extends kmp-library, adds Compose MP plugin + Coil + Decompose
    puklic.ios-library.gradle.kts          — iOS-only convention: iosArm64/iosX64/iosSimulatorArm64 targets only; NO JVM toolchain; NO AGP
    puklic.jvm-library.gradle.kts          — JVM-only lib: toolchain 21, kotlin jvm plugin, test deps
    puklic.android-library.gradle.kts      — Android lib: minSdk 26, targetSdk 35, compileSdk 35
    puklic.android-app.gradle.kts          — Android app: same as android-library + applicationId + signing
    puklic.detekt.gradle.kts               — detekt + ktlint applied to ALL modules
```

### shared/ids/

```
shared/ids/build.gradle.kts                 — applies puklic.kmp-library; deps: kotlinx-datetime
shared/ids/src/commonMain/kotlin/           — UserId, GuildId, ChannelId, MessageId, RoleId, EmojiId, AttachmentId
shared/ids/src/commonTest/kotlin/           — SnowflakeTimestampTest
```

### shared/domain/

```
shared/domain/build.gradle.kts              — applies puklic.kmp-library; deps: ids, kotlinx-datetime, kotlinx-serialization
shared/domain/src/commonMain/kotlin/        — ChatMessage, Guild, Channel, UserSummary, Attachment, MessageEmbed, RichTextDocument, etc.
shared/domain/src/commonTest/kotlin/        — equality tests for domain types
```

### shared/platform-api/

```
shared/platform-api/build.gradle.kts       — applies puklic.kmp-library; deps: coroutines
shared/platform-api/src/commonMain/kotlin/ — interfaces: SecureStorage, NotificationService, TrayService, PlatformPaths, PlatformOpen, PlatformClipboard, PlatformAutoStart, PlatformPresence; sealed PlatformException hierarchy
shared/platform-api/src/commonTest/kotlin/ — FakeSecureStorage, FakeNotificationService (test doubles)
```

### shared/chat-parser/

```
shared/chat-parser/build.gradle.kts        — applies puklic.kmp-library; deps: domain, ids
shared/chat-parser/src/commonMain/kotlin/  — parseRichText(), Lexer, BlockParser, InlineParser
shared/chat-parser/src/commonTest/kotlin/  — golden file tests, property tests (Kotest)
shared/chat-parser/src/commonTest/resources/
    fixtures/                              — *.input.txt + *.expected.json parser golden files
```

### shared/protocol-discord/

```
shared/protocol-discord/build.gradle.kts   — applies puklic.kmp-library; deps: domain, ids, Ktor-client, kotlinx-serialization, coroutines
shared/protocol-discord/src/commonMain/kotlin/
    DiscordJson.kt                          — internal production Json instance: ignoreUnknownKeys=true (Discord exception, see ADR-0006)
    ZlibInflater.kt                         — interface ZlibInflater (interface+impl pattern, NOT expect/actual)
    dto/                                    — all Discord DTO data classes
    mapper/                                 — mapper extension functions (DiscordXxxDto.toDomain())
    rest/                                   — DiscordRestClient (rate limiter, endpoints)
    gateway/                                — GatewayConnection, GatewayEvent sealed hierarchy, Heartbeat
    Capabilities.kt                         — CAPABILITIES_VERSION constant (currently 16381); see note below
    HttpClientFactory.kt                    — expect fun createHttpClient(): HttpClient (expect/actual for Ktor engine selection)
shared/protocol-discord/src/jvmMain/kotlin/
    JvmZlibInflater.kt                      — class JvmZlibInflater : ZlibInflater using java.util.zip.Inflater
    HttpClientFactoryJvm.kt                 — actual fun createHttpClient() with CIO engine
shared/protocol-discord/src/androidMain/kotlin/
    AndroidZlibInflater.kt                  — class AndroidZlibInflater : ZlibInflater using java.util.zip.Inflater
    HttpClientFactoryAndroid.kt             — actual fun createHttpClient() with OkHttp engine
shared/protocol-discord/src/iosMain/kotlin/
    IosZlibInflater.kt                      — class IosZlibInflater : ZlibInflater using platform.zlib CInterop
    HttpClientFactoryIos.kt                 — actual fun createHttpClient() with Darwin engine
shared/protocol-discord/src/commonTest/kotlin/
    DiscordJsonStrict.kt                    — internal val DiscordJsonStrict = Json { ignoreUnknownKeys = false }; used by ALL mapper tests
    mapper/                                 — mapper tests using DiscordJsonStrict
    gateway/                                — gateway state machine tests
```

**Note on `Capabilities.kt`:** The file exposes `const val CAPABILITIES_VERSION = 16381` with a doc-comment referencing `docs/02_domain/discord-protocol.md` and the procedure for updating this value (see §9). The `GatewayConnection` `READY` event handler logs a `Warn`-level message if the response shape indicates an unexpected capabilities configuration (e.g., `READY_SUPPLEMENTAL` absent, guild count zero when guilds are expected). This is the signal that `CAPABILITIES_VERSION` may need updating. The update procedure **will be documented** in `docs/02_domain/discord-protocol.md` per §9 obligations (Phase 1 code-freeze gate).

### shared/persistence-api/

```
shared/persistence-api/build.gradle.kts    — applies puklic.kmp-library; SQLDelight plugin; deps: domain, ids, coroutines
shared/persistence-api/src/commonMain/sqldelight/dev/puklic/db/
    account.sq                              — account table + queries
    guild.sq                                — guild table + queries
    channel.sq                              — channel table + queries
    user.sq                                 — user table + queries
    message.sq                              — message table + queries (LIMIT, pagination)
    attachment_cache_index.sq               — disk cache index
    custom_emoji.sq                         — custom emoji cache
    read_state.sq                           — last-read per channel
    local_draft.sq                          — per-channel drafts
    outbound_message.sq                     — outbound queue
shared/persistence-api/src/commonMain/kotlin/
    PuklicDatabase.kt                       — extension functions on generated Database type
shared/persistence-api/src/commonTest/kotlin/ — SQL query tests with in-memory SQLite
```

### shared/persistence-sqldelight/

```
shared/persistence-sqldelight/build.gradle.kts — applies puklic.kmp-library; deps: persistence-api, platform-api, SQLDelight per-platform drivers
shared/persistence-sqldelight/src/commonMain/kotlin/
    DriverFactory.kt                        — interface DriverFactory { fun createDriver(): SqlDriver } (interface+impl pattern, NOT expect/actual)
shared/persistence-sqldelight/src/jvmMain/kotlin/
    JvmDriverFactory.kt                     — class JvmDriverFactory(private val path: Path) : DriverFactory; uses JdbcSqliteDriver + sqlite-jdbc; applies WAL/PRAGMA
shared/persistence-sqldelight/src/androidMain/kotlin/
    AndroidDriverFactory.kt                 — class AndroidDriverFactory(private val context: Context) : DriverFactory; uses AndroidSqliteDriver
shared/persistence-sqldelight/src/iosMain/kotlin/
    IosDriverFactory.kt                     — class IosDriverFactory : DriverFactory; uses NativeSqliteDriver
shared/persistence-sqldelight/src/commonTest/kotlin/ — driver creation smoke test per platform
```

### shared/repositories/

```
shared/repositories/build.gradle.kts       — applies puklic.kmp-library; deps: protocol-discord, persistence-api, chat-parser, coroutines
shared/repositories/src/commonMain/kotlin/
    MessageRepository.kt                    — hot/warm cache + SQLite + API fallback; Flow<List<ChatMessage>>
    GuildRepository.kt                      — StateFlow<Map<GuildId, Guild>>
    ChannelRepository.kt                    — StateFlow<Map<ChannelId, Channel>>
    UserRepository.kt                       — StateFlow<Map<UserId, UserSummary>>
    EmojiRepository.kt                      — custom emoji, LruCache
    OutboundMessageQueue.kt                 — send queue with retry
    SessionCache.kt                         — warm cache (LinkedHashMap LRU, 5 channels × 50 msgs)
    MentionResolver.kt                      — interface + impl, backed by UserRepository
    EmojiResolver.kt                        — interface + impl, backed by EmojiRepository
shared/repositories/src/commonTest/kotlin/  — repository unit tests with fake persistence + fake protocol
```

### shared/session/

```
shared/session/build.gradle.kts            — applies puklic.kmp-library; deps: protocol-discord, repositories, platform-api, Koin
shared/session/src/commonMain/kotlin/
    DiscordSession.kt                       — top-level session: login, connect, logout state machine
    SessionScope.kt                         — SupervisorJob owner, cleanup on logout
    SessionEvent.kt                         — sealed interface: Ready, Disconnected, TokenInvalid, Error
shared/session/src/commonTest/kotlin/       — session lifecycle tests with mock gateway
```

### shared/compose-ui/

```
shared/compose-ui/build.gradle.kts         — applies puklic.compose-library; deps: domain, repositories, session, platform-api, Decompose, Coil, Koin, material3-adaptive
shared/compose-ui/src/commonMain/kotlin/
    PuklicTheme.kt                          — MaterialTheme wrapper + PuklicColors + PuklicSpacing CompositionLocals
    navigation/
        RootComponent.kt                    — Decompose root component, ChildPanels setup
        NavigationState.kt                  — sealed: GuildSelected, ChannelSelected, etc.
    screens/
        LoginScreen.kt
        GuildListScreen.kt
        ChannelListScreen.kt
        MessageListScreen.kt
        SettingsScreen.kt
    viewmodels/
        MessageListViewModel.kt             — StateFlow<MessageListState>; Decompose ComponentContext lifecycle owner
        GuildListViewModel.kt
        ChannelListViewModel.kt
        SettingsViewModel.kt
    components/
        PuklicAvatar.kt
        RichTextView.kt
        MessageRow.kt
        MessageList.kt
        Composer.kt
        ChannelListItem.kt
        CategoryHeader.kt
        GuildRailItem.kt
        ConnectionStatusBanner.kt
        EmptyState.kt
        LoadingSkeleton.kt
        CommandPalette.kt
    scaffold/
        ExpandedScaffold.kt
        MediumScaffold.kt
        CompactScaffold.kt
        SettingsOverlay.kt
shared/compose-ui/src/commonTest/kotlin/    — Compose UI tests with mock state
```

### desktop/app/

```
desktop/app/build.gradle.kts               — applies puklic.jvm-library; deps: compose-ui, session, platform-linux/macos/windows (runtime), Koin, Compose Desktop
desktop/app/src/jvmMain/kotlin/
    Main.kt                                 — main() entry point, applicationScope, Koin start
    AppModule.kt                            — Koin module: wires platform-api actual impls to interfaces
    PuklicWindow.kt                         — Compose Desktop Window { PuklicApp() }
```

### desktop/platform-linux/

```
desktop/platform-linux/build.gradle.kts    — applies puklic.jvm-library; deps: platform-api, JNA, dbus-java
desktop/platform-linux/src/jvmMain/kotlin/
    LinuxSecureStorage.kt                   — libsecret via JNA
    LinuxNotificationService.kt             — D-Bus org.freedesktop.Notifications
    LinuxTrayService.kt                     — StatusNotifierItem via D-Bus / libayatana
    LinuxPlatformPaths.kt                   — XDG_DATA_HOME, XDG_CACHE_HOME, XDG_CONFIG_HOME
    LinuxPlatformOpen.kt                    — xdg-open
    LinuxPlatformAutoStart.kt               — ~/.config/autostart/
    LinuxPlatformClipboard.kt               — AWT clipboard
    LinuxPlatformPresence.kt                — idle/DND (stub for Phase 1)
```

### desktop/platform-macos/ (stub)

```
desktop/platform-macos/build.gradle.kts    — applies puklic.jvm-library; deps: platform-api
desktop/platform-macos/src/jvmMain/kotlin/
    MacosPlatformPaths.kt                   — ~/Library/Application Support / ~/Library/Caches
    MacosSecureStorage.kt                   — stub (NotImplementedError for Phase 1)
    [other stubs...]
```

### desktop/platform-windows/ (stub)

```
desktop/platform-windows/build.gradle.kts  — applies puklic.jvm-library; deps: platform-api
desktop/platform-windows/src/jvmMain/kotlin/
    WindowsPlatformPaths.kt                 — %APPDATA%, %LOCALAPPDATA%
    [other stubs...]
```

### android/app/ (stub)

```
android/app/build.gradle.kts               — applies puklic.android-app; deps: compose-ui, session, android-platform, Koin
android/app/src/main/AndroidManifest.xml   — minimal: INTERNET, ACCESS_NETWORK_STATE permissions, MainActivity
android/app/src/main/kotlin/
    PuklicApp.kt                            — Application subclass, Koin init (stub)
    MainActivity.kt                         — setContent { /* TODO Phase 2 */ }
android/app/src/main/res/                  — minimal resources (icon placeholder)
```

### android/platform/ (stub)

```
android/platform/build.gradle.kts          — applies puklic.android-library; deps: platform-api
android/platform/src/main/kotlin/
    AndroidSecureStorage.kt                 — stub (NotImplementedError body, TODO comment)
    AndroidNotificationService.kt           — stub
    AndroidPlatformPaths.kt                 — uses context.filesDir / cacheDir (actual impl, not stub)
    [other stubs...]
```

### ios/app/ (stub)

```
ios/app/build.gradle.kts                   — applies puklic.ios-library; deps: compose-ui, session, ios-platform, Koin
ios/app/src/iosMain/kotlin/
    Main.kt                                 — minimal Kotlin/Native entry point (stub)
```

### ios/platform/ (stub)

```
ios/platform/build.gradle.kts              — applies puklic.ios-library; deps: platform-api
ios/platform/src/iosMain/kotlin/
    IosSecureStorage.kt                     — stub (NotImplementedError, "Phase 2")
    IosNotificationService.kt              — stub
    IosPlatformPaths.kt                    — uses NSFileManager applicationSupportDirectory (actual impl)
    [other stubs...]
```

### tools/parser-fixtures-gen/

```
tools/parser-fixtures-gen/build.gradle.kts — applies puklic.jvm-library; deps: chat-parser
tools/parser-fixtures-gen/src/jvmMain/kotlin/
    Main.kt                                — CLI: reads raw Discord messages, outputs fixture JSON
```

---

## 6. Library Version Constraints

All versions pinned in `gradle/libs.versions.toml`. Verify each version against Maven Central / JetBrains releases before creating `libs.versions.toml` — versions below were current at 2026-05-21 and the ecosystem moves quickly. For Kotlin, Compose Multiplatform, and AGP, check JetBrains and Google release notes for the latest stable before project init.

### Core toolchain

| Library | Version | Notes |
|---|---|---|
| Kotlin | **2.1.21** | Latest Kotlin 2.1.x stable |
| Compose Multiplatform | **1.8.0** | JetBrains; includes Compose iOS stable track |
| Android Gradle Plugin | **8.7.2** | matches targetSdk 35 |
| Gradle | **8.12** | via wrapper |
| JVM toolchain target | **21** | Fixed; Compose Desktop requires ≥17 |
| Android minSdk | **26** | Oreo |
| Android targetSdk | **35** | Latest stable |
| iOS deployment target | **14.0** | set in KMP targets config |

### Kotlin ecosystem

| Library | Version | Notes |
|---|---|---|
| `kotlinx.coroutines` | **1.10.1** | Must match `coroutines-test` version exactly |
| `kotlinx.serialization` | **1.8.0** | JSON + CBOR |
| `kotlinx.datetime` | **0.6.2** | KMP dates |

### Networking

| Library | Version | Notes |
|---|---|---|
| `ktor-client-core` | **3.1.3** | Ktor 3.x KMP |
| `ktor-client-cio` | **3.1.3** | Desktop engine |
| `ktor-client-okhttp` | **3.1.3** | Android engine |
| `ktor-client-darwin` | **3.1.3** | iOS engine |
| `ktor-client-websockets` | **3.1.3** | All platforms |
| `ktor-client-content-negotiation` | **3.1.3** | For REST |
| `ktor-serialization-kotlinx-json` | **3.1.3** | |

### Persistence

| Library | Version | Notes |
|---|---|---|
| `sqldelight-runtime` | **2.1.0** | KMP runtime |
| `sqldelight-coroutines-extensions` | **2.1.0** | Flow queries |
| `sqldelight-jdbc-driver` | **2.1.0** | JVM driver |
| `sqldelight-android-driver` | **2.1.0** | Android driver |
| `sqldelight-native-driver` | **2.1.0** | iOS driver |
| `sqlite-jdbc` (xerial) | **3.47.0** | JVM SQLite binary |

SQLDelight Gradle plugin version matches runtime version.

### UI

| Library | Version | Notes |
|---|---|---|
| `compose-material3` | bundled with CMP 1.8.0 | Material 3 |
| `compose-material3-adaptive` | **1.1.0** | Adaptive scaffold utilities: ThreePaneScaffold, ListDetailPaneScaffold, adaptive breakpoints — does NOT provide ChildPanels (that is Decompose's API) |
| `decompose` | **3.3.0** | Navigation; ChildPanels multi-pane API; ComponentContext lifecycle |
| `decompose-compose` | **3.3.0** | Compose integration |
| `coil-core` | **3.1.0** | Image loading KMP |
| `coil-compose` | **3.1.0** | Compose AsyncImage |
| `coil-network-ktor3` | **3.1.0** | Ktor network backend |

### DI

| Library | Version | Notes |
|---|---|---|
| `koin-core` | **4.1.0** | KMP DI |
| `koin-compose` | **4.1.0** | Compose integration |
| `koin-android` | **4.1.0** | Android |

### Logging

| Library | Version | Notes |
|---|---|---|
| `kermit` | **2.0.5** | KMP logging |
| `kermit-io` | **2.0.5** | File log writer |

### Testing

| Library | Version | Notes |
|---|---|---|
| `kotlin-test` | bundled with Kotlin 2.1.21 | Base test framework |
| `kotest-assertions-core` | **5.9.1** | Rich assertions (KMP) |
| `kotest-property` | **5.9.1** | Property-based (KMP) |
| `kotest-runner-junit5` | **5.9.1** | JVM runner |
| `kotlinx-coroutines-test` | **1.10.1** | Must = coroutines version |
| `koin-test` | **4.1.0** | KoinTestRule |

### Build tooling

| Library | Version | Notes |
|---|---|---|
| `detekt` | **1.23.8** | Static analysis |
| `detekt-compose-rules` (io.nlopez) | **0.4.22** | Compose-specific rules |
| `ktlint` | **1.3.1** | Formatter |
| `ktlint-compose-rules` (io.nlopez) | **0.4.22** | Compose ktlint rules |
| `kover` | **0.9.1** | Coverage (JVM/Android) |
| `gradle-versions-plugin` | **0.51.0** | `dependencyUpdates` task |

### Platform-specific (desktop only)

| Library | Version | Notes |
|---|---|---|
| `jna` | **5.16.0** | Linux native: libsecret, D-Bus |
| `jna-platform` | **5.16.0** | JNA platform types |
| `dbus-java-core` | **4.3.1** | D-Bus notifications (Linux) |

---

## 7. Phase 1 Implementation Order

The sequence below is the critical path for getting a running desktop app. Each step produces a compilable state; do NOT proceed to the next step until the current one passes `./gradlew build`.

| Step | Action | Success criterion |
|---|---|---|
| 1 | Create `build-logic/` with all 7 convention plugins (no-op bodies) + `settings.gradle.kts` + root `build.gradle.kts` + `gradle/libs.versions.toml` + `gradle.properties` | `./gradlew help` succeeds |
| 2 | Add all module `build.gradle.kts` to `settings.gradle.kts`. Create empty `src/` dirs. | `./gradlew projects` lists all modules |
| 3 | Implement `:shared:ids` — value classes only | `:shared:ids:test` green |
| 4 | Implement `:shared:domain` — data classes + sealed hierarchies | `:shared:domain:test` green |
| 5 | Implement `:shared:platform-api` — interfaces + test doubles (FakeSecureStorage, etc.) | `:shared:platform-api:test` green |
| 6 | Implement `:shared:persistence-api` — `.sq` files + SQLDelight codegen | `:shared:persistence-api:generateSqlDelightInterface` succeeds |
| 7 | Implement `:shared:persistence-sqldelight` — DriverFactory per platform (IosDriverFactory/AndroidDriverFactory stubs, real JvmDriverFactory) | JVM driver creates DB + opens WAL |
| 8 | Implement `:shared:chat-parser` — parser core, golden tests fail → implement → green | `./gradlew :shared:chat-parser:test` 100% golden fixtures green |
| 9 | Implement `:shared:protocol-discord` — DTOs + DiscordJson + DiscordJsonStrict + mappers (no live network yet) | Mapper unit tests using DiscordJsonStrict green |
| 10 | Implement `:shared:repositories` — MessageRepository with fake protocol + fake persistence | Repository unit tests green; StateFlow emission verified |
| 11 | Implement `:shared:session` — gateway state machine with fake WebSocket | Session lifecycle tests: connect → ready → disconnect → resume |
| 12 | Implement `:desktop:platform-linux` — PlatformPaths (real), SecureStorage (libsecret), NotificationService (D-Bus) | Manual smoke test: token store/retrieve, notification appears |
| 13 | Implement `:shared:compose-ui` — PuklicTheme + skeletons of all screen/component/viewmodel composables | `:desktop:app:run` shows empty window with correct theme |
| 14 | Implement `:desktop:app` — main(), Koin wiring, real Compose Desktop window | App starts, shows LoginScreen |
| 15 | Wire protocol-discord to real gateway (live Discord connection) | Connects, receives READY, guilds appear in UI |
| 16 | Wire MessageRepository to live data + SQLite | Message list loads, persists, survives restart |
| 17 | Implement iOS and Android stub module bodies (NotImplementedError) | `./gradlew :ios:platform:compileKotlinIosArm64` compiles without error |
| 18 | `:tools:parser-fixtures-gen` CLI | Generates valid fixture files |
| 19 | Final Phase 1 check: `./gradlew build detekt ktlintCheck` | All green |

**Test-first note:** Steps 3–4 (ids, domain) and Step 8 (chat-parser) follow TDD strictly: write failing tests first, then implement production code. Steps 5 (platform-api — interface-only module, no production code to drive) and Step 9 (protocol DTOs) use contract-first design: define interfaces and fixtures, then write tests against the implementations. Steps 10–11 (repositories, session) follow TDD.

---

## 8. Risk Register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | **Compose iOS stability** — text input, scroll inertia, accessibility rough edges documented in ios.md | High | Medium (Phase 2/3 only) | Use CMP stable track only. Accept UIKit interop for text input if needed. Re-evaluate at Phase 2 start. |
| R2 | **Ktor Darwin WebSocket stability** — NSURLSessionWebSocketTask is iOS 13+ API. Frame reassembly, ping/pong timing unverified for Discord's keep-alive protocol | Medium | High (iOS gateway broken) | Test Darwin WS with real Discord gateway in Phase 2 before shipping. Have a backup plan: Starscream via K/N interop. |
| R3 | **SQLDelight 2.x + Kotlin 2.x K2 compiler** — SQLDelight migrated codegen away from KAPT but K2 ABI changes affect some codegen. The combination has been tested but edge cases exist | Low | Medium | Pin both to tested version pair. Run `./gradlew :shared:persistence-api:generateSqlDelightInterface` as first CI step. |
| R4 | **Desktop binary size** — target < 80 MB (build.md). JRE 21 stripped + Compose Desktop + all deps may exceed this | Medium | Low (nice-to-have target) | Use `jlink` to create minimal JRE image with only required modules. Profile with `jpackage --type image` + measure. |
| R5 | **Decompose 3.x ChildPanels API** — adaptive three-pane is a newer Decompose feature; API may have rough edges on iOS and Desktop | Medium | Medium | Pin Decompose version. Test three-pane collapse on all window sizes in Phase 1. ChildPanels is the core navigation abstraction — do not assume stability until tested. |
| R6 | **Coil 3 + coil-network-ktor3 on iOS** — Coil's Ktor network layer on iOS relies on Darwin engine; CDN redirect handling and TLS have not been officially benchmarked for Discord CDN | Medium | Low (images don't load) | Test avatar + attachment loading on iOS simulator in Phase 2. Fallback: `coil-network-okhttp` on JVM/Android only, no iOS images in Phase 1. |
| R7 | **Discord schema additions break mapper tests** — `DiscordJsonStrict` in tests will fail when Discord adds a new field present in a test fixture | Low | Low (CI failure, not production) | Intended behavior: CI failure prompts explicit DTO update decision. Update DTOs and/or fixtures when new fields appear. Do NOT switch test fixtures to the lenient `DiscordJson` instance. |
| R8 | **Zlib-stream iOS** — `platform.zlib` K/N interop for continuous zlib stream is less tested than the JVM Inflater path | Medium | High (iOS gateway unusable without decompression) | Test zlib decompression with 50 MB of Discord gateway frames in a unit test targeting `iosSimulatorArm64`. Alternative: skip `compress=zlib-stream` on iOS (connect without `&compress=zlib-stream`), add extra bandwidth. |
| R9 | **Koin 4.x + Decompose 3.x integration** — Koin's `koin-compose` scope integration assumes a ViewModel lifecycle that differs from Decompose's `instanceKeeper` | Low | Medium | Use constructor injection into Decompose components (no ViewModelScope/getViewModel in Compose). Koin provides dependencies; Decompose manages lifecycle. These are compatible patterns. |
| R10 | **`gradle.properties` memory** — large KMP projects with multiple concurrent Kotlin compilations can exceed 2 GB heap | Low | Medium (CI OOM kills) | Set `org.gradle.jvmargs=-Xmx4g` in `gradle.properties`. Use `--parallel` with daemon. |

---

## 9. What This Spec Does NOT Include

The following are explicitly outside the scope of this Gradle setup specification. They require separate design sessions or are resolved at implementation time:

- **Business logic implementations** — `GatewayConnection.connect()`, `MessageRepository` ring buffer algorithm, rate limiter implementation, RichText parser internals. This spec defines which files exist; what goes inside them is for the implementing engineer.

- **Compose UI layout pixel-level decisions** — component sizes, exact color hex values, animation curves. See `docs/04_ui/design-system.md`.

- **CI/CD pipeline definition** — GitHub Actions matrix, macOS runner for iOS builds, signing key management. See `docs/06_ops/build.md` (to be expanded).

- ~~**ADR-0006 (ignoreUnknownKeys exception)** — this spec recommends creating it; content is one paragraph based on Q8. Left to the engineer.~~ **Resolved 2026-05-21:** see [ADR-0006](../../01_architecture/adr/0006-discord-json-leniency-exception.md).

- **Discord `capabilities` integer value — update procedure:**
  The `CAPABILITIES_VERSION` constant in `Capabilities.kt` is currently `16381` (per discord-protocol.md, May 2026). Discord changes this value as features ship. The update procedure must be documented in `docs/02_domain/discord-protocol.md` before Phase 1 code freeze. Minimum required documentation: (a) how to observe the current value from the official client's gateway traffic, (b) which `READY` / `READY_SUPPLEMENTAL` fields to check to detect a mismatch (the `Warn`-level log added to the READY handler serves as the runtime signal), (c) the Git commit message template to use when updating the constant so it's traceable. This documentation task is in-scope for Phase 1 but is not a Gradle setup task.

- **Voice, screenshare, media** — Phases 3/4. No modules for these exist in Phase 1.

- **macOS/Windows platform actual implementations** — stubs only in Phase 1. Full implementations in Phase 2.

- **Release, signing, AppImage packaging** — post-Phase 1 concern. `jpackage` task is already mentioned in build.md.

- **Proguard/R8 configuration** — Android release build optimization. Phase 2.

- **iOS Xcode project** — generated by Kotlin Gradle plugin via `embedAndSignAppleFrameworkForXcode`. The `.xcodeproj` is not a Gradle file and is not in scope here.

---

## Appendix: gradle.properties baseline

The following performance and feature flags must be set from day 1:

```
# Build performance
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=512m -XX:+HeapDumpOnOutOfMemoryError
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.parallel=true
org.gradle.daemon=true

# KMP
kotlin.mpp.enableCInteropCommonization=true
kotlin.mpp.androidSourceSetLayoutVersion=2

# Android
android.useAndroidX=true
android.nonFinalResIds=false

# Compose
org.jetbrains.compose.experimental.jscanvas.enabled=false
org.jetbrains.compose.experimental.ios.enabled=true
```

`kotlin.mpp.enableCInteropCommonization=true` is required for the `platform.zlib` CInterop in `iosMain` to be accessible from the `iosMain` shared source set (not only from per-architecture source sets).

---

*End of specification (r2). This document represents the complete Gradle + KMP setup blueprint for Puklic Phase 1. All 12 open questions from `module-map.md` and `build.md` are resolved above.*
