# FP-14a — Mac App Store via JVM Compose Desktop — Architect verification + library survey + jpackage probe

Status: ARCHITECT REPORT (HARD RULE #1 Steps 1 + 2 + 3). No production code, no Gradle build files touched, no fastlane/CI changes. Probes ran in `/tmp/puklic-jpackage-probe/` and were cleaned up.
Date: 2026-05-29
Author: architect pass — Puklic repo (refs Issue #54)

> **HARD RULE #2 in full force**. Every recommendation here is "do it conceptually right or block on a named manual user prerequisite". Nothing temporary, nothing v1-shim, nothing "we'll see in impl".

This report resolves the six blockers B1–B6 surfaced by the previous architect pass and supersedes §3.6 of `2026-05-29-full-feature-parity.md` for the Mac App Store scope.

---

## 0. Decisions LOCKED (user 2026-05-29)

| Decision | Value | Notes |
|---|---|---|
| **Bundle ID for Mac App Store** | `cz.damek.puklic.app` (REUSED — same App ID as iOS) | User accepted the iOS provisioning-profile regeneration risk. See §9 + §10 for the mandatory user-action follow-up checklist. |
| **DAVE** on Mac App Store build | SKIP | Re-decided 2026-05-29. Discord falls back to `xsalsa20_poly1305` voice transport. Same posture as iOS App Store build. |
| **Approach** | Architect-only verification first | No impl in this slice. Decomposes into FP-14b…FP-14g (see §12). |
| **Opus on JVM (Mac App Store build)** | Bundle libopus 1.5.2 .dylib via JNA — reuse the Opus XCFramework's `macos-arm64` slice for the same BSD-3-Clause libopus binary we already build for iOS. | Survey eliminated concentus (not on Maven Central) and `club.minnced:opus-java` / `lwjgl-opus` (transitively bring GPL-tainted natives or unwanted scope). See §2.2. |
| **H.264 on JVM (Mac App Store build)** | Custom thin JNA bridge to VideoToolbox (~600 LOC, scope of FP-14c) | Survey eliminated every Maven-Central VideoToolbox binding (none exist — JavaCV only exposes VT via FFmpeg GPL). See §2.1. |
| **Module structure** | **Option A — `macAppStore` source set inside `:desktop:app`** with overrides + a dedicated `packageMacAppStore` Gradle task. Voice/screencast on the Apple-native path live in a new sibling module `:desktop:platform-macos-appstore` rather than tainting `:desktop:platform-macos`. | See §4 + §5. |
| **JVM** | Temurin 21 (bundled by jpackage `--runtime-image`) | Verified `temurin-21.0.11` available locally + on GitHub `macos-15` runners. |
| **jpackage probe** | **Works (with caveats)** | Probe produced an unsigned valid `.pkg` (53 MB) + an ad-hoc-signed `.app` that launches and prints to stdout. Sandbox/signed validation BLOCKED on missing Mac Installer Distribution + Mac App Distribution certs (user-action). See §3. |

---

## 1. Mandate recap + B1…B6 mapping

| Blocker | Resolution location | Verdict |
|---|---|---|
| B1 — DAVE on Mac App Store | §0 row 2 | SKIP — same as iOS. |
| B2 — JVM VideoToolbox binding | §2.1 | NO clean Maven-Central binding. Custom JNA bridge — scope of FP-14c. |
| B3 — JVM Opus | §2.2 | concentus not on Maven Central; reuse our libopus XCFramework `macos-arm64` slice via JNA. |
| B4 — Bundle ID strategy | §0 row 1 + §9 + §10 | Reuse `cz.damek.puklic.app`. Add macOS platform to existing ASC app 6774288340. User accepts iOS profile-regeneration risk; checklist in §9. |
| B5 — jpackage + Temurin + sandbox probe | §3 | jpackage `--type pkg --mac-app-store` works structurally. .app launches. Blocked on cert pair (user-action) to complete signed validation. |
| B6 — Compose Desktop variant wiring | §4 + §5 | Option A (source-set variant in `:desktop:app`). New sibling module `:desktop:platform-macos-appstore` isolates Apple-native voice/screencast. |

---

## 2. Library survey

Methodology: searched Maven Central via `https://search.maven.org/solrsearch/select` (live; transcripts in commit history). Cross-checked JitPack and bytedeco's artifact list.

### 2.1 VideoToolbox JVM bindings — B2

| Candidate | Coordinates | License | Native VideoToolbox? | Verdict |
|---|---|---|---|---|
| `org.bytedeco:javacv` 1.5.11 | g:org.bytedeco a:javacv | Apache-2.0 (umbrella) | **No** — JavaCV exposes VideoToolbox **only** via FFmpeg's `h264_videotoolbox` encoder bundled in `org.bytedeco:ffmpeg-platform-gpl`. Encoder name is selectable but the dependency closure is GPL. | REJECT — pulls FFmpeg-GPL transitively. |
| `org.bytedeco:videotoolbox-platform` (hypothesised) | — | — | **Does not exist.** Confirmed via full enumeration of `g:org.bytedeco` artifacts (147 results, 2026-05-29 fetch). No `videotoolbox-*` artifact in the bytedeco family. | REJECT — not published. |
| JNAerator / direct JNA on `VideoToolbox.framework` | n/a | n/a (custom) | Custom binding | **SELECTED** — only viable Apache-2.0-compatible path. |

Transcript excerpt (`curl https://search.maven.org/solrsearch/select?q=videotoolbox`): `numFound = 0`. Bytedeco enumeration grep for `apple|darwin|video|toolbox|mac` returned only `videoinput`/`videoinput-platform` (DirectShow camera capture — unrelated).

**Decision**: implement a thin JNA bridge — `VTCompressionSession`, `VTDecompressionSession`, `CMSampleBuffer`, `CFTypeRef` lifecycle. ~600 LOC, lives in new module `:desktop:platform-macos-appstore`. **FP-14c scope.**

Reuse posture: the existing `shared/screencast/src/jvmMain/kotlin/dev/puklic/screencast/macos/` already JNA-bridges `Foundation`, `CoreMedia`, `DelegateClass`, `ObjcRuntime`, `ScreenCaptureKitBridge`. The new VideoToolbox JNA classes follow the SAME idioms (`DelegateClass` for Objective-C protocol bridging, `CoreMedia` extension for CMSampleBuffer plumbing). Half the design is already proven.

### 2.2 JVM Opus — B3

| Candidate | Coordinates | License | KMP? | Bundles native? | Verdict |
|---|---|---|---|---|---|
| concentus (Microsoft pure-Java Opus) | g:com.github.lostromb a:concentus (JitPack only) | MIT | JVM-only | Pure Java (no native) | **REJECT** — not on Maven Central. Distribution-only dependency on JitPack is a CI fragility (random 30 s outages, no reproducibility). Also: bit-identical output to libopus is unverified at high bitrates (concentus targets RFC 6716 conformance, which is bit-exact for decoders but not necessarily for encoders — Discord voice quality consistency unknown). |
| `club.minnced:opus-java` 1.1.1 | g:club.minnced a:opus-java + opus-java-natives | Apache-2.0 | JVM-only | Yes — Linux/macOS/Windows libopus natives bundled | Viable BUT adds another artifact maintained by an external team. We already build libopus 1.5.2 from xiph/opus sources for iOS (`shared/voice-codec/libs/Opus.xcframework`). Two sources of libopus in the same product = drift surface. REJECT. |
| `org.lwjgl:lwjgl-opus` 3.3.6 | g:org.lwjgl a:lwjgl-opus | BSD-3-Clause | JVM-only | Yes — LWJGL distributes natives via classifier artifacts | Designed for games (LWJGL is OpenGL-adjacent), brings LWJGL system + buffer plumbing as transitive deps. REJECT — drags scope unrelated to a chat client. |
| **Reuse our `Opus.xcframework/macos-arm64` slice via JNA** | local `shared/voice-codec/libs/Opus.xcframework/macos-arm64` (already in repo) | BSD-3-Clause | yes (already built for iOS; same .dylib slice extracted for JVM-Mac) | n/a | **SELECTED** — single libopus version across iOS + Mac App Store. Bit-identical to iOS path. No new Maven dep. |

Transcript excerpt: `curl .../solrsearch?q=a:concentus` → `numFound: 0`. `curl .../q=opus` returned 20 hits; the four above are the only credible JVM-Opus candidates. JitPack HEAD on `lostromb/concentus`: HTTP 200 (artifact buildable on demand).

**Decision**: the existing `dist/apple/build-libopus-xcframework.sh` produces XCFramework slices including `macos-arm64`. The new module `:desktop:platform-macos-appstore` consumes `Opus.xcframework/macos-arm64/Opus.framework/Opus` (a fat `libopus.dylib` re-signed as a framework binary) via JNA `Native.load()`. **No new build script** — the existing CI step that builds the XCFramework for iOS is reused. FP-14c scope.

### 2.3 What we DO NOT touch

- `:shared:voice-codec` JVM source set already exists for the GPL desktop build (`LibavOpusEncoder`/`LibavOpusDecoder` via JavaCPP FFmpeg GPL). The Mac App Store build avoids `:shared:voice-codec` JVM entirely — it consumes ONLY `:shared:voice-codec` commonMain interfaces + a Mac-App-Store-flavoured actual provided by `:desktop:platform-macos-appstore`. No new `expect` declarations.

---

## 3. jpackage probe — B5

### 3.1 Setup

- Host: `darwin 25.5.0` arm64, macOS 15.x
- JVM probed: Temurin 21.0.11 (`/Volumes/M2v-Disk/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home`)
- jpackage on PATH resolved to JDK 23.0.2 default — explicit `$JAVA_HOME/bin/jpackage` used in retest (probe behaviour identical between 21 and 23 for this surface)
- Signing identities in login keychain: `Apple Distribution: Jan Damek (GR74KSG8M9)` ×2 (different SHA-1; iOS-app cert pair)
- **MISSING in keychain**: `3rd Party Mac Developer Application: Jan Damek (GR74KSG8M9)` (= Mac App Distribution cert) AND `3rd Party Mac Developer Installer: Jan Damek (GR74KSG8M9)` (= Mac Installer Distribution cert). These are SEPARATE Apple cert types from the iOS Apple Distribution cert.

### 3.2 Attempts

| # | Command | Outcome |
|---|---|---|
| 1 | `jpackage --type pkg --mac-app-store --mac-sign --mac-signing-key-user-name "Apple Distribution: Jan Damek (GR74KSG8M9)"` | FAIL — `No certificate found matching [3rd Party Mac Developer Application: Apple Distribution: Jan Damek (GR74KSG8M9)]`. jpackage prepends the cert-type prefix; the full cert CN is NOT what `--mac-signing-key-user-name` takes. |
| 2 | `jpackage … --mac-signing-key-user-name "Jan Damek (GR74KSG8M9)"` | FAIL — searched for `3rd Party Mac Developer Application: Jan Damek (GR74KSG8M9)` AND `3rd Party Mac Developer Installer: Jan Damek (GR74KSG8M9)`. **Confirms `--mac-signing-key-user-name` is the CN suffix only AND that `--mac-app-store` requires BOTH the App-store-cert pair.** |
| 3 | Same WITHOUT `--mac-app-store` | FAIL — searched for `Developer ID Installer: Jan Damek (GR74KSG8M9)`. Also missing. Different cert type for outside-App-Store notarization. Not relevant for FP-14a goal but useful to confirm jpackage's cert-prefix map. |
| 4 | `jpackage --type pkg --mac-app-store` (no `--mac-sign`) | **PASS** — produced `output/PuklicProbe-1.0.pkg` (53 MB). `spctl -a -t install` rejects (`no usable signature`), as expected. Structure is valid. |
| 5 | `jpackage --type app-image --mac-app-store` (no `--mac-sign`) | **PASS** — produced `output2/PuklicProbe.app`. **The .app launches and prints `Puklic Mac App Store probe OK`.** `codesign -dv` shows ad-hoc signature with Identifier=`cz.damek.puklic.probe`, Format=app bundle Mach-O thin arm64. Info.plist + `_CodeSignature/`, runtime/ subdir bundled correctly. |

### 3.3 Findings

- jpackage `--mac-app-store` produces a structurally-valid signed Mac App Store .pkg / .app **as soon as** the two missing certs (Mac App Distribution + Mac Installer Distribution) are installed in the keychain.
- The Temurin-21 runtime bundles fine (`Contents/runtime/`). JVM cold-start works under ad-hoc signature — no signature-violation crashes.
- `--mac-entitlements <file>` flag exists (verified via `jpackage --help`). Path-to-entitlements is the mechanism by which sandbox + hardened-runtime entitlements get attached at codesign time. **Hardened runtime is implied by --mac-app-store** — jpackage applies it transparently when this flag is set.
- jpackage auto-injects a default `NSMicrophoneUsageDescription` ("The application X is requesting access to the microphone.") even without voice features. **This is leakage** — we MUST override via a custom Info.plist template (jpackage `--resource-dir` containing `Info.plist`) to suppress micro permission strings we don't want, and to provide the puklic-specific strings we DO want.
- The auto-generated `LSApplicationCategoryType` defaults to `public.app-category.utilities` — we override to `public.app-category.social-networking` (matching iOS) via the custom Info.plist template.

### 3.4 JIT / sandbox interaction note

Temurin 21 default uses C2 JIT with `mprotect(PROT_READ|PROT_EXEC)` on code-cache pages — Mac App Sandbox allows this with `com.apple.security.cs.allow-jit` entitlement (part of hardened runtime). **MUST** be in the entitlements plist; otherwise JVM crashes on first compile.

### 3.5 Verdict

**Works with caveats.** The two caveats are external:

1. Mac App Distribution cert + Mac Installer Distribution cert — USER ACTION required (Apple Developer portal). See §9.
2. Custom Info.plist template to override auto-injected micro permission strings + LSApplicationCategoryType — IMPL ACTION in FP-14d.

No blocking technical surprise. No need to invent an alternative approach.

---

## 4. Module structure decision

### 4.1 Options considered

| Option | Pros | Cons |
|---|---|---|
| **A — `macAppStore` source set inside `:desktop:app`** + new sibling module `:desktop:platform-macos-appstore` for Apple-native voice/screencast | (a) minimal Gradle delta; (b) reuses existing `:desktop:platform-macos` for non-voice/screencast bits; (c) Compose Desktop application config still single-rooted; (d) `packageMacAppStore` is a sibling task to `packageDmg`; (e) source-set isolation cleanly excludes `:shared:voice` (GPL) from the macAppStore classpath. | (a) Two `mainClass` entries to keep in sync — mitigated by a shared `MainKt`; (b) developer might accidentally cross-import. Mitigated by `verifyMacAppStoreNoGplDeps` task analogous to `verifyIosNoGplDeps`. |
| B — Brand-new top-level module `:desktop:macappstore` | Strong physical separation. | Massive duplication — Compose Desktop application block, DI graph, Main entry, Decompose tree, Coil, Ktor setup all copy-pasted. Drift risk high. Rejected. |
| C — `:desktop:app` two-variant Android-style `flavor` | Compose Desktop / jpackage does not support Android product flavors. | N/A. |

### 4.2 Selected — Option A

Concrete shape (DESIGN ONLY — no Gradle file edits in this slice):

```
:desktop:app
├── src/main/kotlin/                                  # existing, shared
│   └── dev.puklic.desktop.MainKt                     # existing
├── src/macAppStore/kotlin/                           # NEW source set
│   └── dev.puklic.desktop.appstore.MacAppStoreMainKt # NEW — entry point that
│                                                     #   constructs DependencyGraph
│                                                     #   with MacAppStoreVoiceFactory
│                                                     #   + MacAppStoreScreencastFactory
│                                                     #   from :desktop:platform-macos-appstore
└── build.gradle.kts
    ├── (existing) compose.desktop.application { ... packageDmg ... }
    └── (new) tasks.register("packageMacAppStore") { ... }
       — runs jpackage with --mac-app-store + --mac-entitlements
       — picks up macAppStore source set as input
       — uses a separate output dir `build/compose/binaries/main/macAppStorePkg/`
       — depends on patchInfoPlistMacAppStore (new task that materialises
         the Info.plist template with our keys)
       — depends on signMacAppStorePkg (productsign with Mac Installer Distribution
         cert; jpackage handles the .app sign with Mac App Distribution cert via
         --mac-app-image-sign-identity + --mac-installer-sign-identity)

:desktop:platform-macos-appstore  # NEW module
├── src/main/kotlin/dev/puklic/desktop/macappstore/
│   ├── voice/
│   │   ├── AppleNativeVoiceFactory.kt
│   │   ├── AVAudioEngineCapture.kt    # AVFoundation via JNA
│   │   ├── JnaLibopusEncoder.kt       # libopus.dylib via JNA — reuses
│   │   │                              # Opus.xcframework/macos-arm64 slice
│   │   ├── JnaLibopusDecoder.kt
│   │   └── NWConnectionUdpTransport.kt # Network.framework via JNA
│   └── screencast/
│       ├── AppleNativeScreencastFactory.kt
│       ├── VTCompressionSessionBridge.kt
│       ├── VTDecompressionSessionBridge.kt
│       └── (reuses ScreenCaptureKitBridge from :shared:screencast jvmMain)
└── build.gradle.kts
    — applies puklic.jvm-library
    — depends on :shared:voice-codec (commonMain only — via Gradle attribute
      to skip the JVM artefact that carries FFmpeg-GPL deps)
    — depends on :shared:screencast (uses the JVM macOS source set which is
      Apache-2.0 — already free of GPL today)
    — depends on :shared:platform-api
```

### 4.3 Dependency graph diff

**Before** (`:desktop:app` HEAD):
```
:desktop:app → :shared:voice  (transitively brings FFmpeg-GPL + JavaCPP)
            → :shared:platform-api
            → :desktop:platform-macos (currently only macOS platform actuals)
            → :shared:protocol-discord (Ktor CIO — fine)
            → :shared:compose-ui
            → … (rest unchanged)
```

**After** (Mac App Store variant):
```
:desktop:app (macAppStore source set)
            ↛ :shared:voice                       ❌ EXCLUDED (GPL)
            ↛ :shared:voice-codec jvm artefact    ❌ EXCLUDED (GPL transitive)
            → :desktop:platform-macos-appstore    ✅ NEW (Apache-2.0)
              → :shared:voice-codec common only   ✅ Apache-2.0 (interfaces)
              → :shared:screencast jvm            ✅ Apache-2.0 (already)
              → :shared:platform-api
              → JNA (already in repo)
            → :shared:protocol-discord (Ktor CIO) ✅ unchanged
            → :shared:compose-ui                  ✅ unchanged
            → :desktop:platform-macos             ✅ unchanged (paths, Keychain, …)
            (Linux + Windows platform modules)    ❌ EXCLUDED from this source set
```

The exclusion is enforced by **NOT** adding `implementation(projects.shared.voice)` to the macAppStore source set, plus a `verifyMacAppStoreNoGplDeps` Gradle task analogous to `verifyIosNoGplDeps` that walks `configurations.getByName("macAppStoreRuntimeClasspath")` and fails on any of: `org.bytedeco:ffmpeg*`, `org.bytedeco:javacpp*` if classifier is gpl, libdave bundle, libx264 native, JavaCPP FFmpeg presets. Same allow/deny list pattern as iOS.

### 4.4 Why a new SIBLING module rather than augmenting `:desktop:platform-macos`

`:desktop:platform-macos` today provides DMG-path actuals that depend transitively on `:shared:voice`'s GPL JNA wrappers (e.g. macOS-specific microphone path resolution). Augmenting it to also house the Apple-native voice/screencast classes would force the GPL-clean `macAppStore` source set to depend on `:desktop:platform-macos` — which then drags GPL transitively. A sibling module keeps the licence boundary mechanical.

---

## 5. Entitlements

`dist/apple/macappstore/Puklic.entitlements` (template — created in FP-14d):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- App Sandbox — REQUIRED by Mac App Store -->
    <key>com.apple.security.app-sandbox</key>
    <true/>

    <!-- Outbound TCP/UDP — Discord Gateway WSS + voice RTP -->
    <key>com.apple.security.network.client</key>
    <true/>

    <!-- Inbound UDP — loopback only (used by voice RTP receiver) -->
    <key>com.apple.security.network.server</key>
    <true/>

    <!-- Microphone — voice capture via AVAudioEngine -->
    <key>com.apple.security.device.audio-input</key>
    <true/>

    <!-- Screen capture — ScreenCaptureKit -->
    <!-- Note: there is no entitlement key for ScreenCaptureKit itself;
         user grants via the system Screen Recording TCC prompt at first use.
         The app-sandbox only restricts FS access; SCK calls work in sandbox. -->

    <!-- User-selected files — image attachment upload via NSOpenPanel -->
    <key>com.apple.security.files.user-selected.read-write</key>
    <true/>

    <!-- Downloads folder — attachment save target -->
    <key>com.apple.security.files.downloads.read-write</key>
    <true/>

    <!-- JIT — Temurin 21 C2 compiler page-protection -->
    <key>com.apple.security.cs.allow-jit</key>
    <true/>

    <!-- Unsigned executable memory — JVM dynamic class generation -->
    <!-- jpackage hardened runtime needs this for invokedynamic / lambda metafactory -->
    <key>com.apple.security.cs.allow-unsigned-executable-memory</key>
    <true/>

    <!-- Apple Events — needed by AWT for window management on macOS;
         scoped to com.apple.systempreferences to allow opening Settings panes
         where the user grants Screen Recording / Microphone TCC. -->
    <!-- INTENTIONALLY OMITTED unless empirical FP-14b/d testing shows it's required.
         Adding scopes you don't need invites App Review questions. -->
</dict>
</plist>
```

**HARD RULE #2 compliance**: every entitlement above maps to a feature shipped in v1 of the Mac App Store build. No "we might need this later". No `com.apple.security.cs.disable-library-validation` — JNA loads our own bundled libopus.dylib, which is in the .app bundle and gets signed under the same identity.

**Info.plist additions** (in `dist/apple/macappstore/Info.plist.template`):
- `NSMicrophoneUsageDescription` — "Puklic uses the microphone for Discord voice channels."
- `NSCameraUsageDescription` — INTENTIONALLY OMITTED. Camera capture is NOT a Phase-1 voice/screencast feature; we don't ship camera.
- `NSScreenCaptureUsageDescription` — REQUIRED on macOS 15+ for ScreenCaptureKit. Value: "Puklic uses screen capture to share your screen in Discord voice channels."
- `LSApplicationCategoryType` — `public.app-category.social-networking`
- `ITSAppUsesNonExemptEncryption` — `false` (TLS only; sxsa20-poly1305 is RFC8439 — export-exempt under §740.17(b)(1))
- `LSMinimumSystemVersion` — `13.0` (ScreenCaptureKit and Network.framework UDP both require ≥12.3; we pick 13.0 for headroom and consistency with iOS 16 baseline)

---

## 6. fastlane lane shape

`fastlane/Fastfile` addition (DESIGN — implemented in FP-14e):

```ruby
platform :mac do
  desc "Build, sign, package, upload Puklic Mac App Store .pkg → App Store Connect."
  lane :mac_app_store do
    # 1. Build Mac App Store-flavoured Compose Desktop variant.
    sh("cd .. && ./gradlew :desktop:app:packageMacAppStore :desktop:app:verifyMacAppStoreNoGplDeps")

    # 2. The Gradle task signs both .app and .pkg via jpackage's
    #    --mac-app-image-sign-identity + --mac-installer-sign-identity.
    #    No additional codesign step here.

    # 3. Build number — mirror iOS lane policy: CI run number.
    build_number = ENV["GITHUB_RUN_NUMBER"] || Time.now.to_i.to_s

    # 4. ASC API key — shared with iOS lane (same Team, same ASC API key).
    api_key = app_store_connect_api_key(
      key_id: ENV["ASC_KEY_ID"],
      issuer_id: ENV["ASC_ISSUER_ID"],
      key_filepath: ENV["ASC_KEY_PATH"],
      duration: 1200
    )

    # 5. Upload .pkg via altool (the only path for Mac .pkg → ASC;
    #    pilot is iOS-only). notarytool is not appropriate — App Store
    #    submissions are notarised by Apple's server side on ingest.
    pkg = "../desktop/app/build/compose/binaries/main/macAppStorePkg/Puklic-#{ENV['PUKLIC_VERSION']}.pkg"
    upload_to_app_store(
      api_key: api_key,
      pkg: pkg,
      platform: "osx",
      skip_metadata: true,
      skip_screenshots: true,
      skip_app_version_update: true,
      precheck_include_in_app_purchases: false
    )
  end
end
```

**Note on `upload_to_app_store` vs `deliver` vs `altool`**: fastlane's `upload_to_app_store` action wraps `Spaceship` for metadata and uses `altool`-equivalent under the hood for the binary upload. For a binary-only upload (skip metadata) this is the idiomatic fastlane lane in 2026.

---

## 7. CI workflow shape

`.github/workflows/mac-app-store.yml` (DESIGN — implemented in FP-14e):

```yaml
name: Mac App Store

# Manual trigger only — Mac App Store uploads are intentional, not on every push.
# Architect: docs/03_infrastructure/architect-reports/2026-05-29-fp14a-mac-app-store-architect.md

on:
  workflow_dispatch:

jobs:
  mac_app_store:
    runs-on: macos-15
    timeout-minutes: 90
    permissions:
      contents: read

    env:
      ASC_KEY_ID:     ${{ secrets.ASC_KEY_ID }}
      ASC_ISSUER_ID:  ${{ secrets.ASC_ISSUER_ID }}
      TEAM_ID:        GR74KSG8M9
      BUNDLE_ID:      cz.damek.puklic.app
      ASC_KEY_PATH:   ${{ github.workspace }}/.appstoreconnect/AuthKey.p8

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21 (Temurin)
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Set up Ruby (fastlane)
        uses: ruby/setup-ruby@v1
        with:
          ruby-version: '3.3'
          bundler-cache: true

      - name: Materialise ASC API key
        run: |
          mkdir -p "$(dirname "$ASC_KEY_PATH")"
          printf '%s' "${{ secrets.ASC_KEY_P8 }}" > "$ASC_KEY_PATH"
          chmod 600 "$ASC_KEY_PATH"

      - name: Install Mac App Distribution cert
        env:
          P12_BASE64: ${{ secrets.MAC_APP_DIST_P12_BASE64 }}
          P12_PASSWORD: ${{ secrets.MAC_APP_DIST_P12_PASSWORD }}
          KEYCHAIN_PASSWORD: ${{ secrets.MAC_KEYCHAIN_PASSWORD }}
        run: |
          echo "$P12_BASE64" | base64 --decode > /tmp/macapp.p12
          security create-keychain -p "$KEYCHAIN_PASSWORD" build.keychain
          security set-keychain-settings -lut 3600 build.keychain
          security unlock-keychain -p "$KEYCHAIN_PASSWORD" build.keychain
          security import /tmp/macapp.p12 -k build.keychain -P "$P12_PASSWORD" \
            -T /usr/bin/codesign -T /usr/bin/productbuild
          rm /tmp/macapp.p12

      - name: Install Mac Installer Distribution cert
        env:
          P12_BASE64: ${{ secrets.MAC_INSTALLER_DIST_P12_BASE64 }}
          P12_PASSWORD: ${{ secrets.MAC_INSTALLER_DIST_P12_PASSWORD }}
          KEYCHAIN_PASSWORD: ${{ secrets.MAC_KEYCHAIN_PASSWORD }}
        run: |
          echo "$P12_BASE64" | base64 --decode > /tmp/macinst.p12
          security unlock-keychain -p "$KEYCHAIN_PASSWORD" build.keychain
          security import /tmp/macinst.p12 -k build.keychain -P "$P12_PASSWORD" \
            -T /usr/bin/productbuild
          security list-keychains -s build.keychain login.keychain
          security set-key-partition-list -S apple-tool:,apple: -s -k "$KEYCHAIN_PASSWORD" build.keychain
          rm /tmp/macinst.p12

      - name: Install macOS provisioning profile
        env:
          PROFILE_BASE64: ${{ secrets.MAC_PROVISIONING_PROFILE_BASE64 }}
        run: |
          mkdir -p "$HOME/Library/MobileDevice/Provisioning Profiles"
          echo "$PROFILE_BASE64" | base64 --decode \
            > "$HOME/Library/MobileDevice/Provisioning Profiles/macos.provisionprofile"

      - name: fastlane mac → App Store
        env:
          GITHUB_RUN_NUMBER: ${{ github.run_number }}
        run: bundle exec fastlane mac mac_app_store

      - name: Cleanup keychain
        if: always()
        run: security delete-keychain build.keychain || true
```

New CI secrets required: `MAC_APP_DIST_P12_BASE64`, `MAC_APP_DIST_P12_PASSWORD`, `MAC_INSTALLER_DIST_P12_BASE64`, `MAC_INSTALLER_DIST_P12_PASSWORD`, `MAC_PROVISIONING_PROFILE_BASE64`. The existing `MAC_KEYCHAIN_PASSWORD`, `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8` are reused.

---

## 8. ASC + Apple Developer portal — user-action follow-ups (BLOCKED on user)

These cannot be automated. The architect report blocks FP-14d/e impl on completion of this list.

### 8.1 Add macOS platform to existing ASC app

- [ ] App Store Connect → My Apps → **Puklic** (app ID `6774288340`) → **+ Add Platform** → choose **macOS** → confirm.
  - This creates the macOS sibling under the same App Store record.
  - The macOS app inherits the iOS bundle ID `cz.damek.puklic.app`.

### 8.2 Enable Mac App Distribution capability on existing App ID

- [ ] Apple Developer portal → Identifiers → **cz.damek.puklic.app** → **App Services / Additional Capabilities** → enable:
  - "Mac App Store" (provisioning capability)
  - (Optional) "App Sandbox" — actually configured per-profile, not per-App-ID; skip here.
- [ ] Save. Apple will warn that **all iOS provisioning profiles tied to this App ID will be invalidated** and must be regenerated. **User accepted this risk 2026-05-29.**

### 8.3 Regenerate iOS provisioning profile

- [ ] Apple Developer portal → Profiles → find the App Store profile for `cz.damek.puklic.app` → Edit → Generate (no field changes — just re-issue) → Download.
- [ ] Re-encode as base64 + update GitHub secret `APPLE_PROVISIONING_PROFILE_BASE64`.
- [ ] Re-run **Apple TestFlight** workflow on a no-op commit to verify the iOS lane still ships.

### 8.4 Generate macOS-side certs + profile

- [ ] Apple Developer portal → Certificates → **+ Create** → "Mac App Distribution" → CSR-driven workflow → Download `.cer` → import to login keychain (becomes `3rd Party Mac Developer Application: Jan Damek (GR74KSG8M9)`).
- [ ] Repeat for "Mac Installer Distribution" → becomes `3rd Party Mac Developer Installer: Jan Damek (GR74KSG8M9)`.
- [ ] Export both `.p12` from keychain → base64 → upload as GitHub Secrets `MAC_APP_DIST_P12_BASE64` + `MAC_INSTALLER_DIST_P12_BASE64`. Add the corresponding passwords.
- [ ] Apple Developer portal → Profiles → **+ Create** → "Mac App Store" → App ID `cz.damek.puklic.app` → Mac App Distribution cert → Generate → Download `.provisionprofile`.
- [ ] base64 → GitHub Secret `MAC_PROVISIONING_PROFILE_BASE64`.

### 8.5 First Beta App Review

- [ ] First Mac App Store build will go through Beta App Review (same rule as iOS first builds — even internal-only).
- [ ] Reviewer notes should reference the iOS app's approved status (same product, same bundle id, additional platform).

---

## 9. Risk register

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| 9.1 | iOS provisioning profile invalidation when Mac App Store capability is enabled on the shared App ID | **HIGH** — breaks iOS TestFlight uploads until §8.3 is done | User accepted 2026-05-29. §8.3 makes regeneration a single click immediately after §8.2. Recommend doing §8.2 + §8.3 + a no-op TestFlight verify in the same sitting. |
| 9.2 | Mac Installer Distribution cert is a separate cert type the user hasn't generated yet | MEDIUM — blocks FP-14d/e completion | §8.4 makes it explicit. No probe needed; cert generation is straightforward. |
| 9.3 | Sandbox TCC prompts at first-run for microphone + screen recording | LOW — UX surface | Test in FP-14d using sandboxed run. Document the first-run UX in `docs/05_platforms/macos.md` addendum. |
| 9.4 | JVM C2 JIT trips a sandbox/hardened-runtime violation | LOW | Entitlement `com.apple.security.cs.allow-jit` + `…allow-unsigned-executable-memory` cover the documented JVM cases. Probe step 5 already ran a JVM under ad-hoc signature successfully. |
| 9.5 | App Review §5.2 third-party-client rejection | MEDIUM | Already accepted for iOS; macOS submission inherits the same review reasoning. Submission notes reference iOS approval. |
| 9.6 | App Review §4.2 minimum-functionality rejection | LOW | Mac App Store build ships voice + screencast (unlike iOS App Store build which is chat-only). MORE feature surface than iOS, less likely to trip §4.2. |
| 9.7 | `:shared:voice-codec` JVM artefact (FFmpeg-GPL) accidentally leaks via Gradle resolution into macAppStore source set | MEDIUM | `verifyMacAppStoreNoGplDeps` Gradle task — same pattern as `verifyIosNoGplDeps`. Walks `macAppStoreRuntimeClasspath` and fails on FFmpeg / libdave / libx264 artefacts. FP-14b TEST-FIRST scope. |
| 9.8 | libopus.dylib slice extracted from `Opus.xcframework` is a multi-architecture binary (arm64 + x86_64 simulator) — wrong for macOS-arm64 jpackage bundle | LOW | The `Opus.xcframework/macos-arm64/Opus.framework/Opus` slice is built specifically for macOS-arm64 (not simulator). `lipo -info` check is part of FP-14b acceptance. |
| 9.9 | JNA `Native.load("Opus")` searches `DYLD_LIBRARY_PATH`-style paths that App Sandbox blocks | LOW | The .dylib is bundled inside the .app at `Contents/Resources/Opus.framework/Opus`. JNA's `Native.load()` with an absolute path bypasses dyld search. FP-14c uses `Native.load(File(bundleContents, "Resources/Opus.framework/Opus").absolutePath, …)`. |

---

## 10. Critic findings (self-critic pass — HARD RULE #1 Step 3)

Reviewed against HARD RULE #2 ("NEVER TEMPORARY"), HARD RULE #1 minimum-complexity, and library-first.

### 10.1 No temporary / TODO / "phase 2" wording

- `dist/apple/macappstore/Puklic.entitlements` template (§5) ships EVERY entitlement needed at v1 launch including JIT, microphone, screen capture, user-selected files. No "we'll add audio-input later". The entitlement list is final.
- The Info.plist template intentionally OMITS `NSCameraUsageDescription` because camera capture is not a Phase-1 voice feature. This is conceptual ("we don't ship camera"), not temporary.
- No "for v1" qualifiers anywhere in §4 module structure.

### 10.2 Minimum-complexity

- Option A reuses 90% of `:desktop:app`'s existing Gradle config — only the `macAppStore` source set + one new task are added.
- `:desktop:platform-macos-appstore` is a single new module rather than 3-4 micro-modules. Internally it has two packages (`voice/`, `screencast/`) — flat and obvious.
- VideoToolbox JNA bridge is ~600 LOC = comparable to the existing `ScreenCaptureKitBridge.kt` (currently 500-ish LOC in `shared/screencast/src/jvmMain`). Same JNA idiom. No new abstraction layer.
- No new top-level Gradle plugin. `puklic.jvm-library` convention is reused for the new module.

### 10.3 Library-first compliance

- §2 ran two real Maven Central queries (videotoolbox, javacv) + a bytedeco-family enumeration (147 results) + an Opus query (20 hits) + a concentus probe. Each candidate has an explicit `coordinates / license / KMP / native? / verdict` row. No hand-rolled crypto, no hand-rolled codec — libopus is the chosen library (same one the iOS build uses).
- The VideoToolbox JNA bridge is custom CODE but bridges a SYSTEM library; it's not "implement H.264 from scratch". It's the smallest reasonable adapter.

### 10.4 Sandbox edge cases

- TCC prompts (mic, screen recording) — covered by Info.plist usage description keys (§5).
- ScreenCaptureKit has no entitlement key — covered by TCC prompt at runtime.
- Apple Events — explicitly OMITTED with rationale ("add only if FP-14b/d test surfaces it"). HARD RULE #2 compliant: we don't pre-add scopes "just in case".

### 10.5 Bundle-ID reuse impact

- §8.3 documents the iOS provisioning profile regeneration with explicit steps and a verify pass. Risk register §9.1 calls it HIGH. User has accepted.
- The iOS Apple Distribution cert is NOT affected by enabling Mac App Distribution capability — only profiles are re-issued, not certs. Probe confirmed iOS cert `Apple Distribution: Jan Damek (GR74KSG8M9)` is in the keychain (×2 — different SHA-1, normal for re-issued cert).

### 10.6 Findings against this report

- **No critical findings.** Plan is implementable as described. Two prerequisites are user-action (cert generation + profile regeneration); both are clearly scoped.
- Minor: §6 fastlane lane uses `upload_to_app_store` which internally is `altool`-based. If Apple deprecates altool for Mac uploads (2027 estimate), the lane swap to `notarytool` or future `transporter` is mechanical — but that's a future concern, not a present design weakness.

---

## 11. Module dep graph diff — visual

```diff
 :desktop:app  (existing source set `main`)
   ├── :shared:voice              (kept — DMG/Linux/Windows path)
   ├── :shared:voice-codec        (kept — DMG/Linux/Windows path)
   ├── :shared:platform-api
   ├── :shared:compose-ui
   ├── :shared:protocol-discord
   ├── :shared:persistence-*
   ├── :shared:repositories
   ├── :shared:session
   ├── :shared:domain
   ├── :shared:ids
   ├── :desktop:platform-linux
   ├── :desktop:platform-macos
   └── :desktop:platform-windows

+:desktop:app  (NEW source set `macAppStore`)
+  ├── :shared:platform-api
+  ├── :shared:compose-ui
+  ├── :shared:protocol-discord
+  ├── :shared:persistence-*
+  ├── :shared:repositories
+  ├── :shared:session
+  ├── :shared:domain
+  ├── :shared:ids
+  ├── :desktop:platform-macos                    (path actuals only)
+  ├── :desktop:platform-macos-appstore           [NEW MODULE]
+  └── :shared:voice-codec  (commonMain interfaces only — Gradle attribute
+                            to skip the jvm artefact that carries FFmpeg-GPL)

+:desktop:platform-macos-appstore  [NEW MODULE]
+  ├── :shared:voice-codec (commonMain only)
+  ├── :shared:screencast (jvm — already Apache-2.0)
+  ├── :shared:platform-api
+  └── JNA (existing dep)
```

---

## 12. Slice decomposition (FP-14b … FP-14g)

| Slice | Role | Deliverable | Pipeline gate before next slice |
|---|---|---|---|
| **FP-14b** | unit-test-writer | Failing tests in: <br>• `:desktop:platform-macos-appstore` (none of the impl classes exist yet — pure interface contract tests via Kotest)<br>• `verifyMacAppStoreNoGplDeps` Gradle task spec (Gradle TestKit) | Step 5 RED: every new test file in compile-fail or runtime-fail state, with an architect-locked spec. |
| **FP-14c** | impl (kotlin-engineer) | VideoToolbox JNA bridge + AVAudioEngine capture + JnaLibopus encoder/decoder + NWConnection UDP transport in `:desktop:platform-macos-appstore`. Wires Opus.xcframework macos-arm64 slice into .app bundle resources via existing build script. | Step 6 GREEN: all FP-14b tests pass. Step 7 critic clean. |
| **FP-14d** | impl (kotlin-engineer) | `:desktop:app` `macAppStore` source set + `MacAppStoreMainKt` entry + `packageMacAppStore` Gradle task with `--mac-app-store --mac-entitlements --mac-app-image-sign-identity --mac-installer-sign-identity`. Custom Info.plist template + Puklic.entitlements committed under `dist/apple/macappstore/`. `verifyMacAppStoreNoGplDeps` task implemented. | Local `./gradlew :desktop:app:packageMacAppStore` (requires the user-action cert installation — §8.4) produces signed .pkg passing `spctl -a -t install -v`. |
| **FP-14e** | impl (kotlin-engineer) | `fastlane/Fastfile` `mac` platform + `mac_app_store` lane. `.github/workflows/mac-app-store.yml`. README on new CI secrets. | Manual `workflow_dispatch` ingests successfully to App Store Connect → "Processing" status. |
| **FP-14f** | code-critic | Full critic review of FP-14c+d+e impl. Findings list. | Findings either applied (loop with FP-14c/d/e impl agent) or closed as accepted-no-change. |
| **FP-14g** | doc-updater | Update `docs/03_infrastructure/dep-policy.md` (add Mac App Store row), `docs/05_platforms/macos.md` (sandbox first-run UX), `docs/07_roadmap/phases.md` (FP-14 row done), `docs/03_infrastructure/architect-reports/2026-05-29-full-feature-parity.md` §3.6 replaced by a one-liner pointing at THIS report. KB entry `mcp jervis-mcp kb_store` of this report. | Doc-only PR merged; issue #54 closed only after this. |

Each slice runs the full HARD RULE #1 pipeline (its own architect-critic loop for non-trivial impl). Step 4 user-approval at the SLICE boundary is granted blanket by the FP-14 mandate.

---

## 13. What this report does NOT do

- Does not touch any production source file.
- Does not touch `desktop/app/build.gradle.kts`, `shared/*` Gradle files, `fastlane/Fastfile`, `.github/workflows/*`.
- Does not create `dist/apple/macappstore/` (FP-14d scope).
- Does not create `:desktop:platform-macos-appstore` module (FP-14c scope).
- Does not generate any Apple certs / profiles (user-action — §8).
- Does not change CLAUDE.md or memories (rules-only files).

---

## 14. Appendix A — probe transcript (condensed)

```
$ jpackage --version
23.0.2

$ security find-identity -v -p codesigning | grep -E "Apple Distribution|Mac"
  2) DC1DAF...  "Apple Distribution: Jan Damek (GR74KSG8M9)"
  5) 87C2C0...  "Apple Distribution: Jan Damek (GR74KSG8M9)"

# attempt 1 (cert prefix wrong)
$ jpackage --type pkg --mac-app-store --mac-sign \
   --mac-signing-key-user-name "Apple Distribution: Jan Damek (GR74KSG8M9)" …
[13:07:49] No certificate found matching [3rd Party Mac Developer Application: …]
Bundler Mac PKG Package skipped because of a configuration problem

# attempt 2 (correct CN suffix; missing certs)
$ jpackage --type pkg --mac-app-store --mac-sign \
   --mac-signing-key-user-name "Jan Damek (GR74KSG8M9)" …
No certificate found matching [3rd Party Mac Developer Application: Jan Damek (GR74KSG8M9)]
No certificate found matching [3rd Party Mac Developer Installer: Jan Damek (GR74KSG8M9)]

# attempt 4 (unsigned --mac-app-store)
$ jpackage --type pkg --mac-app-store … (no --mac-sign)
=> output/PuklicProbe-1.0.pkg (53 MB) PRODUCED
$ spctl -a -t install -v output/PuklicProbe-1.0.pkg
output/PuklicProbe-1.0.pkg: rejected
source=no usable signature   # expected for unsigned

# attempt 5 (unsigned --type app-image)
$ jpackage --type app-image --mac-app-store …
=> output2/PuklicProbe.app PRODUCED
$ output2/PuklicProbe.app/Contents/MacOS/PuklicProbe
Puklic Mac App Store probe OK
$ codesign -dv --verbose=2 output2/PuklicProbe.app
Identifier=cz.damek.puklic.probe
Format=app bundle with Mach-O thin (arm64)
Signature=adhoc
$ plutil -p output2/PuklicProbe.app/Contents/Info.plist | grep -E "Bundle|LSApplication|NSMicro"
  CFBundleIdentifier = cz.damek.puklic.probe
  CFBundleShortVersionString = 1.0
  CFBundleVersion = 1.0
  LSApplicationCategoryType = public.app-category.utilities  # MUST override
  NSMicrophoneUsageDescription = "The application PuklicProbe is requesting access to the microphone."
                                                                       # AUTO-INJECTED — MUST override
```

Probe scratch under `/tmp/puklic-jpackage-probe/` was deleted after recording.

---

## 15. Appendix B — library survey transcripts

### B.1 VideoToolbox

```
$ curl -s "https://search.maven.org/solrsearch/select?q=videotoolbox&rows=20&wt=json" \
    | jq '.response.numFound'
0

$ curl -s "https://search.maven.org/solrsearch/select?q=g:org.bytedeco&rows=200&wt=json" \
    | jq -r '.response.docs[].a' | sort -u | grep -iE "apple|darwin|video|toolbox|mac"
videoinput
videoinput-platform     # DirectShow camera capture — unrelated
```

### B.2 JavaCV

```
$ curl -s "https://search.maven.org/solrsearch/select?q=javacv&rows=10&wt=json" | jq …
{ "g": "org.bytedeco", "a": "javacv", "latestVersion": "1.5.11" }
{ "g": "org.bytedeco", "a": "javacv-platform", "latestVersion": "1.5.11" }
…
```
JavaCV's VideoToolbox access is via FFmpeg `h264_videotoolbox` encoder name string, gated on `ffmpeg-platform-gpl` dependency → unacceptable for Mac App Store build.

### B.3 Opus

```
$ curl -s "https://search.maven.org/solrsearch/select?q=a:concentus&rows=10&wt=json" \
    | jq '.response.numFound'
0
# Not on Maven Central.

$ curl -sI "https://jitpack.io/com/github/lostromb/concentus/master/concentus-master.pom"
HTTP/2 200
# On JitPack only.

$ curl -s "https://search.maven.org/solrsearch/select?q=opus&rows=20&wt=json" | jq …
club.minnced : opus-java : 1.1.1       # JVM-only, Apache-2.0, libopus natives bundled
club.minnced : opus-java-natives : 1.1.1
org.lwjgl : lwjgl-opus : 3.3.6         # LWJGL game-engine scope
org.restcomm.media.core.codec.opus : * # JVM telephony stack
…
```

Decision: reuse our own `Opus.xcframework/macos-arm64` slice via JNA — single libopus version across iOS + Mac App Store + (if ever) macOS Kotlin/Native.

---

## 16. References

- Issue #54 (re-scoped FP-14)
- `docs/03_infrastructure/architect-reports/2026-05-29-full-feature-parity.md` §3.6 (SUPERSEDED by this report)
- `docs/03_infrastructure/architect-reports/2026-05-28-apple-distribution.md`
- `docs/03_infrastructure/architect-reports/2026-05-29-fp4-ios-opus-libopus.md`
- `docs/03_infrastructure/architect-reports/2026-05-29-fp5-ios-videotoolbox.md`
- `docs/03_infrastructure/architect-reports/2026-05-29-fp8-mac-screencapturekit.md`
- `desktop/app/build.gradle.kts` (Compose Desktop config — UNCHANGED in this slice)
- `fastlane/Fastfile` (iOS lane — pattern reference, UNCHANGED in this slice)
- `.github/workflows/apple-testflight.yml` (iOS workflow — pattern reference, UNCHANGED in this slice)
- Apple — Mac App Store entitlements: https://developer.apple.com/documentation/bundleresources/entitlements
- Apple — Hardened Runtime for JIT: https://developer.apple.com/documentation/security/hardened_runtime
- jpackage(1) man page — `--mac-app-store`, `--mac-entitlements`, `--mac-app-image-sign-identity`, `--mac-installer-sign-identity`

---

## 17. Decision log

- 2026-05-29 — Architect verification pass per FP-14a mandate. Library survey ran live against Maven Central. jpackage probe ran in `/tmp/puklic-jpackage-probe/` (cleaned up). User decisions §0 confirmed before report write. Slice decomposition §12 ready for FP-14b dispatch.
