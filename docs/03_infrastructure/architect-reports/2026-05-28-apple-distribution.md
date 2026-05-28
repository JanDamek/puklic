# Apple Distribution — TestFlight (internal) + App Store (single app, iOS + macOS) + APN/FCM prep

Status: ARCHITECT REPORT (Step 1 + Step 2). No production code. Templates only.
Date: 2026-05-28
Author: architect pass (puklic repo)
Scope decisions confirmed by user 2026-05-28 (this report's mandate prompt).

> **HARD RULE #2 applies in full.** Every recommendation below is "do it conceptually
> right or block". No "comment out for now", no "phase-2 shim", no half-states. Where
> an item is blocked by an external manual step (Apple Developer portal, Firebase
> console), it is flagged BLOCKED, not stubbed.

---

## 0. Executive summary

| Decision | Recommendation | Rationale |
|---|---|---|
| App Store identity | **"Designed for iPad on Mac"** (single iOS arm64 app, runs natively on Apple Silicon Macs) | Single binary, single app record, single review. Compose Multiplatform supports iOS but **not Mac Catalyst** (Catalyst is UIKit-only and incompatible with Compose iOS's SkikoUIView/MetalLayer integration). Universal Purchase = two binaries + two reviews + double maintenance. |
| Build target split | New `:ios:app` lane (Apache-2.0 deps only, no FFmpeg, no libx264, no libdave, no JNA, no PipeWire) shipped via TestFlight/App Store. Current `:desktop:app` (GPL-3.0 with FFmpeg-gpl + libx264 + libdave) stays on GitHub Releases. | App Store §3 disallows GPL-3.0 (anti-DRM clause conflicts with FairPlay). App Sandbox blocks raw UDP voice transport, JNA native loading, and arbitrary subprocess (`ffmpeg`). The split is the **only** viable path. |
| Feature parity (App Store build) | Chat-only: text, mentions, custom emoji, attachment download, markdown, image upload via UIDocumentPicker, reactions, edit/delete, login (token + email/pw). **No voice, no screenshare, no audio capture.** | All voice/screenshare deps are GPL-3.0 and/or sandbox-incompatible. Removing them is mechanical (Gradle dependency boundary). |
| Push prep | Generate APN `.p8` auth key in Apple Developer portal (HTTP/2 provider auth, modern). Create Firebase project for FCM (HTTP v1 OAuth, modern). Wire bundle ID push capability. No client wiring today — infra only. | User: "to že zatím tam nic nepůjde nevadí." |
| Submission tooling | `fastlane` + `pilot` for TestFlight upload. ASC API JWT via existing `~/.appstoreconnect/asc_api.sh` (active key = `AuthKey_6C6D4D726S.p8`, KID `6C6D4D726S`, Issuer `69a6de7f-7dab-47e3-e053-5b8c7c11a4d1`, Team `GR74KSG8M9`). | `fastlane` is the de-facto KMP+iOS standard. ASC API key already provisioned (Admin role). |

**Recommended order**: Slice 1 (build split & dep audit) → Slice 2 (iOS Xcode app shell) → Slice 3 (Compose iOS framework wiring) → Slice 4 (App ID + capabilities) → Slice 5 (fastlane + TestFlight upload) → Slice 6 (APN `.p8` + FCM project) → Slice 7 (Beta App Review submission) → Slice 8 (internal-tester invite).

---

## 1. App Store identity strategy

### 1.1 Three candidate models

| Model | Binaries | App Store records | Reviews | Compose-iOS compatible | Maintenance |
|---|---|---|---|---|---|
| **Designed for iPad on Mac** | 1 (iOS arm64) | 1 | 1 | ✅ Yes | Lowest |
| Mac Catalyst | 2 (iOS + Catalyst macOS) | 1 (single record with both binaries) | 2 | ❌ **No** — Catalyst forces UIKit; Compose iOS uses `SkikoUIView` + `MetalLayer` which run on iOS but **not under Catalyst's `UIKitForMac` runtime** (no Metal backing layer for `SkikoUIView` in Catalyst environment). | Medium |
| Universal Purchase | 2 (iOS + native macOS) | 2 (linked) | 2 | Native macOS not feasible from Compose iOS (no AppKit target in CMP); would require separate AppKit shell or Compose Desktop Mac with sandbox — see §1.4 | High |

### 1.2 Why "Designed for iPad on Mac" wins for puklic

- **JetBrains Compose Multiplatform iOS** compiles to a Kotlin/Native framework
  that's embedded in a UIKit `UIViewController` via `ComposeUIViewController { ... }`.
  Apple's "Designed for iPad" mode runs the unmodified iOS binary on Apple Silicon
  Macs through a translation layer that maps UIKit → AppKit at the OS level.
  Compose iOS apps work in this mode because **all their rendering goes through
  Metal**, and Metal is available in the Mac-iOS runtime.
- Mac Catalyst is **not** "iOS on Mac" — it's "UIKit ported to macOS" and uses
  `UIKitForMac`. Compose iOS's Metal layer integration has known incompatibilities
  there (the SkiaMetalEmbeddedRenderer doesn't initialise correctly under
  Catalyst because `CAMetalLayer` setup differs).
- One binary, one bundle ID, one TestFlight build, one Beta App Review.

### 1.3 Tradeoffs to disclose

- "Designed for iPad" apps on Mac run in iPad mode: window is iPad-shaped (resizable
  with letterboxing), keyboard works, but menu-bar integration, multi-window, and
  AppKit affordances are limited. **For a chat client this is acceptable** — users
  resize the window like an iPad app.
- Users can opt out of running an iOS app on Mac via "Mac App Store → iPhone & iPad
  Apps". We must check "Make this app available on Mac" in App Store Connect.
- Push notifications work identically in both iOS and Designed-for-iPad-on-Mac modes
  (same APNs topic = bundle ID).

### 1.4 Why NOT a separate native macOS App Store build

The current `:desktop:app` runs **outside the sandbox** (uses JNA, raw UDP, ffmpeg
subprocess, file paths). To ship it on Mac App Store would require:
1. Removing GPL deps (same split as iOS).
2. Enabling App Sandbox — breaks voice (`com.apple.security.network.client` allows
   outbound TCP/UDP but voice gateway secrets + DAVE key management require
   filesystem capabilities the user has not approved).
3. Compose Desktop's installer (jpackage) is not sandbox-clean by default.

Net: the macOS App Store route is **more work than iOS** and produces a worse
product (sandbox-castrated voice-less puklic vs. the existing full GitHub Release
.dmg). "Designed for iPad on Mac" gives Mac users the chat-only experience for
free — they install via the **Mac App Store iPhone/iPad Apps tab**, no separate
build.

### 1.5 References

- Apple — "Making your iPad app available on the Mac": https://developer.apple.com/documentation/uikit/mac_catalyst/making_your_ipad_app_available_on_the_mac
  (note: that doc covers both Catalyst and "Designed for iPad" — see "iPad Apps on
  Mac with Apple Silicon" section).
- Apple — "iPad Apps on Apple Silicon Macs": https://developer.apple.com/documentation/apple-silicon/running-your-ios-apps-on-macos
- JetBrains Compose Multiplatform iOS docs: https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-and-ios.html
- Compose iOS Metal renderer source (skiko): https://github.com/JetBrains/skiko

---

## 2. Build target split — `:desktop:app` (GPL) vs new `:ios:app` (Apache-2.0)

### 2.1 Current shared-module iOS readiness

Inventory at HEAD (2026-05-28):

| Module | JVM target | iOS targets enabled in Gradle | Notes |
|---|---|---|---|
| `:shared:ids` | yes | **not enabled** | Pure-Kotlin; iOS-ready, just needs the targets added to its convention plugin. |
| `:shared:domain` | yes | **not enabled** | Pure data classes; iOS-ready. |
| `:shared:chat-parser` | yes | **not enabled** | Pure-Kotlin parser; iOS-ready. |
| `:shared:platform-api` | yes | **not enabled** | `expect/actual` for files/clipboard/etc.; needs iOS `actual` implementations. |
| `:shared:protocol-discord` | yes | **iosMain exists (lazy guarded)** | `build.gradle.kts:27` shows the iOS source set is present but Ktor client engine for iOS not declared — needs `Darwin` engine. |
| `:shared:persistence-api` | yes | **not enabled** | Interface only; trivially iOS-portable. |
| `:shared:persistence-sqldelight` | yes | **iosMain exists (lazy guarded)** | SQLDelight has `native-driver`; needs `NativeSqliteDriver` actual. |
| `:shared:repositories` | yes | **not enabled** | Coroutines-based; iOS-ready when deps are. |
| `:shared:session` | yes | **not enabled** | Holds session state + auth flow; iOS-ready. |
| `:shared:compose-ui` | yes | **not enabled** | Compose Multiplatform UI. **Critical** — needs iOS target plus Compose iOS dependencies. |
| `:shared:voice` | yes (JVM only) | **NO and stays NO** | FFmpeg-javacpp (GPL), JNA, raw UDP. **Excluded from iOS lane.** |
| `:shared:voice-dave` | yes (JVM only) | **NO and stays NO** | libdave JNI; GPL. Excluded. |

iOS modules already in repo:
- `:ios:app` (stub `IosAppEntry.kt`)
- `:ios:platform` (stub `IosPlatform.kt`)

Both apply convention plugin `puklic.ios-library` (`build-logic/src/main/kotlin/puklic.ios-library.gradle.kts`) which already declares `iosArm64()`, `iosX64()`, `iosSimulatorArm64()`. Good baseline.

### 2.2 Work needed per shared module (no code in this report — design only)

A new convention plugin `puklic.kmp-shared-ios` (or extend existing `puklic.kmp-shared`) MUST add `iosArm64()`, `iosX64()`, `iosSimulatorArm64()` to every module listed "iOS-ready" above. For each:

- **`:shared:platform-api`** — needs iOS `actual` for: `FileChooser`, `Clipboard`, `Notifications`, `SecureStorage` (Keychain), `LogDir` (Caches dir), `OpenInBrowser` (`UIApplication.openURL`). Conceptually a one-time per-actual write.
- **`:shared:protocol-discord`** — Ktor engine `Darwin` instead of `OkHttp`/`CIO`. WebSocket via `DarwinWebSockets`.
- **`:shared:persistence-sqldelight`** — `NativeSqliteDriver(schema, "puklic.db")` actual.
- **`:shared:session`, `:shared:repositories`, `:shared:chat-parser`, `:shared:ids`, `:shared:domain`** — additive only (declare targets, no actuals needed).
- **`:shared:compose-ui`** — declare `iosMain` with `compose.runtime`, `compose.foundation`, `compose.material3`, `compose.uiTooling` (or iOS-equivalent). Replace any AWT-bound code (file pickers, drag-drop) with `expect/actual` calls into `:shared:platform-api`.

### 2.3 New `:ios:app` responsibilities

- Build a Kotlin/Native framework that exports `ComposeUIViewController { PuklicAppRoot() }`.
- The Xcode app (`iosApp/iosApp.xcodeproj`) imports the framework and hosts the
  view controller as its root.
- Bundle ID: `cz.damek.puklic.app` (suggested; user decides at App ID creation in
  Developer portal).
- **No dependency on `:shared:voice` or `:shared:voice-dave`.** Enforced by:
  ```
  ios/app/build.gradle.kts:
      // (no implementation(projects.shared.voice) — voice/screenshare excluded by design)
  ```
  Should be enforced by a Gradle check task `verifyIosNoGplDeps` that fails the
  build if any iOS module transitively pulls FFmpeg/libx264/libdave. Slice 1 work.

### 2.4 Build matrix

| Distribution | Targets | License | Voice/Screenshare | Channel |
|---|---|---|---|---|
| `:desktop:app` Linux .deb/.AppImage | x86_64 | GPL-3.0-or-later | ✅ | GitHub Releases |
| `:desktop:app` macOS .dmg | arm64 | GPL-3.0-or-later | ✅ | GitHub Releases |
| `:ios:app` TestFlight/App Store | iOS arm64 (runs on iPhone, iPad, Apple Silicon Mac via "Designed for iPad") | Apache-2.0 | ❌ | Apple App Store |

Linux/macOS desktop continues unchanged. The iOS lane is purely additive.

---

## 3. Feature parity — App Store build (`:ios:app`)

### 3.1 Shipping in v1 App Store build (chat-only MVP)

- ✅ Login (token paste + email/pw with TOTP MFA). Captcha → token-paste fallback identical to desktop.
- ✅ Guild list, channel list, DM list.
- ✅ Message list with markdown, mentions, custom emoji (image URLs), reactions, replies, edited indicator.
- ✅ Send text messages.
- ✅ Image upload via `UIDocumentPickerViewController` / `PHPickerViewController`.
- ✅ Attachment download (read-only viewing in-app, save via Share Sheet).
- ✅ Settings: theme, font scale, log dir reveal.
- ✅ Local SQLite cache (via `NativeSqliteDriver`).
- ✅ Secure token storage in iOS Keychain.

### 3.2 Excluded (blocked by GPL or sandbox)

- ❌ Voice (DAVE, RTP, UDP) — libdave is GPL; iOS App Sandbox forbids raw UDP without specific entitlements that are not granted for general apps; libavcodec is GPL.
- ❌ Screenshare — same.
- ❌ Audio playback of voice — same encoder stack.
- ⚠️ Notification sound playback — system sound only (`UNNotificationSound.default`), not custom Opus playback. OK to ship.

### 3.3 Implementation slice mapping (see §8)

All ✅ above are covered by Slices 2 + 3 + 7. ❌ items are explicitly **never shipped** in the App Store lane.

---

## 4. iOS Xcode project scaffolding

### 4.1 Directory layout (proposed; NOT created by this architect pass)

```
iosApp/                                  # NEW — committed except .xcuserdata
├── iosApp.xcodeproj/                    # Xcode project (committed)
│   └── project.pbxproj                  # human-readable, must be in VCS
├── iosApp/
│   ├── Info.plist
│   ├── iosApp.entitlements              # APNs + (future) push background mode
│   ├── Assets.xcassets/
│   │   ├── AppIcon.appiconset/
│   │   └── AccentColor.colorset/
│   ├── iOSApp.swift                     # @main App entry
│   └── ContentView.swift                # hosts ComposeUIViewController
├── Configuration/
│   ├── Config.xcconfig                  # bundle id, team, version
│   └── Release.xcconfig
└── Podfile / Package.swift              # (NOT recommended — see §4.4)
```

**This report does not create any of these files** (mandate constraint #2:
"NO Xcode project files committed"). They are listed so the user / impl slice
knows the target shape.

### 4.2 Compose iOS framework wiring

JetBrains' supported path is the **Gradle Kotlin DSL `binaries.framework`** declaration in `:ios:app`:

```kotlin
// :ios:app/build.gradle.kts (PROPOSAL — not applied here)
kotlin {
    listOf(iosArm64(), iosX64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "PuklicShared"
            isStatic = true
            export(projects.shared.composeUi)
            export(projects.shared.session)
            export(projects.shared.domain)
        }
    }
}
```

The Xcode project consumes the framework via an Xcode build phase that runs
`./gradlew :ios:app:embedAndSignAppleFrameworkForXcode` (the JetBrains-provided
task). No CocoaPods, no SPM — direct Gradle integration is the modern path
(JetBrains-recommended since CMP 1.5).

### 4.3 Info.plist requirements (chat-only build)

| Key | Value | Why |
|---|---|---|
| `CFBundleIdentifier` | `cz.damek.puklic.app` (TBD with user) | App ID |
| `CFBundleShortVersionString` | from `gradle.properties:puklic.version` | matches desktop |
| `CFBundleVersion` | CI build number | TestFlight requires monotonic |
| `LSApplicationCategoryType` | `public.app-category.social-networking` | App Store category |
| `UIApplicationSceneManifest` | single scene, no multi-window for v1 | minimum complexity |
| `NSPhotoLibraryUsageDescription` | "Puklic uses your photo library to attach images to messages." | required for `PHPickerViewController` |
| `ITSAppUsesNonExemptEncryption` | `false` (we only use TLS — exempt) | export compliance |
| `UIBackgroundModes` | `remote-notification` (when push lands) | APNs background delivery |

**No `NSMicrophoneUsageDescription`, `NSCameraUsageDescription`,
`NSLocalNetworkUsageDescription`** — we have no voice/local-network features
in this build. Adding them would invite reviewer questions about features that
don't exist.

### 4.4 Entitlements (chat-only build)

```xml
<!-- iosApp.entitlements (PROPOSAL) -->
<key>aps-environment</key>
<string>development</string>  <!-- "production" for App Store / TestFlight -->
```

That's all. No keychain access groups (default works), no audio background, no
network entitlements (iOS App Sandbox is default-allow for outbound HTTPS).

### 4.5 Why NOT SPM or CocoaPods

Both add an additional dependency manager on top of Gradle. JetBrains has
deprecated the CocoaPods integration for new projects. SPM integration works
but adds a "Package.swift" the developer must keep in sync. Direct Gradle
framework export (`binaries.framework` + `embedAndSignAppleFrameworkForXcode`)
is the simplest correct path for a single-app KMP project.

---

## 5. TestFlight pipeline — `fastlane` + `pilot`

### 5.1 Tool choice rationale

| Tool | Pros | Cons | Verdict |
|---|---|---|---|
| `fastlane` (`pilot`/`deliver`) | De-facto standard. Handles ASC API JWT, codesign, exportArchive, IPA upload. Ruby-based; well-documented; CI-friendly. | Ruby dependency. | **Recommended.** |
| `xcrun altool` | Built-in. | Deprecated for new uploads (Apple direction is `xcrun notarytool` + `xcrun altool` → `Transporter`). | No. |
| Transporter.app | GUI. | Manual; no CI story. | No. |
| Xcode Cloud | Apple-native. | Requires the source repo in Apple's view (or bridge to GitHub); paid beyond free hours; ties pipeline to Apple. ASC API key `4ZZ7ZJP4IXIR` was provisioned with App ID `6759265560` (Jervis) workflow `D74B382E-...` — that's a **different app** (Jervis), not puklic. Would need a separate Xcode Cloud workflow for puklic. | Future option; not v1. |
| `xcrun notarytool` | For notarisation (DMG). | App Store / TestFlight uploads happen pre-notarisation — Apple notarises the IPA on their side after ASC ingest. Not the right tool here. | No (for TestFlight). |

### 5.2 Fastlane configuration outline (template — see `dist/apple/Fastfile.template`)

The template uses these environment variables (all set in CI secrets, never in repo):

```
ASC_KEY_ID=6C6D4D726S
ASC_ISSUER_ID=69a6de7f-7dab-47e3-e053-5b8c7c11a4d1
ASC_KEY_PATH=/Users/runner/.appstoreconnect/private_keys/AuthKey_6C6D4D726S.p8
TEAM_ID=GR74KSG8M9
BUNDLE_ID=cz.damek.puklic.app     # TBD; user confirms at App ID creation
APP_STORE_CONNECT_APP_ID=    # populated after App Store Connect record created
```

Lanes:
- `lane :beta` — increment build number, run Gradle to build iOS framework, run `xcodebuild archive`, run `xcodebuild -exportArchive` with `ExportOptions-AppStore.plist`, upload IPA via `pilot` to TestFlight, set internal-tester group.

### 5.3 Build-number policy

TestFlight rejects duplicate `(CFBundleShortVersionString, CFBundleVersion)`. Use:
- `CFBundleShortVersionString` = `gradle.properties:puklic.version` (e.g. `1.3.0`)
- `CFBundleVersion` = CI run number (monotonic). `fastlane`'s `increment_build_number(build_number: ENV['GITHUB_RUN_NUMBER'])`.

### 5.4 Local-host signing prerequisites

- Apple Developer membership (paid, user has — Team ID `GR74KSG8M9` confirmed in `asc_api.sh`).
- macOS host with Xcode 15+ (CI: `macos-14` runner).
- Distribution certificate "Apple Distribution" in keychain (fastlane `match` recommended for CI; for local-host the user installs once).
- Provisioning profile "App Store" for `cz.damek.puklic.app`. Auto-managed via `xcodebuild -allowProvisioningUpdates` once Team ID + App ID are configured.

---

## 6. APN setup — `.p8` auth key (HTTP/2 provider)

### 6.1 Key identification — current state

`~/.appstoreconnect/private_keys/AuthKey_6C6D4D726S.p8` is **NOT** an APNs key,
despite the `AuthKey_` prefix. Evidence from `asc_api.sh`:

```
KEY_ID="6C6D4D726S"
# AuthKey_6C6D4D726S.p8 = claude-cli Team Key, Admin role (active)
```

This key is the **active App Store Connect API Team Key** (Admin role). The
naming convention `AuthKey_<KID>.p8` is what Apple's Developer portal uses for
**both** ASC API keys **and** APNs auth keys — the prefix alone doesn't
distinguish them. They are managed in different sections of the portal:

- ASC API keys: App Store Connect → Users and Access → Integrations → App Store Connect API
- APNs auth keys: Apple Developer → Certificates, Identifiers & Profiles → Keys

`ApiKey_4ZZ7ZJP4IXIR.p8` is the older individual ASC key (the `ApiKey_` prefix
is non-standard; likely renamed manually by the user).

**Conclusion**: there is currently **NO APNs auth key on disk**. One must be
generated. This is a manual user step in Developer portal (no API to create
APN keys).

### 6.2 Manual user steps (flagged for user)

1. Apple Developer portal → Keys → ➕ → name "Puklic APNs", check "Apple Push Notifications service (APNs)" → Continue → Register.
2. Download the generated `AuthKey_<KID>.p8` **once** (cannot be re-downloaded).
3. Store at `~/.appstoreconnect/private_keys/AuthKey_<KID>_APNS.p8` (rename to disambiguate from the ASC key).
4. Record the Key ID + Team ID (`GR74KSG8M9`) + topic (bundle ID `cz.damek.puklic.app`).
5. The APN topic for HTTP/2 push is exactly the bundle ID for alerts. (`.voip` and `.complication` suffixes are NOT needed for puklic.)

### 6.3 Why `.p8` over legacy `.p12`

- `.p12` certs expire every 1 year, must be regenerated per environment (dev/prod), per topic.
- `.p8` auth keys are **environment-agnostic** (one key for dev + prod), do not expire (rotate manually), and use modern HTTP/2 + JWT. This is Apple's recommended modern provider auth path.
- Server side sends JWT signed with `.p8` (ES256, KID + Team ID in claims) on each connection to `api.push.apple.com:443` (prod) or `api.sandbox.push.apple.com:443` (dev).

### 6.4 Bundle ID push capability

When the App ID `cz.damek.puklic.app` is created (manual step in Developer portal):
- Check "Push Notifications" capability.
- No additional configuration needed for the `.p8` key path (one key services all App IDs in the team).

### 6.5 Server-side: deferred

User said: "to že zatím tam nic nepůjde nevadí." No server is built today. When push is wired:
- A small relay service or direct from `service-orchestrator` (jervis) signs JWTs and POSTs to `https://api.push.apple.com/3/device/<deviceToken>`.
- Recommended lib: `node-apn` (Node.js) or `apns2` (Python) or `pushy` (JVM). Decision deferred per scope.

### 6.6 Documentation

See `dist/push/README.md` for the manual steps list, kept in repo for future reference.

---

## 7. FCM setup — HTTP v1 OAuth

### 7.1 Why FCM v1 over legacy server key

- FCM legacy "Server Key" auth is **deprecated as of 2024-06-20** (Google sunset). New projects must use HTTP v1 + Google OAuth2 (service-account JWT, scope `https://www.googleapis.com/auth/firebase.messaging`).
- HTTP v1 is type-safe (typed `Message` proto), supports per-message customisation (data + notification + token).

### 7.2 Manual user steps (flagged for user)

1. Go to https://console.firebase.google.com → "Add project" → name "Puklic" → continue.
2. Inside the project → ⚙️ Project settings → "Service accounts" → "Generate new private key" → downloads `puklic-firebase-adminsdk-<hash>.json`.
3. Store at `~/.firebase/puklic-fcm-service-account.json` (do not commit).
4. ⚙️ Project settings → "Cloud Messaging" tab → note the Sender ID + Server Key (only for reference; not used in HTTP v1).
5. For Android (future): ⚙️ → "Add app" → Android → package `cz.damek.puklic.android` → download `google-services.json` → place at `android/app/google-services.json`. **Not added to repo yet** (no Android push consumer).
6. For iOS — Firebase has an APNs bridge but **not needed for puklic**: we use APNs directly. FCM is only consulted for Android in our design. (FCM-on-iOS is for projects that want a single push abstraction; we don't.)

### 7.3 What gets prepared in repo today

- `dist/push/README.md` — bullet list of manual steps + reference to this report
- **NO** `google-services.json` (correct — Android push is future work)
- **NO** Firebase Admin SDK service-account JSON (secrets never in repo)

### 7.4 Decision: split push routing

| Platform | Channel | Auth |
|---|---|---|
| iOS / Mac-as-iPad | **APNs direct** (`.p8` HTTP/2) | Apple Developer APNs key |
| Android | **FCM HTTP v1** | Firebase service-account JSON |
| Desktop (Linux/macOS) | OS notification only; no push channel (foreground WebSocket only) | n/a |

This is the conceptually correct split — no relay layer in the middle, each
platform uses its native push channel. Discussion on whether to add a relay
server later (for desktop background push) is **out of scope** per user.

---

## 8. Implementation slices (HARD RULE #2 — each ships standalone)

Each slice has its own architect/critic/test-first/impl/critic/deploy pipeline (HARD RULE #1). No slice ships a "phase 2 follow-up" — if it isn't done, the slice doesn't ship.

| # | Slice | Deliverable | External prereqs |
|---|---|---|---|
| 1 | **iOS dep boundary + verify task** | Add `verifyIosNoGplDeps` Gradle task; CI fails if `:ios:app` transitively pulls FFmpeg/libx264/libdave/JNA/javacpp. Document the dep policy in `docs/03_infrastructure/dep-policy.md`. | none |
| 2 | **Shared modules iOS targets enabled** | All Apache-2.0 shared modules declare `iosArm64/iosX64/iosSimulatorArm64`. New `actual` impls for `:shared:platform-api` (Keychain, file picker stub, clipboard, logdir, openURL), `:shared:protocol-discord` (Ktor Darwin), `:shared:persistence-sqldelight` (NativeSqliteDriver). Tests green on all three iOS targets. | none |
| 3 | **`:ios:app` Compose iOS framework** | `:ios:app` exports `PuklicShared.framework` via `binaries.framework`. `ComposeUIViewController { PuklicAppRoot() }` returns a real composable that hosts the existing Compose UI tree. Gradle task `embedAndSignAppleFrameworkForXcode` works. | none |
| 4 | **Xcode project (iosApp/)** | `iosApp/iosApp.xcodeproj` committed (no `.xcuserdata`). `iOSApp.swift` + `ContentView.swift` hosting the framework. Builds to `.ipa` on local macOS host (manual signing OK). Info.plist + entitlements per §4. | Apple Developer membership active (have it). |
| 5 | **App ID + capabilities in Developer portal** | User creates App ID `cz.damek.puklic.app`, enables Push Notifications + (no other capabilities). Records App Store Connect app record. **Manual user step** — architect issues a checklist. | User action |
| 6 | **fastlane + ExportOptions** | `fastlane/` directory with Fastfile (from template in `dist/apple/Fastfile.template`), `ExportOptions-AppStore.plist`. CI workflow `apple-testflight.yml` triggered manually or on git tag `ios-v*`. | ASC API key already on disk (`AuthKey_6C6D4D726S.p8`). User adds CI secrets. |
| 7 | **First TestFlight upload + Beta App Review** | CI uploads .ipa to TestFlight. First build triggers Beta App Review (always required for new app, internal-only or not — Apple's rule for first build). Submission notes acknowledge §9 risks. | App Store Connect record created in #5. |
| 8 | **Internal tester group invite** | App Store Connect → TestFlight → Internal Testing → group "Internal" with the user's Apple ID(s). After build is approved by Beta App Review (~24-48h), users receive TestFlight invite. | Beta App Review approval. |
| 9 (optional, future) | **APN `.p8` provisioning** | User generates APN key per §6.2. Stored at `~/.appstoreconnect/private_keys/AuthKey_<KID>_APNS.p8`. README documented. **No client wiring** — push consumer is future slice. | User action |
| 10 (optional, future) | **Firebase project + service account** | User creates Firebase project per §7.2. Service account JSON stored at `~/.firebase/puklic-fcm-service-account.json`. README documented. **No client wiring** — Android push consumer is future slice. | User action |

Slices 1–8 ship the actual TestFlight pipeline. Slices 9–10 provision push infra
without a consumer (per user's "to že zatím tam nic nepůjde nevadí" — infra
ready, consumer when designed).

---

## 9. Risks + alternatives

### 9.1 App Review §5.2 — third-party client risk

- **Risk**: App Review rejects on §5.2.1 ("Apps may not violate the rights of any third party … Discord trademarks") or §5.2.2 ("intermediary"). Vesktop / Vencord / similar are NOT in the App Store; this is a meaningful signal.
- **Mitigations**:
  - App Store metadata explicitly states "Unofficial Discord-compatible client" (transparency).
  - No Discord trademarks in icon, screenshots, name. App name = "Puklic" (already trademark-clean).
  - Submission notes acknowledge "client communicates with Discord's public API using the same protocol as the official client; user supplies their own credentials; no scraping/automation".
  - Discord ToS risk acknowledged in `CLAUDE.md §Discord protocol`.
- **Fallback if rejected**: Stay on GitHub Releases (desktop) — iOS users go without. Re-frame the App Store record as "open-source chat client framework demo" (less honest, not preferred). User decides.

### 9.2 Beta App Review rejection for "missing functionality"

- New apps with limited features (no voice/share) sometimes get "Minimum Functionality" §4.2 rejection.
- **Mitigation**: Submit with screenshots showing chat, reactions, attachments, emoji, login flow. Demo video showing a real conversation. Position as "chat-only client" intentionally.

### 9.3 Compose iOS performance / stability

- Compose Multiplatform iOS is **Stable since CMP 1.5** but the dev community still reports rough edges: text input edge cases, IME handling, scroll perf on long lists. Puklic's chat-list is long-list-heavy.
- **Mitigation**: Slice 3 includes a "scroll 500-message channel" smoke test as acceptance. If it fails, surface as critic finding; do NOT ship "v1 with known scroll jank".

### 9.4 Apple Silicon Mac runtime issues

- "Designed for iPad on Mac" apps sometimes hit edge cases: clipboard differences, file picker presentation, missing menu bar. For a chat client these are minor.
- **Mitigation**: In-app test plan must include a Mac-as-iPad verification pass per release.

### 9.5 Long-term: voice/screenshare on iOS

- Out of scope today, but to reactivate it would require: (a) re-licensing or wrapping libdave under LGPL or replacing with a clean-room MLS lib; (b) replacing FFmpeg with a non-GPL stack (e.g. native `VideoToolbox` + `Opus.framework`); (c) navigating App Sandbox network restrictions. Multi-quarter effort. Document this as a "Phase 7" candidate, not v1.

---

## 10. Unknown credentials — flagged for user

| Item | Required value | Source |
|---|---|---|
| ASC Issuer ID | `69a6de7f-7dab-47e3-e053-5b8c7c11a4d1` | Already in `asc_api.sh`. ✅ resolved. |
| ASC API Key ID | `6C6D4D726S` | Already on disk + in `asc_api.sh`. ✅ resolved. |
| Apple Team ID | `GR74KSG8M9` | Already in `asc_api.sh`. ✅ resolved. |
| iOS Bundle ID | **TBD** (recommendation: `cz.damek.puklic.app`) | User confirms in Slice 5. |
| App Store Connect App ID | **TBD** | User creates App Record in Slice 5. |
| **APN Auth Key (`.p8`)** | **NOT YET CREATED** | User manually generates in Apple Developer portal — Slice 9. |
| **APN Key ID** | **TBD** | Output of Slice 9. |
| **Firebase Project ID** | **TBD** | User creates at console.firebase.google.com — Slice 10. |
| **Firebase Service Account JSON** | **TBD** | User downloads in Slice 10. |

The two `.p8` files on disk today are BOTH ASC API keys (the modern Team Key + a legacy individual key). There is **no APN key on disk yet**.

---

## 11. Manual Apple Developer / ASC steps (consolidated checklist)

Cannot be automated. User does these in order:

- [ ] Slice 5a: Apple Developer portal → Certificates, Identifiers & Profiles → Identifiers → ➕ → App IDs → App → Bundle ID `cz.damek.puklic.app` (or chosen) → enable "Push Notifications" capability → Register.
- [ ] Slice 5b: App Store Connect → My Apps → ➕ → New App → platform "iOS" → check "Make this app available on Apple Silicon Macs" (Designed for iPad on Mac) → Bundle ID = above → SKU = `puklic-ios` → Primary Language = English → Create.
- [ ] Slice 5c: App Store Connect → Apps → Puklic → App Information → fill in: category (Social Networking), content rights, age rating questionnaire. Privacy policy URL + support URL required (host on GitHub Pages or puklic.dev).
- [ ] Slice 9 (push prep): Developer portal → Keys → ➕ → "Puklic APNs" → check "Apple Push Notifications service (APNs)" → Continue → Register → Download `.p8` (one-shot) → store at `~/.appstoreconnect/private_keys/AuthKey_<KID>_APNS.p8`.
- [ ] Slice 10 (Firebase prep): console.firebase.google.com → Add project → "Puklic" → Service accounts → Generate new private key → store at `~/.firebase/puklic-fcm-service-account.json`.

---

## 12. Firebase manual steps (consolidated checklist)

See §7.2 above. Repeated as checklist:

- [ ] Open https://console.firebase.google.com
- [ ] "Add project" → name "Puklic" → disable Google Analytics (not needed) → Create.
- [ ] Project settings ⚙️ → "Service accounts" tab → "Firebase Admin SDK" → "Generate new private key" → download JSON.
- [ ] Store at `~/.firebase/puklic-fcm-service-account.json` (chmod 600).
- [ ] (Future, when Android push lands) Project settings → "Your apps" → ➕ → Android → package name `cz.damek.puklic.android` → download `google-services.json` → place in `android/app/`. **DO NOT** add until Android push consumer is implemented (would be a half-state).

---

## 13. Templates created by this architect pass

(Templates only — no live credentials, no Xcode project files, no `.p8` keys.)

- `dist/apple/ExportOptions-AppStore.plist`
- `dist/apple/Fastfile.template`
- `dist/apple/README.md`
- `dist/push/README.md`

Plus this report.

---

## 14. Roadmap addendum

A new Phase 6 (mobile + App Store) is appended to `docs/07_roadmap/phases.md` with the slices from §8 as `[ ]` items.

---

## Appendix A — Tradeoff matrix: Designed-for-iPad-on-Mac vs Catalyst vs Universal

| Criterion | Designed for iPad | Catalyst | Universal Purchase |
|---|---|---|---|
| Binaries to build | 1 | 2 | 2 |
| App Store records | 1 | 1 | 2 (linked) |
| Beta App Reviews per submission | 1 | 2 | 2 |
| Compose Multiplatform compatible | ✅ | ❌ (UIKitForMac drops Metal layer for Skiko) | Native macOS path needs separate AppKit shell (not feasible w/ CMP today) |
| User installs on Mac via | Mac App Store iPhone/iPad Apps tab | Mac App Store (native) | Mac App Store (native) |
| RAM target (<300 MB) | ✅ (iOS efficiency) | ✅ | ✅ but native shell is more code |
| Engineering cost | Lowest | Medium | Highest |
| Maintenance per release | 1 build verify | 2 builds | 2 builds + 2 codebases |

Recommendation: **Designed for iPad on Mac** for the entirety of Apple distribution.

---

## Appendix B — Decision log

- 2026-05-28: Architect pass per user mandate. Recommendations documented. Awaiting user approval on Step 4 of HARD RULE #1 before any implementation slice starts.

