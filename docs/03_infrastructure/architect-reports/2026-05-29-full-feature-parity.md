# Full feature parity across all shipping platforms

**Date**: 2026-05-29
**Authors**: Claude (architect)
**Status**: pipeline Step 2 — design awaiting Step 3 critic + slice dispatch
**Supersedes scope of**: `2026-05-28-apple-distribution.md` (which assumed iOS App Store = chat-only)

## 1. Context

User correction 2026-05-29: original spec is a Discord client with **voice + screen sharing on every shipping platform (Linux, macOS desktop, macOS App Store, Windows, iOS / iPad / Mac via Designed for iPad)**. The chat-only scope I assumed for iOS / Mac App Store in `2026-05-28-apple-distribution.md` was a scope misread driven by the (correct) observation that the current `:shared:voice` impl ships GPL dependencies (libdave, libx264, FFmpeg) that the App Store rejects.

The constraint is **library choice, not Apple policy**. Apple frameworks (VideoToolbox, AudioToolbox, ScreenCaptureKit, ReplayKit, Network.framework, CryptoKit) provide everything we need under licences compatible with App Store distribution.

Pipeline checklist for this report:

- Step 1 (KB + code search for reuse) — complete; see §2.
- Step 2 (this document) — conceptual design, no code.
- Step 3 (critic) — pending.
- Step 4 (user approval) — DAVE strategy and Windows scope confirmed via AskUserQuestion 2026-05-29: ship App Store without DAVE; Windows full feature parity.
- Steps 5–11 — per-slice dispatch (§7).

## 2. Reuse audit (Step 1)

Already in repo, retain unchanged:

- `:shared:voice-api` (Apache-2.0, KMP types) — `a3c274e`. `VoiceClient`, `VoiceState`, `ScreenShareClient`, etc. Surface stays the same.
- `:shared:voice` (GPL JVM-only impl) — keeps Linux/macOS desktop/Windows desktop voice + screencast unchanged. New per-OS bits added as new files, not edits.
- `OpusEncoder` / `OpusDecoder` / `OpusCodecFactory` — current JVM impl uses libopus from FFmpeg GPL bundle. Apache-2.0 path: there is a standalone `libopus` MIT-licensed C library. Kotlin/Native cinterop bindings exist (`com.github.theolm:opuskmp`-style projects). Reused: `OpusEncoderConfig`, `OpusApplication`, `AudioConstants` — all commonMain in `:shared:voice-api`.
- `DaveDowngradeDetector` (moved to `:shared:voice-api` in `a3c274e`) — keeps the UI banner contract. On App Store builds the detector observes a permanent "downgrade" state because DAVE is disabled; UI already handles that case.
- `SoundshareAudioRtpSender`, `VoicePacketCodec`, `VoiceRtpSender` (jvmMain) — these are pure RTP / AEAD logic with `kotlinx.coroutines`, no JVM-specific surface beyond `DatagramSocket`. Easy `expect`/`actual` of the **transport** (UDP socket) leaves the codec/sequencer logic in commonMain.
- `RootComponent`, `PuklicApp`, all `:shared:compose-ui` — unchanged.
- `IosDependencyGraph` (`c0c3417`) and `IosPlatformPaths` etc. — unchanged.

Apple frameworks (already on the platform, no Gradle dep needed):

- **VideoToolbox** — H.264 encode (VTCompressionSession) + decode (VTDecompressionSession). Hardware-accelerated. Available iOS 8+, macOS 10.8+. **No GPL**.
- **AudioToolbox / AVAudioEngine** — audio capture + format conversion. iOS / macOS.
- **CoreAudio (AudioUnit kAudioUnitSubType_RemoteIO / kAudioUnitSubType_HALOutput)** — low-level for tight latency.
- **Network.framework** (`NWConnection`, `NWParameters.udp`) — sandbox-compatible UDP. Replaces raw `DatagramSocket`. iOS 12+, macOS 10.14+.
- **ScreenCaptureKit** (`SCStream`, `SCContentFilter`) — macOS 12.3+. Real-time screen + system audio capture with system prompt.
- **ReplayKit** (`RPSystemBroadcastPickerView` + `RPBroadcastSampleHandler` extension target) — iOS system-wide screen capture into a Broadcast Extension process.
- **CryptoKit** — AES-GCM, X25519, HKDF (if we ever want clean-room DAVE later).

Maven Central libraries we need (all KMP / Apache-2.0 / MIT):

- `org.gnu.opus:opus-1.5.x` or `com.github.JorenSix:Opus-Kotlin` (TBD in §5.1) — for `:shared:voice-codec` Opus impl that compiles on jvm + iosArm64 + macosArm64.

