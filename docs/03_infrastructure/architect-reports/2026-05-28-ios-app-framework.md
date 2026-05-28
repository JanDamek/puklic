# Slice 3 — `:ios:app` Compose iOS framework

**Date**: 2026-05-28
**Status**: implemented
**Closes**: #30
**Follow-up**: #32 (iOS DependencyGraph — Slice 3.5)

## Goal

Wire `:ios:app` to produce a Swift-callable `UIViewController` that hosts the existing Compose UI tree, so the Xcode app (Slice 4) can embed it.

## Deliverables

1. `:ios:app/build.gradle.kts` applies `org.jetbrains.compose` + `org.jetbrains.kotlin.plugin.compose`. Configures `binaries.framework { baseName = "PuklicShared"; isStatic = false }` for every iOS Kotlin/Native target (Arm64, X64, SimulatorArm64).
2. New entry point `ios/app/src/commonMain/kotlin/dev/puklic/ios/PuklicAppRootViewController.kt`:

   ```kotlin
   public fun puklicAppRootViewController(
       sessionManager: SessionManager,
       preferences: UserPreferencesRepository? = null,
       mentionResolver: MentionResolver = NoopMentionResolver,
       emojiResolver: EmojiResolver = NoopEmojiResolver,
       platformOpen: PlatformOpen? = null,
   ): UIViewController
   ```

   Constructs a Decompose `LifecycleRegistry` + `DefaultComponentContext`, builds the `RootComponent`, calls `lifecycle.resume()`, then returns `ComposeUIViewController { PuklicApp(root, …) }`.
3. New module deps in `:ios:app`: `:shared:platform-api`, `libs.decompose`, `compose.runtime`, `compose.ui` (in addition to existing `:ios:platform`, `:shared:compose-ui`, `:shared:session`, `libs.koin.core`).

## Scope split — why DI lives in Slice 3.5

The architect report `2026-05-28-apple-distribution.md` §3.1 lists the v1 App Store features (login, guild/channel/DM list, message list, SQLite cache, Keychain). Each requires concrete instances of `SessionManager`, repositories, Discord HTTP/gateway clients, etc. The desktop module wires these through `DependencyGraph.kt` (~200 lines) using the JVM stack (`JvmDriverFactory`, Ktor CIO, etc.).

The iOS equivalent (`IosDependencyGraph` — `NativeSqliteDriver`, Ktor Darwin engine, iOS keychain via the actuals from Slice 2b) is the same conceptual surface but a fresh implementation. Doing it in the same slice as the framework wiring would mix two distinct verification tasks (framework links vs DI graph works). They are split:

- **Slice 3** (this): framework links, factory exposes `UIViewController` taking a configured `SessionManager`. Compiles without depending on any iOS DI graph.
- **Slice 3.5** (#32): iOS DependencyGraph — produces a real `SessionManager` from a clean call site, so the framework factory has something to be fed in Slice 4.

This is not a temporary split — the factory's parameter set is the permanent contract; `IosDependencyGraph` is a separate concern (DI graph), and the desktop-side has the same split between `RootComponent` constructor and `DependencyGraph.kt`.

## Verification

```
./gradlew \
  :ios:app:linkReleaseFrameworkIosArm64 \
  :ios:app:linkReleaseFrameworkIosX64 \
  :ios:app:linkReleaseFrameworkIosSimulatorArm64 \
  :ios:app:verifyIosNoGplDeps
```

All five tasks pass on 2026-05-28 (commit pending push). `verifyIosNoGplDeps` confirms no GPL dependency was introduced.

## Next

- #32 — iOS DependencyGraph (Slice 3.5)
- Slice 4 — `iosApp/iosApp.xcodeproj` + Swift entry that constructs `IosDependencyGraph().puklicAppRootViewController()`
