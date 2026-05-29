# FP-14h-2a — implementation plan (impl-role architect note)

Scope per FP-14h-1-v2 §13 slice 2a only. The follow-up slice 2b owns
`UdpRtpTransport` deletion + caller rewire to `VoiceUdpTransport`.

## Goals (2a-only)

1. Introduce `@PuklicVoiceCodec` opt-in marker annotation in
   `:shared:voice-codec` commonMain (§2.3 of v2 design).
2. Promote `UdpRtpTransport` interface + `expect fun newUdpRtpTransport()`
   from `internal` to `public @PuklicVoiceCodec` (no move, no deletion,
   no caller rewire).
3. Split current `IpDiscovery.kt` (in `:shared:voice/commonMain`) so that
   only `IpDiscovery` object lives in a new file moved into
   `:shared:voice-codec/commonMain`. The legacy `UdpRtpTransport`
   interface + `expect` + `discoverIp` extension stay behind in
   `:shared:voice/commonMain` in a separate file named
   `UdpRtpTransport.kt` (renamed via `git mv` of the original file).
4. Move `JitterBuffer.kt` from `:shared:voice/jvmMain/.../pipeline/` to
   `:shared:voice-codec/commonMain/.../pipeline/`. Promote it to
   `public @PuklicVoiceCodec`.

## Exact paths

| Artefact | Path |
|---|---|
| `@PuklicVoiceCodec` annotation | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/PuklicVoiceCodec.kt` |
| `IpDiscovery` (after split + move) | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/IpDiscovery.kt` |
| Legacy `UdpRtpTransport` + `expect newUdpRtpTransport` + `discoverIp` ext | `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/UdpRtpTransport.kt` (origin `IpDiscovery.kt`, renamed via `git mv`) |
| Existing JVM actual `UdpRtpTransport.jvm.kt` | unchanged (stays in `:shared:voice/jvmMain`); only updates to use `public @PuklicVoiceCodec` types |
| `JitterBuffer` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/pipeline/JitterBuffer.kt` |

## Module dep + opt-in wiring

- `:shared:voice` already has `api(projects.shared.voiceCodec)` → the
  marker annotation is visible from `:shared:voice/commonMain` and
  `:shared:voice/jvmMain` automatically.
- `:shared:voice/build.gradle.kts` gets a `kotlin {
  sourceSets.all { languageSettings.optIn("dev.puklic.voice.codec.PuklicVoiceCodec") } }`
  block so commonMain/jvmMain/jvmTest sources accept the promoted-but-
  marked types without per-call-site `@OptIn` annotations.
- `:shared:voice-codec/build.gradle.kts` gets the same languageSettings
  block (so its own commonMain/jvmMain can reference the marker).
- `:desktop:app` does not currently import `UdpRtpTransport` or
  `JitterBuffer` directly (verified: only `:shared:voice` jvmMain +
  jvmTest does). No `:desktop:app` opt-in needed.
- `:ios:app` likewise does not import these legacy types; no change.

## Self-critic

- Will promoting `UdpRtpTransport` + `expect` to `public` break anything?
  No — widening visibility is non-breaking.
- Will opt-in cascade hit any other module? `:desktop:app` does NOT use
  `UdpRtpTransport`/`JitterBuffer`/`IpDiscovery` per grep audit. Only
  `:shared:voice` uses them, and we add the module-level opt-in there.
- Does `JitterBuffer` use any JVM-only API? Read confirms: pure
  Kotlin (`Int`/`Short`/`ByteArray`/companion). No `j.u.c.atomic`. No
  `System.nanoTime`. Safe to move to commonMain as-is.
- `IpDiscovery` object uses only `String.format(...)` which is
  KMP-supported (`kotlin.String.format` via stdlib on Native).
  Verified: `"0x%04x".format(...)` is available in commonMain.
- The original `IpDiscovery.kt` file imports `kotlinx.coroutines.withTimeout`
  only for the `discoverIp` extension. The split version (in voice-codec)
  containing only the `IpDiscovery` object will NOT need that import.
  The remaining `UdpRtpTransport.kt` file (in :shared:voice) keeps the
  `withTimeout` import for `discoverIp`.

## Out of scope (handed to 2b)

- Deletion of `UdpRtpTransport` interface / `expect newUdpRtpTransport` /
  jvm actual.
- Rewiring `DefaultVoiceClient`, `DefaultScreenShareClient`,
  `VoicePacketDispatcher`, `VideoRtpSender`, `SoundshareAudioRtpSender`,
  `PlaybackPipeline` from `UdpRtpTransport` to `VoiceUdpTransport`.
- Adding `discoverIp` extension on `VoiceUdpTransport`.
- Moving the 6 jvmTest files.
- atomicfu plugin, ConcurrentHashMap replacement, CryptoKit cinterop —
  all reserved for 2b / 2c / 2d slices.