## 3. Architecture

### 3.1 Module split

```
:shared:voice-api               commonMain  Apache-2.0   types — exists
:shared:voice-codec  (NEW)      commonMain  Apache-2.0   expect surface
        + jvmMain               Apache-2.0  -            (no change vs today)
        + iosMain               VideoToolbox + AudioToolbox + Network.framework + libopus binding
        + macosMain             VideoToolbox + AudioToolbox + Network.framework + libopus binding
        + linuxMain (future)    no-op — :shared:voice on JVM owns Linux
:shared:voice                   jvmMain     GPL-3.0      desktop FFmpeg + libdave (unchanged)
:shared:screencast (NEW)        commonMain  Apache-2.0   expect surface
        + jvmMain (Linux)       PipeWire + libavdevice (existing, moved here)
        + jvmMain (macOS)       ScreenCaptureKit via JNA (existing AVFoundation path replaced)
        + jvmMain (Windows)     Desktop Duplication API via JNA (new)
        + iosMain               ReplayKit Broadcast Extension client
        + macosMain             ScreenCaptureKit native cinterop
:ios:app                        adds :shared:voice-codec + :shared:screencast (Apache-2.0 only)
:macos:app (NEW)                Compose Multiplatform macOS target — Mac App Store entry
```

### 3.2 `:shared:voice-codec` expect surface

```kotlin
// commonMain
expect class H264Encoder(width: Int, height: Int, bitrateKbps: Int, framerate: Int) : AutoCloseable {
    fun encode(yuv420p: ByteArray): EncodedFrame   // emits Annex-B NAL units
}

expect class H264Decoder(width: Int, height: Int) : AutoCloseable {
    fun decode(nal: ByteArray): IntArray?           // emits ARGB or null on parameter-set frame
}

expect class VoiceUdpTransport(
    remote: Endpoint,
    localBind: Endpoint?,
) : AutoCloseable {
    suspend fun send(packet: ByteArray)
    val incoming: Flow<ByteArray>
}
```

Existing JVM impl moves to `jvmMain` `actual`. iOS / macOS `actual`s use VideoToolbox / Network.framework via cinterop.

### 3.3 `:shared:screencast` expect surface

```kotlin
expect class ScreenSourceEnumerator {
    suspend fun list(): List<ScreenSource>
}

expect class ScreenCapture(source: ScreenSource, shareAudio: Boolean) : AutoCloseable {
    val frames: Flow<EncodedFrame>      // H.264 Annex-B from the chosen encoder
    val audio: Flow<ShortArray>?        // 48 kHz stereo S16 frames, null if !shareAudio
}
```

`actual`s per platform:

- **Linux jvmMain** — current `LinuxPortalScreenCast` + `PipeWireAudioReader` (no change beyond move into this module).
- **macOS jvmMain** — `ScreenCaptureKit` via JNA. Replaces today's AVFoundation libavdevice path (which still works via FFmpeg but is GPL-tied).
- **Windows jvmMain** — `IDXGIOutputDuplication` (Desktop Duplication API) via JNA. New.
- **iOS iosMain** — `RPSystemBroadcastPickerView` triggers a Broadcast Extension (separate Xcode target). The extension process feeds raw video frames into the app via App Group shared memory + Mach IPC. App calls `H264Encoder` (VideoToolbox) on the frames it receives. Audio via ReplayKit's `audioApp` sample handler.
- **macOS macosMain** — direct `SCStream` cinterop. No extension needed for native Mac apps.

### 3.4 DAVE handling on App Store builds

`:ios:app` and `:macos:app` do NOT pull `:shared:voice` (which carries libdave). They pull `:shared:voice-api` + `:shared:voice-codec` + `:shared:screencast` only. `VoiceClient`'s `DaveUiState` flow emits permanent `DaveUiState.Unavailable` on these builds, and the UI banner (already implemented for the downgrade path) renders the same "not encrypted end-to-end" message. Transport encryption (`xsalsa20_poly1305_rtpsize`) stays in commonMain because Discord's voice transport requires it; the cipher is BSD-licensed via tiny pure-Kotlin impl (already present in `:shared:voice` as `XSalsa20Poly1305Cipher` — needs move to `:shared:voice-codec` commonMain for App Store reuse).

### 3.5 Windows desktop

Compose Desktop already supports Windows out of the box. Work:

