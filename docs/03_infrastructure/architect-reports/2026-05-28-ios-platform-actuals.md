# Slice 2b — iOS actuals in `:ios:platform`

Status: ARCHITECT REPORT (Step 2 + Step 3). User pre-approved Step 4 in the
Slice 2b handoff (`.claude-handoff-2026-05-28.md`). Issue: GitHub #28.

Date: 2026-05-28
Author: pipeline orchestrator (this session)

## 1. Goal

Replace the `NotImplementedError("iOS platform: Phase 2")` stubs in
`ios/platform/src/commonMain/kotlin/dev/puklic/platform/ios/IosPlatform.kt`
with conceptually-complete iOS implementations, using `kotlinx.cinterop`
against `platform.Foundation`, `platform.UIKit`, `platform.Security`,
`platform.UserNotifications`, and `platform.UniformTypeIdentifiers`.

No Swift bridging files. No CocoaPods. No `TODO`. No half-state.

## 2. Module conventions

`:shared:platform-api` uses plain Kotlin interfaces (not `expect/actual`).
`:ios:platform` provides concrete iOS classes that implement those
interfaces — same pattern as `:desktop:platform-linux` /
`:desktop:platform-macos`. The convention plugin `puklic.ios-library`
already declares iosArm64 / iosX64 / iosSimulatorArm64.

## 3. File layout

Split the single `IosPlatform.kt` into one file per service for SOLID and
diff-locality. All in package `dev.puklic.platform.ios`:

| File | Class | Backing API |
|---|---|---|
| `IosSecureStorage.kt` | `IosSecureStorage` | `SecItemAdd` / `SecItemCopyMatching` / `SecItemDelete` (`kSecClassGenericPassword`) |
| `IosPlatformPaths.kt` | `IosPlatformPaths` | `NSFileManager.URLForDirectory` |
| `IosPlatformOpen.kt` | `IosPlatformOpen` | `UIApplication.sharedApplication.openURL` |
| `IosPlatformClipboard.kt` | `IosPlatformClipboard` | `UIPasteboard.generalPasteboard` + `UTType(MIMEType:)` |
| `IosFilePicker.kt` | `IosFilePicker` | `UIDocumentPickerViewController` + delegate |
| `IosNotificationService.kt` | `IosNotificationService` | `UNUserNotificationCenter` |
| `IosPlatformAutoStart.kt` | `IosPlatformAutoStart` | `supported = false` (permanent OS fact) |
| `IosPlatformPresence.kt` | `IosPlatformPresence` | idle `StateFlow`s (permanent OS limitation) |
| `IosTrayService.kt` | `IosTrayService` | throws `PlatformUnavailable` (permanent OS fact) |

## 4. Implementation map

### 4.1 Keychain (`IosSecureStorage`)

`SecItemAdd` for write (replaces via delete-then-add on duplicate
`errSecDuplicateItem` = -25299). `SecItemCopyMatching` with
`kSecReturnData=true` for read. `SecItemDelete` for remove. `SecItemCopyMatching`
with `kSecReturnAttributes=true` + `kSecMatchLimitAll` for list (extract
`kSecAttrAccount`).

Service name = `puklic-client` (matches `:desktop:platform-macos`
constant for cross-platform key compatibility, even though stores are
separate).

Memory model: CF objects returned via outvar are bridged to Kotlin via
`CFBridgingRelease` (K/N: cast the `CFTypeRef` to `NSData?`/`NSDictionary?`
in `memScoped` block).

### 4.2 Paths (`IosPlatformPaths`)

```
dataDir   = NSFileManager.URLForDirectory(NSDocumentDirectory)
cacheDir  = NSFileManager.URLForDirectory(NSCachesDirectory)
configDir = NSFileManager.URLForDirectory(NSApplicationSupportDirectory)
crashDir  = dataDir + "crashes" (createDirectoryAtURL)
databaseFile() = dataDir + "puklic.db"
```

Directories are eagerly created with `createDirectoryAtURL:withIntermediateDirectories:`.

### 4.3 openURL (`IosPlatformOpen`)

`UIApplication.sharedApplication.openURL(url, options, completionHandler)`
(iOS 10+). All three methods (`openUrl`, `openFile`, `openInFolder`) route
through `UIApplication`. For files, build `NSURL.fileURLWithPath:`. For
folders, same (iOS Files app opens the folder URL).

Threading: `UIApplication` must be touched on main thread. Dispatch via
`dispatch_async(dispatch_get_main_queue())` and suspend with
`suspendCancellableCoroutine` waiting for the `completionHandler`.

