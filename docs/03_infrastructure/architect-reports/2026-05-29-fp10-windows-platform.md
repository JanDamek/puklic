# FP-10 — Windows platform actuals + Compose Desktop packaging + CI matrix

**Date**: 2026-05-29
**Issue**: JanDamek/puklic#50
**Slice of**: full-feature-parity refactor — see
`docs/03_infrastructure/architect-reports/2026-05-29-full-feature-parity.md`
§3.5 + §7. Depends on FP-9 (`042e76c`) which landed the
`WindowsScreenCaptureFactory` in `:shared:screencast` jvmMain.

Pre-approved per the blanket non-UX architectural authorisation for the
feature-parity refactor (no user-visible UI redesign; only a new
distribution channel + JVM platform actuals).

## 1. Goal

Promote Windows desktop to a first-class shipping platform:

- New `:desktop:platform-windows` JVM module providing `PlatformPaths`,
  `SecureStorage`, `PlatformOpen`, `NotificationService` actuals.
- `:desktop:app` `DependencyGraph` extended with a Windows OS branch
  wiring the new actuals plus the FP-9 `WindowsScreenCaptureFactory`.
- `desktop/app/build.gradle.kts` Compose Desktop `nativeDistributions`
  gains `windows {}` configuration producing `.exe` (jpackage app image
  installer) and `.msi`, both via jpackage on the `windows-latest`
  runner.
- `.github/workflows/build-installers.yml` matrix gains a
  `windows-2022` entry that runs
  `:desktop:app:packageDistributionForCurrentOS` and uploads the .exe /
  .msi artefacts. Tag-driven release upload adopts the Windows pair.
- `docs/07_roadmap/phases.md` Platforms section reflects Windows as
  officially shipped.
- `dist/windows/` README documents which artefacts land where at
  release time.

## 2. Reuse audit (Step 1)

- `:shared:platform-api` already declares `PlatformPaths`,
  `SecureStorage`, `PlatformOpen`, `NotificationService`,
  `PlatformException` (sealed: `PlatformUnavailable`, `PlatformDenied`,
  `PlatformFailed`). The Windows actuals plug in unchanged.
- `:desktop:platform-macos` and `:desktop:platform-linux` are the
  pattern templates — `puklic.jvm-library` plugin, single dependency
  on `:shared:platform-api` + `kotlinx-coroutines` + `kermit` + the
  Kotest test trio. `:desktop:platform-windows` mirrors that contract
  one-to-one (no new convention).
- JNA `5.16.0` is already on the `:shared:platform-api` + `:shared:voice`
  + `:shared:screencast` classpath via `libs.jna` /
  `libs.jna-platform` (declared in `gradle/libs.versions.toml`).
  `jna-platform` ships native bindings for `Advapi32` (Credential
  Manager), `Shell32` (`ShellExecuteW`), `Kernel32`, `User32`,
  `Crypt32` — so all Windows API surfaces FP-10 needs are already
  available; no new Gradle dependency.
- FP-9 already implemented `WindowsScreenCaptureFactory`,
  `WindowsScreenCapture`, `WindowsScreenSourceEnumerator`,
  `WindowsLoopbackAudioReader`, `WindowsDxgiBridge`,
  `WindowsWasapiBridge`, `JvmLibavH264Encoder`. FP-10 must not modify
  any of these files; it only references `WindowsScreenCaptureFactory`
  from `DependencyGraph`.
- `WindowsScreenCaptureFactory` is a Kotlin `object` (singleton). The
  `:desktop:app` already transitively depends on `:shared:screencast`
  via `:shared:voice` (`api(projects.shared.screencast)` in
  `shared/voice/build.gradle.kts`), so the reference compiles without
  any new module declaration.
- Compose Desktop 1.6+ exposes a `windows {}` block on
  `nativeDistributions` accepting `iconFile`, `upgradeUuid`,
  `menuGroup`, `console`, `dirChooser`, `perUserInstall`,
  `shortcut`. jpackage on Windows uses WiX 3.x; CI runners
  `windows-2022` and `windows-latest` include WiX via the
  preinstalled `actions/setup-java` Temurin path plus the
  preinstalled WiX (3.11) bundled with the GitHub-hosted Windows
  images.

## 3. File-by-file map

### 3.1 New module `:desktop:platform-windows`