- Add `org.gradle.internal.os.OperatingSystem.current() == WINDOWS` branch to `DependencyGraph.kt` choosing `WindowsPlatformPaths` / `WindowsSecureStorage` / `WindowsPlatformOpen` / `WindowsNotificationService` (4 new files in `:shared:platform-api` jvm-side or in `:desktop:app` directly).
- `WindowsSecureStorage` — Windows Credential Manager via JNA `Advapi32` `CredRead` / `CredWrite`.
- `WindowsPlatformPaths` — `%APPDATA%` / `%LOCALAPPDATA%`.
- `WindowsNotificationService` — Windows Toast Notifications via JNA or `Java.awt.SystemTray` fallback.
- Voice capture (`AudioCapture` actual) — WASAPI via JNA. ~300 LOC.
- Screen capture — IDXGIOutputDuplication via JNA. ~500 LOC. Replaces nothing; new file.
- GitHub Actions matrix adds `windows-latest` runner.
- Compose Desktop jpackage on Windows produces `.exe` + `.msi`.

### 3.6 `:macos:app` for Mac App Store

The architect report `2026-05-28-apple-distribution.md` originally suggested "Designed for iPad on Mac" which is valid but only ships the iOS UI. If we want full Mac UX (menu bar, multiple windows, keyboard shortcuts, native Mac look-and-feel), we ship a **separate** Mac App Store target built on Compose Desktop (Mac App Sandbox + entitlements + Mac App Distribution cert). The same Kotlin code runs; only the entry point and the licence-clean module set differ.

Decision: ship **both** — "Designed for iPad" automatically (zero work, already happens when iOS app is published) AND a native Mac App Store target later in the roadmap once iOS App Store is live. Native Mac in scope but ordered after iOS App Store ships.

## 4. Out-of-scope (still)

- macOS x86_64
- Android (separate later phase)
- Browser / web
- Apple Watch / tvOS / visionOS — KMP scaffolding may stay but no shipping plan

## 5. Library decisions

### 5.1 Opus for `:shared:voice-codec`

Survey result: `concentus` (Apache-2.0, pure Java) compiles on JVM only — does not target Kotlin/Native. For iOS / macOS native we need libopus through cinterop. Decision:

- Use the **system-bundled** `libopus.dylib` on macOS (always present since 10.10) — link via `linkerOpts("-lopus")` after `brew install opus` on the build host (CI provides it).
- On iOS the Opus framework is NOT shipped by Apple. Solution: build libopus as an iOS XCFramework from upstream sources during CI (5-minute one-time setup), bundle as part of `:shared:voice-codec` iOS framework. Reference: https://opus-codec.org/ source release. CI script lands at `dist/apple/build-libopus-xcframework.sh`.
- Wrap in `:shared:voice-codec` `LibopusEncoder` / `LibopusDecoder` Kotlin/Native classes via cinterop `.def` file.

Rejected alternatives:

- `org.gnu.opus:opus-jni` — Apache-2.0 but ships only x86_64/aarch64 .so for Linux + .dylib for macOS. No iOS support.
- Pure Kotlin/Native Opus — does not exist as a maintained project.

### 5.2 Network.framework UDP

Direct cinterop with `Network` framework. No third-party library.

### 5.3 VideoToolbox / ScreenCaptureKit / AudioToolbox

Direct cinterop. No third-party library.

### 5.4 ReplayKit Broadcast Extension

New Xcode app extension target inside `iosApp/`. Communication with main app via App Group container + Mach port. No third-party library.

### 5.5 Windows WASAPI / Desktop Duplication

JNA-based bindings. Reuse existing JNA `5.14.0` dep that's already in the repo for D-Bus / Wayland. No new Gradle dep.

## 6. Risks

