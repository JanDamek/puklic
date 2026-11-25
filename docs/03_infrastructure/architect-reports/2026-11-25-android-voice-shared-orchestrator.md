# Android voice: shared DAVE-free orchestrator (PUKLIC-124)

Status: implemented 2026-11-25. UX approved by Jan (variant A).

## Decision

Android gets a real voice engine by extracting the call orchestration into commonMain of `:shared:voice-codec` (`dev.puklic.voice.client`) instead of writing a second Android-only client. `SharedVoiceClient` runs the whole call: Op 4 on the main gateway, voice gateway handshake, UDP IP discovery, protocol selection, capture and playback pipelines, per-hop `aead_xchacha20_poly1305_rtpsize`.

DAVE is not implemented: Identify announces `max_dave_protocol_version = 0` (new `maxDaveProtocolVersion` parameter of `DefaultVoiceGatewayConnection`), and a `SessionDescription` carrying a DAVE version above 0 fails the call explicitly instead of producing silent audio. This matches the DAVE strategy for App Store builds (2026-05-29); the same orchestrator can later back `AppleNativeVoiceClient` (FP-14h).

## Rejected alternatives

- Android-only client duplicating `DefaultVoiceClient`: two engines to keep in sync.
- Making `DefaultVoiceClient` itself common: it is coupled to DAVE (`wire-core-crypto`, GPL) and screen sharing; the desktop behaviour is left untouched.
- JNI libopus on Android: needs the NDK and per-ABI natives; Concentus (pure Java, BSD) is sufficient for 48 kHz mono voice.

## Structure

- `:shared:voice-api`: `MainGatewayBridge` (moved from `:shared:voice` jvmMain, same package `dev.puklic.voice`).
- `:shared:session`: `MainGatewayBridgeAdapter` (moved from `:desktop:app`), shared by desktop and Android.
- `:shared:voice-codec` commonMain: `SharedVoiceClient`, `VoiceHandshake`, `VoiceMediaSession`, `VoicePlatform`/`AudioEndpoints`/`VoiceSignalling`, `ParticipantTracker`, `IncomingCallQueue`, `ActiveSession`, `DeafenableAudioPlayback`.
- `:shared:voice-codec` `src/javaMain` (compiled into jvm and android): `JvmVoiceUdpTransportFactory` (`DatagramSocket`) and the BouncyCastle `xchacha20Poly1305` actual.
- `:shared:voice-codec` androidMain: `AndroidAudioCapture`, `AndroidAudioPlayback`, `listAudioDevices` (one default entry per direction), `OpusCodecFactory` over Concentus.
- `:android:platform`: `VoiceAudioRouting` (audio focus, `MODE_IN_COMMUNICATION`, headset or loudspeaker route) driven by `VoiceServiceController` with the foreground service.
- `:android:app`: `AndroidVoiceClientFactory` builds `SharedVoiceClient` behind `PermissionGatedVoiceClient`.

## UX (approved)

`VoiceDock` Android actual: one row at the bottom of the channel list, visible only while a voice session is active. Left: "channel · server" (DM calls show the peer name). Right: icon buttons mute microphone, deafen, disconnect. No screen-share button, no DAVE indicator. Connecting shows a spinner with a cancel button; failures show a dismissable error row.

## Limits

No DAVE (Discord falls back to transport encryption only), no screen sharing or incoming video, no per-device selection (the system communication route is used), Concentus is slower than native libopus (acceptable for one mono stream).

## Verification

Only the JVM side compiles in the build pod (no Android SDK, no Xcode). Android sources are verified by Jenkins `PUKLIC/build-android` (MODE=build).