### 4.4 Clipboard (`IosPlatformClipboard`)

```
setText  = UIPasteboard.generalPasteboard.setString(text)
getText  = UIPasteboard.generalPasteboard.string
setImage = UIPasteboard.generalPasteboard.setData(nsData, forPasteboardType: uti)
```

UTI resolution: `UTType.typeWithMIMEType(mime)?.identifier ?: "public.data"`
(UniformTypeIdentifiers framework, iOS 14+; minSdk = 14 in chat-only app).

### 4.5 FilePicker (`IosFilePicker`)

`UIDocumentPickerViewController(forOpeningContentTypes: listOf(UTType.item))`.
`allowsMultipleSelection = allowMultiple`. Presented on key window's root
view controller.

Delegate (`UIDocumentPickerDelegateProtocol`) lifecycle:
- Held as strong ref inside an `NSObject` subclass owning a
  `kotlinx.coroutines.CompletableDeferred<List<PickedFile>>`.
- `didPickDocumentsAtURLs:` reads each URL via
  `url.startAccessingSecurityScopedResource()` → `NSData(contentsOfURL:)`
  → `url.stopAccessingSecurityScopedResource()`. Builds `PickedFile`s
  with MIME from `UTType(filenameExtension:).preferredMIMEType`.
- `documentPickerWasCancelled:` completes with empty list.

If no root VC available, throws `PlatformUnavailable` — that's a real
runtime precondition (app not yet wired in Slice 3), NOT a half-state
stub. Slice 3 wires the host VC and `pick()` then works end-to-end.

### 4.6 Notifications (`IosNotificationService`)

`UNUserNotificationCenter.currentNotificationCenter`:
- `show` → build `UNMutableNotificationContent` (title, body) →
  `UNTimeIntervalNotificationTrigger` 0.1s (immediate) → `addNotificationRequest`
- `cancel` → `removePendingNotificationRequestsWithIdentifiers` +
  `removeDeliveredNotificationsWithIdentifiers`
- Identifier = `NotificationHandle.id` (caller-supplied UUID via
  `NSUUID.UUID().UUIDString` when generated internally)

Permissions: `requestAuthorizationWithOptions(.alert | .sound)` lazily on
first `show`. If denied, throws `PlatformDenied`.