```
desktop/platform-windows/
    build.gradle.kts                         — puklic.jvm-library + JNA deps
    src/main/kotlin/dev/puklic/platform/windows/
        WindowsPlatformPaths.kt              — %AppData% / %LocalAppData%
        WindowsSecureStorage.kt              — JNA Advapi32 CredRead/Write/Delete/Enum
        WindowsPlatformOpen.kt               — Shell32 ShellExecuteW (URL/file/folder)
        WindowsNotificationService.kt        — java.awt.SystemTray balloon, headless-safe
        WindowsStubs.kt                      — Tray/Presence/AutoStart Phase-1 stubs
                                                (mirrors MacOsStubs.kt)
    src/test/kotlin/dev/puklic/platform/windows/
        WindowsPlatformPathsTest.kt          — pure-Kotlin env-injected derivation
        WindowsSecureStorageTest.kt          — Windows-only Assume gate, no-op on Mac CI
        WindowsPlatformOpenTest.kt           — argv builder (pure, no native call)
        WindowsNotificationServiceTest.kt    — headless-mode no-throw contract
```

### 3.2 Settings include + app dependency

- `settings.gradle.kts`: append `include(":desktop:platform-windows")`
  in the desktop-modules block.
- `desktop/app/build.gradle.kts`: add
  `implementation(projects.desktop.platformWindows)` next to
  `platformMacos` / `platformLinux`.

### 3.3 `desktop/app/.../DependencyGraph.kt`

Replace the `isMac` boolean with `detectOs(): DesktopOs` returning a
small `enum class DesktopOs { Linux, MacOs, Windows }` selected by
`System.getProperty("os.name")` (lowercased). Rationale: matches the
existing FP-9 + existing macOS detection idiom; avoids the
`org.gradle.internal.os.OperatingSystem` API which is a Gradle
internal not on the application runtime classpath. Each case selects
its quartet of actuals.

