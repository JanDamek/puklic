# iCloud Keychain sync for Discord token (Issue #74)

**Date:** 2026-06-02
**Issue:** [#74](https://github.com/JanDamek/puklic/issues/74)
**Slice:** ship in 1.2.5.

## Problem

The Discord token saved by the Mac App Store build of Puklic did not flow to
the iOS app — iOS users had to re-paste the token after every fresh install.
The root cause was that neither Apple-platform `SecureStorage` implementation
opted into iCloud Keychain replication:

- `IosSecureStorage` (`ios/platform/.../IosSecureStorage.kt`) called
  `SecItemAdd` without `kSecAttrSynchronizable`, so the row was local-only.
- `MacOsSecureStorage` (`desktop/platform-macos/.../MacOsSecureStorage.kt`)
  shelled out to `/usr/bin/security`. The `security` CLI has **no flag** to
  set `kSecAttrSynchronizable`, so even with the entitlement the CLI could
  not create a synced row.

iCloud Keychain replicates items between Apple devices signed into the same
Apple ID as long as both write the item with `kSecAttrSynchronizable = true`
and both apps share the same `(kSecAttrService, kSecAttrAccount)` tuple under
the user's iCloud Keychain access group.

## Design

### iOS

Minimal patch in place: every `SecItemAdd` query carries
`kSecAttrSynchronizable = true`; every `SecItemCopyMatching` /
`SecItemDelete` / `list()` query carries
`kSecAttrSynchronizable = kSecAttrSynchronizableAny` so both synced and any
pre-existing (pre-#74) local-only rows are matched on read / delete. The
service name stays `puklic-client` (unchanged) so the cross-platform key
namespace matches macOS.

### macOS Desktop / Mac App Store

CLI path retired. New JVM impl uses `Security.framework` `SecItem*` APIs via
JNA, mirroring the iOS code path:

- New JNA bindings:
  - `desktop/platform-macos/.../bridge/Security.kt` — `SecItemAdd`,
    `SecItemCopyMatching`, `SecItemDelete`, `SecItemUpdate` + the
    `kSecClass*` / `kSecAttr*` / `kSecValueData` / `kSecMatchLimit*` globals.
  - `desktop/platform-macos/.../bridge/CoreFoundation.kt` — small CF slice
    (`CFStringCreateWithBytes`, `CFDataCreate`, `CFDictionaryCreate`,
    `CFArrayGetCount`, `CFGetTypeID`, `kCFBooleanTrue`,
    `kCFTypeDictionary{Key,Value}CallBacks`).
  - `desktop/platform-macos/.../bridge/CfQueryBuilder.kt` — typed helpers
    `cfString` / `cfData` / `cfDictionary` / `fromCfString` / `fromCfData`
    with explicit retain-release discipline.
- Every write sets `kSecAttrSynchronizable = kCFBooleanTrue`; every
  lookup / delete uses `kSecAttrSynchronizableAny`.
- **No CLI fallback.** Per HARD RULE #2 (NEVER TEMPORARY) a degraded path
  that silently disables iCloud sync would just re-introduce the original
  bug. If `Security.framework` cannot be loaded, `requireSecurity()` throws
  `PlatformUnavailable` so the failure is loud.

### Entitlements

The Mac App Store build already ships with
`com.apple.application-identifier` (provisioning profile) and the iOS app
already ships with the default keychain access group. iCloud Keychain sync
works automatically as long as the user has "iCloud Keychain" enabled in
System Settings → Apple ID → iCloud. No new entitlement is needed.

**Correction (2026-06-03, #92):** the original claim that Developer ID `.dmg`
builds honour the synchronizable flag without entitlement changes is **wrong**.
macOS returns `errSecMissingEntitlement` (-34018) on a synchronizable
`SecItemAdd` unless the process carries `com.apple.application-identifier`
(+ `keychain-access-groups`), which a Developer ID app can only obtain from an
**embedded provisioning profile**. Without it `MacOsSecureStorage` silently
falls back to a LOCAL-ONLY token that never reaches iOS — observed live: the
`.dmg` stored `discord.token` locally and the iPhone never received it.

Fix shipped for the `.dmg`:
- `dist/apple/Puklic_macApp_DeveloperID.provisionprofile` — a `MAC_APP_DIRECT`
  (Developer ID) profile for `cz.damek.puklic.app`, signed for the shared
  Developer ID Application cert (ASC id `W4NA748W8U`). It grants
  `keychain-access-groups = GR74KSG8M9.*` + `application-identifier`.
- `dist/apple/local-mac-keychain.entitlements` — JIT set + the iCloud-Keychain
  trio, applied to `Contents/MacOS/Puklic` + the `.app` wrapper only (nested
  dylibs keep the JIT-only `local-mac.entitlements`).
- `dist/apple/install-local-mac.sh` embeds the profile at
  `Contents/embedded.provisionprofile` before signing.

The Mac App Store build already has `com.apple.application-identifier` from its
own profile, so it was unaffected. No certificate was regenerated or revoked
(HARD RULE #7) — only an additive per-app profile referencing the shared cert.

## User-visible behaviour

1. User logs in on Mac App Store Puklic → token written with
   `kSecAttrSynchronizable = true`.
2. iCloud Keychain replicates the row across the user's Apple devices
   within seconds (Apple controls the timing; usually < 30 s on Wi-Fi).
3. User opens Puklic on iOS → the existing `IosSecureStorage.get("token")`
   call hits the freshly-synced row → session restores automatically.

### Manual one-time step for existing users (1.2.5 release notes)

Existing users whose Mac App Store build wrote a local-only row before
1.2.5 must re-login once on the Mac after upgrading so the token is
re-written with `kSecAttrSynchronizable = true`. After that the iOS app
will pick it up automatically. iOS-only users see no behaviour change
beyond receiving tokens from Mac going forward.

## Files changed

- `ios/platform/src/commonMain/kotlin/dev/puklic/platform/ios/IosSecureStorage.kt`
- `ios/platform/src/commonTest/kotlin/dev/puklic/platform/ios/IosSecureStorageSyncFlagTest.kt` (new)
- `desktop/platform-macos/build.gradle.kts` — add JNA deps
- `desktop/platform-macos/src/main/kotlin/dev/puklic/platform/macos/MacOsSecureStorage.kt` — rewrite
- `desktop/platform-macos/src/main/kotlin/dev/puklic/platform/macos/bridge/CoreFoundation.kt` (new)
- `desktop/platform-macos/src/main/kotlin/dev/puklic/platform/macos/bridge/Security.kt` (new)
- `desktop/platform-macos/src/main/kotlin/dev/puklic/platform/macos/bridge/CfQueryBuilder.kt` (new)
- `desktop/platform-macos/src/test/kotlin/dev/puklic/platform/macos/MacOsSecureStorageSyncFlagTest.kt` (new)
- `desktop/platform-macos/src/test/kotlin/dev/puklic/platform/macos/MacOsSecureStorageTest.kt` — drop CLI-parsing tests