`supported = NotificationCapabilities(actions = false, images = false, markup = false)`
for now — actions/images need attachment files and category registration,
which is a Slice 6+ enhancement. Setting these to `true` would be a lie
about current capability (HARD RULE #2 forbids).

### 4.7 AutoStart (`IosPlatformAutoStart`)

`supported = false`; the other methods throw `PlatformUnavailable`. iOS
sandbox prohibits launch-at-boot for third-party apps — this is a
permanent OS fact, not a stub. Same shape as the desktop `iOS has no
system tray` pattern.

### 4.8 Presence (`IosPlatformPresence`)

`systemAway` and `dndActive` return constant-false `StateFlow`s. iOS has
no public API to read system Focus / Do Not Disturb state for
third-party apps (the Focus Status API is for "share my focus" which
requires user opt-in per app and is voluntarily-published, not readable).
This is a permanent OS limitation — documented in KDoc, not a stub.

### 4.9 Tray (`IosTrayService`)

Throws `PlatformUnavailable("iOS has no system tray")` on every mutator
(same as existing Phase 1 stub — that one was already conceptually
correct). `clicks` is an empty `SharedFlow`.

## 5. Threading model

iOS APIs touched here split into:
- **Thread-safe**: Keychain (`SecItem*`), `NSFileManager` URL queries, `UNUserNotificationCenter`.
- **Main-thread-only**: `UIApplication`, `UIPasteboard`, `UIDocumentPickerViewController`.

Suspend functions that touch main-thread-only APIs use
`suspendCancellableCoroutine` + `dispatch_async(dispatch_get_main_queue())`
to marshal onto main, then resume the continuation on whatever thread
the callback returns on (Kotlin/Native default Default dispatcher will
unwind correctly).

Kotlin/Native interop runs in a single-threaded mode by default for the
new memory model (1.7+), so passing Kotlin closures across dispatch
boundaries is safe (no freeze required).

## 6. Critic pass (Step 3)

| Concern | Resolution |
|---|---|
| `SecItemCopyMatching` returns `CFTypeRef` via `outvar`. Memory mgmt? | Use `memScoped { alloc<CFTypeRefVar>() }`; cast result via `CFBridgingRelease(it)` to `NSData?` / `NSDictionary?` — ARC takes ownership, no manual `CFRelease` needed. |
| FilePicker delegate retention — Kotlin objects can be GC'd while UIKit holds weak ref. | Delegate strongly retains itself in a private `mutableSet` until the deferred completes; remove on completion. Standard pattern for K/N + delegate-based APIs. |
| `UIApplication.sharedApplication` is `null` in unit-test process (no UIApplicationMain). | Tests for `IosPlatformOpen` / `IosPlatformClipboard` / `IosFilePicker` are skipped — they require a host app. Keychain + Paths tests work in the simulator process without UIApplication. |
| `setImage(bytes, mimeType)` — UTType is iOS 14+. Min target? | iOS 14 is our floor (chat-only app, Compose iOS requires 14 anyway). Documented. |
| Notification permission flow — synchronous request from `show()` could surprise UI. | Acceptable: `show()` is suspend, and the permission prompt is a one-time system dialog. If denied, throw `PlatformDenied` — caller handles (UI could surface "Enable notifications in Settings"). |
| `IosFilePicker` throws `PlatformUnavailable` when root VC missing — is that a stub? | No: it's a runtime precondition reflecting that the iOS app shell isn't constructed yet. Once Slice 3 wires `ComposeUIViewController` into the app's window, root VC is non-null and pick works. Throwing the typed exception is the correct conceptual answer. |
| Single-threaded vs multi-threaded K/N memory model — do we need `freeze()`? | Kotlin 1.7+ uses the new MM by default; no freeze required. Convention plugin doesn't override → we get new MM. |
| Dispatching `dispatch_async` from a suspend function — Kotlin coroutines main dispatcher exists in K/N. Why not use it? | `Dispatchers.Main` on K/N requires `kotlinx-coroutines-core` with the `Main` dispatcher available — it IS available since 1.7.x via `NsQueueDispatcher`. Use `withContext(Dispatchers.Main)` instead of raw GCD where possible. Cleaner. |

No findings require redesign. Proceed to implementation.

## 7. Test plan (Step 5 — tests-first)

`ios/platform/src/commonTest/kotlin/dev/puklic/platform/ios/`:

- `IosSecureStorageTest.kt` — put → get → list → remove → get-returns-null round-trip with a unique random key per test to isolate from any leftover keychain state. Runs in iosSimulatorArm64 / iosX64 test process (both have a working keychain).
- `IosPlatformPathsTest.kt` — `dataDir`, `cacheDir`, `configDir` resolve to non-empty paths; directories exist after construction; `databaseFile()` resolves under `dataDir`; `crashDir` resolves under `dataDir`.

UI-bound services (`IosFilePicker`, `IosPlatformOpen`, `IosPlatformClipboard`,
`IosNotificationService`) are not unit-testable without a host
`UIApplication` — they are exercised by integration / device tests in
Slice 4. Skipping them is correct conceptually (Slice 4 owns that test
plan), NOT a coverage gap.

## 8. Dependency changes

`ios/platform/build.gradle.kts`:
```
commonMain.dependencies {
    implementation(projects.shared.platformApi)
    implementation(libs.kotlinx.coroutines.core)   // new: for StateFlow / SharedFlow / Dispatchers.Main
}
```

No new third-party deps. All iOS APIs come from the K/N Apple platform
bindings shipped with the Kotlin compiler.

`verifyIosNoGplDeps` continues to pass — kotlinx-coroutines-core is
Apache-2.0.

## 9. Risk + rollback

- Risk: a Keychain entry under `puklic-client` persists between test runs
  on the simulator. Mitigation: tests use unique random keys + clean up
  in `finally`.
- Risk: `UTType(MIMEType:)` returns `null` for exotic MIME types.
  Mitigation: fall back to `"public.data"` UTI.
- Risk: `UIApplication.openURL` blocks if app is mid-launch. Mitigation:
  `completionHandler` is asynchronous; we wait for it via continuation.
- Rollback: revert the commit; the only artefact added is the new files
  + the dep line.

## 10. Done criteria (Step 11)

- `./gradlew :ios:platform:compileKotlinIosArm64 :ios:platform:compileKotlinIosX64 :ios:platform:compileKotlinIosSimulatorArm64` green.
- `./gradlew :ios:platform:iosSimulatorArm64Test` green (Keychain + Paths tests).
- `./gradlew :ios:app:verifyIosNoGplDeps` green.
- No `NotImplementedError`, no `TODO`, no Phase-2 placeholder strings in
  the new files.
- `docs/01_architecture/module-map.md` updated to reflect iOS module
  status (if it lists iOS as stub).
- Issue #28 closed in commit message.
