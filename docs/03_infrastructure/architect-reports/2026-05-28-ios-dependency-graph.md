# Slice 3.5 — `IosDependencyGraph`

**Date**: 2026-05-28
**Closes**: #32
**Depends on**: #30 (Slice 3, commit `1628db1`), #31 (commit `819a4eb`)

## Goal

Provide the iOS analogue of `desktop/app/.../DependencyGraph.kt` so the Xcode app (Slice 4) can construct a complete Discord session graph with a single Swift call.

## Deliverables

1. **Real `IosDriverFactory`** — replaces the `NotImplementedError` placeholder. Uses `app.cash.sqldelight:native-driver` (`NativeSqliteDriver`) with the same PRAGMA set as `JvmDriverFactory` (`WAL`, `synchronous=NORMAL`, `foreign_keys=ON`, `temp_store=MEMORY`, `mmap_size=256 MB`, `cache_size=8 MB`) and the additive `user_preferences` `CREATE TABLE IF NOT EXISTS`.
2. **`KtorGatewayTransport` moved** from `desktop/app/.../KtorGatewayTransport.kt` (package `dev.puklic.desktop`) to `shared/protocol-discord/src/commonMain/.../gateway/KtorGatewayTransport.kt` (package `dev.puklic.protocol.discord.gateway`). The implementation was already KMP-clean (Ktor websockets); only the package and an import in desktop's `DependencyGraph.kt` change. Conceptually the right home: the Ktor-backed `GatewayTransport` is Discord-gateway-specific and reusable across all platforms with a Ktor engine.
3. **`IosDependencyGraph`** — top-level DI factory in `ios/app/src/commonMain/kotlin/dev/puklic/ios/IosDependencyGraph.kt`. Mirrors desktop's graph, with these iOS-specific substitutions:

   | Desktop | iOS |
   |---|---|
   | `MacOs/LinuxPlatformPaths` | `IosPlatformPaths` |
   | `MacOs/LinuxSecureStorage` | `IosSecureStorage` |
   | `MacOs/LinuxPlatformOpen` | `IosPlatformOpen` |
   | `MacOs/LinuxNotificationService` | `IosNotificationService` |
   | `JvmDriverFactory` | `IosDriverFactory` |
   | `HttpClient(CIO)` | `HttpClient(Darwin)` |
   | `Dispatchers.IO` | `Dispatchers.Default` (Native has no separate IO pool) |
   | `DefaultVoiceClient` (gated on `VoiceFeatureFlag.ENABLED`) | `NoOpVoiceClient` (always — voice excluded on App Store per §3.2) |
   | `UpdateChecker / UpdateCheckerScheduler` | not wired (desktop-only feature) |
   | `runAutoTest` opt-in | not wired |

4. **`IosDependencyGraph.puklicAppRootViewController(): UIViewController`** — convenience that delegates to the existing Slice 3 factory with the graph's pre-built `sessionManager`, `userPreferences`, resolvers and `platformOpen`. Swift sees one Kotlin object, calls `create()` + `puklicAppRootViewController()`, embeds the result.

5. **`:ios:app` linker fix** — add `linkerOpts("-lsqlite3")` to every `binaries.framework` block. SQLDelight `NativeSqliteDriver` pulls in `co.touchlab:sqliter` which links against the system `libsqlite3.dylib`. Xcode adds this automatically for app targets, but a standalone Kotlin/Native framework needs the explicit flag.

## Conceptual note — Dispatchers.IO

`kotlinx.coroutines.Dispatchers.IO` is JVM-only (it's marked `internal` in the Native source set). On Kotlin/Native there is no separate "blocking IO" thread pool: the default worker pool is the correct equivalent. The commonMain repository code uses an injected `CoroutineDispatcher`, so this is a wiring choice, not a code change to any module.

## License surface

- `:ios:app` adds `libs.ktor.client.darwin`, `libs.sqldelight.runtime`, `libs.sqldelight.native.driver` (transitively via `:shared:persistence-sqldelight`), `libs.decompose`, `compose.runtime/ui`, `libs.kermit`, `libs.kotlinx.{coroutines.core, datetime, serialization.json}`, `libs.ktor.client.{core, websockets, content-negotiation}` and `libs.ktor.serialization.kotlinx.json`. All Apache-2.0.
- `:shared:protocol-discord` is unchanged on the license side (Ktor websockets dep was already declared in commonMain). The iosMain Darwin engine dep was already present.
- `:shared:persistence-sqldelight` iosMain Darwin/native-driver dep is now applied via `sourceSets.matching { it.name == "iosMain" }.configureEach { … }` instead of `sourceSets.findByName("iosMain")?.apply { … }`. The first eagerly resolves once iOS targets exist; the second was being called before the source set was created and silently dropped the dep block.
- `verifyIosNoGplDeps` stays green — no GPL artifact appears in the resolved iOS graph.

## Verification

```
./gradlew \
  :ios:app:linkReleaseFrameworkIosArm64 \
  :ios:app:linkReleaseFrameworkIosX64 \
  :ios:app:linkReleaseFrameworkIosSimulatorArm64 \
  :ios:app:verifyIosNoGplDeps
```

All pass on 2026-05-28. The simulator framework was linked first (~12 min cold), confirming the SQLite linker flag fixes the `_sqlite3_*` undefined symbols.

## Caller contract (Slice 4)

Swift:

```swift
import PuklicShared

let graph = IosDependencyGraph.companion.create()
let vc = graph.puklicAppRootViewController()
window.rootViewController = vc
```

The graph owns the application coroutine scope, the SQLite database connection and the HTTP client. Disposal is at app termination (iOS app lifecycle ends the process).

## Out of scope

- Voice / screenshare (excluded by design — `:shared:voice` is a GPL-only JVM impl, not on the iOS graph)
- Push notifications (Slice 9 — APN `.p8` provisioning)
- Update checker (desktop-only feature; App Store handles iOS updates)
