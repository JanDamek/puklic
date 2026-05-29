# Dependency policy

Single source of truth for which licences may appear in which Puklic build
target. Enforced mechanically by Gradle.

See also: `architect-reports/2026-05-28-apple-distribution.md` (Apple
distribution design — the rationale for the iOS / desktop split).

## Build-target licence matrix

| Target | Distribution | Permitted licences | Why |
|---|---|---|---|
| `:desktop:app` | GitHub Releases (.deb / .AppImage / AUR) | Apache-2.0, MIT, BSD, **GPL-3.0** | GPL-compatible distribution channel. Voice (FFmpeg-GPL, libdave, x264) lives here. |
| `:ios:app` | Apple App Store (TestFlight, internal) | Apache-2.0, MIT, BSD only | App Store Guideline 5.3 + Apple's incompatibility with GPL-3.0 anti-Tivoization clauses. Chat-only — no voice. |
| `:android:app` | (not yet shipping) | TBD when phase activates | — |

The iOS column is the load-bearing constraint: any GPL-3.0 transitive on the
iOS classpath would block App Store distribution.

## Shared-module licence matrix (KMP)

| Module | Linked into | Permitted licences | Notes |
|---|---|---|---|
| `:shared:voice-api` | desktop + iOS shared graph (`:shared:session`, `:shared:compose-ui`) | Apache-2.0 only | KMP-wide public voice/screenshare types (interfaces, sealed states, data classes, no-op clients, `DaveDowngradeDetector` policy). No native deps. Extraction rationale: `architect-reports/2026-05-28-voice-api-split.md`. |
| `:shared:voice-codec` | desktop today (via `:shared:voice` `api`); direct iOS / macOS consumers per FP-4..6 | Apache-2.0, **BSD-3-Clause** (iOS only) | KMP-wide Discord voice transport codec: `AeadCipher` interface, `NonceGenerator`, `RtpPacket`, `VoicePacketCodec`, `OpusCodecFactory` (FP-4). iOS `actual` links the prebuilt `libs/Opus.xcframework` (upstream libopus 1.5.2, BSD-3-Clause — App Store compatible). JVM `actual` keeps using FFmpeg-GPL JavaCPP for libopus (desktop only — never reaches the iOS classpath). Extraction rationale: `architect-reports/2026-05-29-fp1-voice-codec-extraction.md`, `architect-reports/2026-05-29-fp4-ios-opus-libopus.md`. |
| `:shared:screencast` | desktop today (via `:shared:voice` `api`); direct iOS / macOS / Windows consumers per FP-8 / FP-9 / FP-12 | Apache-2.0 (commonMain surface); **GPL-3.0** (jvmMain transitive: FFmpeg-GPL, libx264, libvpx, pipewire libavdevice demuxer, swresample) | KMP-wide screen capture contract: `ScreenCapture`, `ScreenSourceEnumerator`, `ScreenCaptureFactory`, `H264EncoderFactory` typealias (`dev.puklic.screencast`). Plus `VideoEncoder` / `VideoCodec` / `chooseCodec` (`dev.puklic.voice.screenshare.encoder`, package preserved across the move). JVM Linux actual: `LibavVideoEncoder`, `LinuxPortalScreenCast`, `PipeWireAudioReader`, `LinuxScreenSourceEnumerator` (jvmMain). The GPL transitive lives only in jvmMain and never reaches the iOS classpath — `verifyIosNoGplDeps` covers the guarantee mechanically because `:ios:app` does not depend on this module's jvm target. Extraction rationale: `architect-reports/2026-05-29-fp7-screencast-extraction.md`. |
| `:shared:voice` | `:desktop:app` only (JVM impl) | Apache-2.0, MIT, BSD, GPL-3.0 | JVM-only impl: `DefaultVoiceClient`, gateway, transport, crypto cipher (BouncyCastle XChaCha20-Poly1305), codec (Opus via FFmpeg-GPL), audio (JavaSound), screenshare encoders/sources, libdave bridge. Forbidden in `:ios:app` graph — already covered by `verifyIosNoGplDeps`. |
| `:shared:voice-dave` | `:shared:voice` only (JVM impl) | GPL-3.0 (Wire core-crypto) | DAVE MLS bridge. JVM-only. |

## Forbidden artefacts in `:ios:app`

Enforced by the `verifyIosNoGplDeps` Gradle task
(`ios/app/build.gradle.kts`). The matcher itself lives in
`build-logic/src/main/kotlin/IosGplChecker.kt` and is unit-tested in
`build-logic/src/test/kotlin/IosGplCheckerTest.kt`.

Case-insensitive substring match on `group:artifact`:

- `org.bytedeco:ffmpeg-platform-gpl` — FFmpeg multi-platform bundle, GPL variant
- `org.bytedeco:ffmpeg*` — any FFmpeg native classifier (catches the `-gpl` ones too)
- `org.bytedeco:javacpp*` — JavaCPP JNI bridge (transitive of ffmpeg-platform-gpl)
- `*libdave*` — DAVE MLS bridge (in-repo bundled today; Maven coord guarded for future)
- `net.java.dev.jna:jna*` — Apache-2.0 itself, but the libdave JNI bridge in this repo; chat-only build must not pull it
- `org.libx264:*` — x264 native bindings, GPL-2.0+
- `com.wire:core-crypto*` — Wire MLS lib, GPL-3.0 (used by `:shared:voice-dave`)

## Enforcement

Run on every `./gradlew :ios:app:check`. The task fails with the full list of
violating coordinates and a pointer to this file.

There is **no opt-out flag**. Per HARD RULE #2 (CLAUDE.md: never temporary)
the guard is absolute — if a legitimate Apache-2.0/MIT replacement is needed
for a previously-GPL component, swap the dep; don't bypass the check.

## When to update this file

- Adding a new build target → add a row to the matrix and decide the licence
  policy explicitly.
- Discovering a new GPL transitive that the matcher misses → add the
  coordinate to `FORBIDDEN_GPL_ARTIFACTS` in `IosGplChecker.kt` AND add a
  unit test AND mention it in the list above.
- Relaxing a forbidden entry (e.g. an artefact relicenced to Apache-2.0) →
  remove from the list, remove the test, document the relicence date in this
  file.