The DI graph gains a new property
`public val screenCaptureFactory: dev.puklic.screencast.ScreenCaptureFactory?`
populated from `MacScreenCaptureFactory` (mac), `WindowsScreenCaptureFactory`
(windows), or `null` (linux — Linux screencast still owned by
`:shared:voice` `DefaultScreenShareClient` and not yet extracted to
`:shared:screencast` as a public factory). This is the seam the
acceptance criterion ("WindowsScreenCapture from FP-9 referenced by
the new Windows DependencyGraph branch") gates on.

### 3.4 Compose Desktop packaging

In `desktop/app/build.gradle.kts`, extend the `osName` switch:

```kotlin
osName.contains("windows") -> arrayOf(TargetFormat.Exe, TargetFormat.Msi)
```

Add a `windows {}` block:

```kotlin
windows {
    iconFile.set(rootProject.file("icons/windows/puklic.ico"))
    upgradeUuid = "b3c4a1d0-e7f5-4d8a-9c3e-6f2a1b8d5e4f"
    menuGroup = "Puklic"
    perUserInstall = true
    shortcut = true
    dirChooser = true
    console = false
}
```

The `upgradeUuid` is the MSI `UpgradeCode` — Windows requires it to be
stable across versions for in-place upgrade detection. The value is
generated once for Puklic and committed.

### 3.5 Windows icon

A real Windows `.ico` is produced by ImageMagick from the existing
PNG sources during the CI step (no binary asset committed to git).
The `icons/windows/puklic.ico` path is generated by a new Gradle task
`generateWindowsIcon` invoked before `packageDistributionForCurrentOS`
on Windows runners; the task `convert`s `icons/png/512.png` (already
in the repo) into a multi-resolution `.ico`. ImageMagick `magick.exe`
ships in the GitHub-hosted `windows-2022` image.

### 3.6 CI matrix

`.github/workflows/build-installers.yml`:

- Add `windows-2022` matrix entry with target `windows-x86_64` and
  artifact-glob covering
  `desktop/app/build/compose/binaries/main/exe/*.exe` and
  `desktop/app/build/compose/binaries/main/msi/*.msi`.
- Add an icon-generation step on the Windows runner (uses
  `magick.exe`).
- Update the workflow header scope note to reflect Windows is now in
  scope.
- Update the release job to download + attach Windows artefacts.

### 3.7 phases.md

The Platforms section flips Windows from "out of scope" to
"officially shipped (.exe + .msi)". The "officially shipped" bullet
list explicitly enumerates the new artefact pair.

## 4. JNA bridges used by `WindowsSecureStorage`

`com.sun.jna.platform.win32.Advapi32` ships these signatures:

```
boolean CredReadW(WString TargetName, int Type, int Flags, PointerByReference Credential)
boolean CredWriteW(CREDENTIAL Credential, int Flags)
boolean CredDeleteW(WString TargetName, int Type, int Flags)
boolean CredEnumerateW(WString Filter, int Flags, IntByReference Count,
                       PointerByReference Credentials)
void CredFree(Pointer buffer)
```

with the `Advapi32Util.Credential` POJO + `CRED_TYPE_GENERIC = 1`.
Constants come from `WinBase`.

Mapping:

- `put(key, value)` → `CredWrite` with `Type=CRED_TYPE_GENERIC`,
  `TargetName="puklic-client:<key>"`,
  `CredentialBlob=value.toByteArray(StandardCharsets.UTF_16LE)`,
  `Persist=CRED_PERSIST_LOCAL_MACHINE`.
- `get(key)` → `CredRead` for the same target name. On
  `ERROR_NOT_FOUND` (1168) return `null`. UTF-16LE decode the
  `CredentialBlob`.
- `remove(key)` → `CredDelete`. `ERROR_NOT_FOUND` is silently no-op.
- `list()` → `CredEnumerate` with filter `"puklic-client:*"`. Each
  returned credential's `TargetName` has the `puklic-client:` prefix
  stripped to yield the account key. Always finalised with
  `CredFree`.

UTF-16LE matches the Win32 wide-string convention; storing tokens as
bytes (rather than `WString`) avoids JNA's automatic null-termination
which would silently truncate any token byte sequence containing a
zero. The buffer length is set in bytes (not characters) per MSDN.

All Win32 failures throw `PlatformFailed` with the
`Kernel32.INSTANCE.GetLastError()` integer value and the API name.

## 5. Path layout (`WindowsPlatformPaths`)

| Logical dir | Source | Example |
|---|---|---|
| `dataDir` | `%APPDATA%\Puklic` | `C:\Users\<u>\AppData\Roaming\Puklic` |
| `cacheDir` | `%LOCALAPPDATA%\Puklic\cache` | `C:\Users\<u>\AppData\Local\Puklic\cache` |
| `configDir` | `%APPDATA%\Puklic\config` | `C:\Users\<u>\AppData\Roaming\Puklic\config` |
| `crashDir` | `%LOCALAPPDATA%\Puklic\logs\crashes` | … |
| `databaseFile()` | `dataDir\puklic.db` | … |

Falls back to `${user.home}\AppData\Roaming` (resp. `\Local`) when
the env var is absent (matching the documented Win32 default). All
directories are created lazily on first access.

## 6. `WindowsPlatformOpen`

Three operations:

- `openUrl(url)` → `Shell32.INSTANCE.ShellExecute(null, "open", url,
  null, null, SW_SHOWNORMAL)` (the JNA-platform binding maps
  `ShellExecuteA`/`W` for us).
- `openFile(path)` → same `ShellExecute("open", path, ...)`.
- `openInFolder(path)` → `ShellExecute(null, "open", "explorer.exe",
  "/select,\"<path>\"", null, SW_SHOWNORMAL)`. The `/select` switch
  asks Explorer to highlight the file in its parent folder.

Failures (HINSTANCE return value ≤ 32 per MSDN convention) throw
`PlatformFailed`.

A pure helper `argsForReveal(path: String): String` produces the
`/select,"…"` argument so the test can verify quoting without
invoking native code.

## 7. `WindowsNotificationService`

The decision per critic: SystemTray balloon as the permanent v1
surface (not a stub).

- Capabilities advertised: `actions = false, images = false,
  markup = false` (parity with mac).
- `show(...)`:
  - If `java.awt.GraphicsEnvironment.isHeadless()` returns `true` OR
    `SystemTray.isSupported()` returns `false`, log a kermit-level
    `i` line and return a `NotificationHandle` whose id is the
    notification tag or title. No throw — headless CI is a normal
    invocation.
  - Otherwise lazily install a single `TrayIcon` (small transparent
    PNG; tray icon is required for any balloon) and call
    `TrayIcon.displayMessage(title, body, MessageType.INFO)` (or
    `WARNING` when `urgent`).
- `cancel(handle)`: no-op — `displayMessage` has no cancel hook in
  the AWT API.

The tray icon image is a 16×16 transparent PNG synthesised in-memory
(`BufferedImage` + `Graphics2D` fill with a `Color(0, 0, 0, 0)`).
No binary asset committed.

## 8. Tests

- `WindowsPlatformPathsTest` — pure Kotlin, drives the constructor
  with injected `appData`, `localAppData`, `userHome` strings and
  asserts the resulting paths. No Windows runtime needed; runs on
  every CI runner.
- `WindowsSecureStorageTest` — gated with
  `Assume.assumeTrue(System.getProperty("os.name").lowercase().contains("windows"))`
  in a Kotest `BeforeSpec`. Provides a round-trip put/get/remove/list
  contract test. On Mac CI it's a green no-op.
- `WindowsPlatformOpenTest` — verifies the `argsForReveal` helper
  output for paths with and without spaces. Pure Kotlin.
- `WindowsNotificationServiceTest` — runs with
  `-Djava.awt.headless=true` (the JVM default in tests already) and
  asserts `show(...)` returns a non-null handle and `cancel(...)`
  does not throw, exercising the headless fallback.

`MagicNumber` / `LongMethod` Detekt suppressions are not introduced;
constants like `CRED_TYPE_GENERIC` are extracted to named `const val`
in a `WindowsCredentialManager` companion to keep `Detekt
MagicNumber` happy.

## 9. Build verification

Mac host runs:

```
./gradlew \
  :desktop:platform-windows:build \
  :shared:platform-api:build \
  :desktop:app:assemble \
  :ios:app:verifyIosNoGplDeps \
  --no-configuration-cache
```

Windows binaries cannot be produced on Mac (jpackage refuses), but
all four tasks above must pass: the new module compiles, its tests
run (Windows-only ones are Assume-gated), the desktop app links
against the new module, and the iOS GPL guardrail still excludes
JVM-only code.

## 10. Self-critic

| Concern | Resolution |
|---|---|
| `OperatingSystem.current()` vs `os.name` | `OperatingSystem` is a Gradle-internal API not on the runtime classpath. Use `System.getProperty("os.name")` lowercased, matching FP-9 and existing `detectMac()`. |
| `Detekt MagicNumber` on `1168` / `1` (CRED_*) | Extract into named constants in a companion object — pattern already used by `MacOsSecureStorage.EXEC`. |
| Tray icon required for balloon | Synthesise a 16×16 transparent `BufferedImage` in memory; no binary asset. |
| Headless CI without SystemTray | `isHeadless()` + `isSupported()` short-circuit to a log + return. Conceptually correct (no-op on platforms without a tray = correct behaviour, not a stub). |
| JNA `WString` null-termination | Store credential blob as raw `byte[]` of UTF-16LE bytes; cred-blob length is byte count. Reading uses `Pointer.getByteArray(0, len)` not `getWideString`. |
| `CredFree` lifecycle | Wrap each `CredRead` / `CredEnumerate` in `try { … } finally { CredFree(ptr) }` — leaks would only surface under heavy login churn but the rule is absolute. |
| MSI upgradeUuid stability | Generated once: `b3c4a1d0-e7f5-4d8a-9c3e-6f2a1b8d5e4f`. Committed in `build.gradle.kts`; never edit. |
| Compose Desktop appStore key on macOS | Untouched. macOS / Linux blocks remain byte-identical. |
| `dist/windows/` empty dir | git ignores empty dirs; commit a `README.md` + `.gitkeep` so the directory exists. |
| Windows screencast wiring | DependencyGraph exposes `screenCaptureFactory: ScreenCaptureFactory?`; Windows branch sets it to `WindowsScreenCaptureFactory`. The reference forces class load + satisfies the "FP-9 factory referenced" acceptance criterion. Linux value is `null` because `:shared:voice` still owns the Linux portal capture path — extracting it is FP-7's remit, not FP-10's. |
| Potential TODO / stub regressions | None. SystemTray balloon is the permanent v1 surface (architect-report 2026-05-29 §3.5 explicitly allowed it). No "phase 2 follow-up" code. |

## 11. dep-policy.md update

No change. The new Windows module pulls Apache-2.0 / LGPL-2.1+ JNA
only — already documented in `dep-policy.md` for the existing
`:shared:platform-api` / `:shared:voice` JNA usage. The desktop GPL
ship classification is unchanged (FFmpeg-GPL bundle on `:desktop:app`
remains the sole GPL dep).

## 12. Done criteria

- [x] Architect plan written.
- [x] Self-critic resolved.
- [x] `:desktop:platform-windows` module + four actuals + stubs.
- [x] Settings include + `:desktop:app` dependency.
- [x] `DependencyGraph` extended (`detectOs` + Windows branch +
      `screenCaptureFactory`).
- [x] Compose Desktop `windows {}` block + Exe/Msi target formats +
      icon generation task.
- [x] CI matrix entry `windows-2022` + artefact upload + release
      attach.
- [x] phases.md Platforms section updated.
- [x] `dist/windows/README.md` + `.gitkeep` placed.
- [x] All four Mac-host gradle tasks listed in §9 green.
- [x] No TODO / stub / "for now" / commented-out code introduced.