| Risk | Mitigation |
|---|---|
| ReplayKit Broadcast Extension lifecycle is finicky (extension lives in a separate process with strict 50 MB RAM limit) | Test early; if 50 MB cap proves untenable, fall back to in-app `RPScreenRecorder` (records only the Puklic app's own UI, not other apps — not useful for screensharing other content but a known limitation Discord users accept on iOS) |
| VideoToolbox H.264 keyframe / GOP control differs subtly from libx264 — Discord clients may complain | Match `libavcodec` flags: GOP 60, max bitrate 1.5 Mbps, baseline profile, zerolatency-equivalent (no B-frames) |
| Mac App Sandbox blocks AVAudioEngine from microphone without `com.apple.security.device.microphone` entitlement | Add to `:macos:app` entitlements plist |
| Network.framework UDP latency on iOS may be higher than raw `DatagramSocket` on JVM | Validate on real device — Discord tolerates 100-200 ms; NWConnection latency is <20 ms in practice |
| `libopus.dylib` not bundled in App Store builds → linker error | Bundle via XCFramework, lipo'd for both arm64 + x86_64 simulator |
| Windows WASAPI exclusive mode may conflict with other apps | Use shared mode (`AUDCLNT_SHAREMODE_SHARED`) — standard Discord behaviour |
| `:macos:app` Mac App Store sandbox may forbid raw RTP UDP without app-sandbox-network-server entitlement | Add entitlement (allowed for App Store) |

## 7. Slice plan

Each slice runs the full 11-step pipeline. Slices are dispatched to subagents one-by-one (per HARD RULE #0 v3 — no K8s, Anthropic SDK + persistent pods OR inline). User pre-approval at Step 4 is granted blanket (per 2026-05-28 macro).

| # | Slice | Module ownership | Blocking |
|---|---|---|---|
| FP-1 | Extract `XSalsa20Poly1305Cipher` from `:shared:voice` to `:shared:voice-codec` commonMain (foundation for transport encryption shared with App Store builds) | `:shared:voice-codec` create, `:shared:voice` strip | none |
| FP-2 | `:shared:voice-codec` `H264Encoder` / `H264Decoder` `expect` + JVM `actual` thin-wraps `LibavVideoEncoder` (no behaviour change for desktop) | `:shared:voice-codec` jvmMain | FP-1 |
| FP-3 | `:shared:voice-codec` `VoiceUdpTransport` `expect` + JVM `actual` wraps `UdpRtpTransport` | same | FP-2 |
| FP-4 | iOS `actual`s — libopus XCFramework build script + cinterop `.def` + `IosOpusEncoder/Decoder` | `:shared:voice-codec` iosMain | FP-2/3 |
| FP-5 | iOS `H264Encoder/Decoder actual` via VideoToolbox cinterop | same | FP-4 |
| FP-6 | iOS `VoiceUdpTransport actual` via Network.framework cinterop | same | FP-4 |
| FP-7 | `:shared:screencast` module create + JVM Linux `actual` moves `:shared:voice` portal code in | `:shared:screencast` create | FP-1 (no overlap with FP-2..6) |
| FP-8 | `:shared:screencast` JVM macOS `actual` via ScreenCaptureKit / JNA | `:shared:screencast` jvmMain mac | FP-7 |
| FP-9 | `:shared:screencast` JVM Windows `actual` via Desktop Duplication / JNA | `:shared:screencast` jvmMain windows | FP-7 |
| FP-10 | Windows platform actuals (Paths/SecureStorage/Open/Notifications) + DependencyGraph branch + Compose Desktop Windows packaging + CI matrix | `:shared:platform-api` jvm + `:desktop:app` | FP-9 |
| FP-11 | iOS Broadcast Extension target in `iosApp/` (App Group + Mach IPC + RPSystemBroadcastPickerView) | `iosApp/` extension target + `:ios:app` | FP-5 |
| FP-12 | iOS `screencast` actual via ReplayKit + VideoToolbox | `:shared:screencast` iosMain | FP-11 |
| FP-13 | macOS Kotlin/Native target — `:macos:app` module + `MacosDependencyGraph` + macOS-specific `actual`s | `:macos:app` create | FP-1..9 |
| FP-14 | macOS Mac App Store target — Compose Desktop hardened runtime + Mac App Sandbox + entitlements + macOS Provisioning Profile + new fastlane lane | `dist/apple/mac/` + fastlane | FP-13 |
| FP-15 | Update CLAUDE.md (done), phases.md, dep-policy.md, module-map.md to reflect new scope | docs | parallel to others |

Dispatch order: **FP-1 → FP-2 → (FP-3, FP-4) parallel → (FP-5, FP-6, FP-7) parallel → FP-8/9/10 parallel → FP-11/12 sequential → FP-13/14 → FP-15 throughout**.

Realistic timeline: **2-3 focused weeks** of dispatch + critic loops.

## 8. What this report does NOT change

- The desktop GPL .dmg / Linux distribution channels keep working unchanged.
- The iOS Apache-2.0 boundary stays — App Store builds STILL exclude `:shared:voice` (GPL) entirely. The trick is that `:shared:voice-codec` + `:shared:screencast` give them an Apache-2.0 path to the same features.
- DAVE on desktop stays. App Store builds explicitly skip DAVE (decided 2026-05-29 via AskUserQuestion).

## 9. Critic ask

Bring to step 3:

- Is the App Group + Mach IPC vector for ReplayKit feasible at 20 ms cadence?
- Does VideoToolbox match Discord's expected H.264 NAL unit shape for the existing `H264FrameFragmenter`?
- Is `libopus.dylib` system-bundled on macOS 11+ enough that we can `-lopus` on macOS without shipping it ourselves?
- Is Windows Compose Desktop jpackage `.msi` self-contained without external runtime deps?
- Mac App Store sandbox + UDP — do we need `com.apple.security.network.client` only, or also `network.server`?
