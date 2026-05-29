# FP-14h-1 — voice-gateway survey + `AppleNativeVoiceClient` composition

Architect-only sub-slice of FP-14h (#62), tracked as issue #63. Pipeline
Step 1 (architectural analysis) + Step 2 (conceptual design). READ-ONLY on
source / build / config. WRITE-ONLY this report.

This survey decides the conceptually-correct refactor that lets
`AppleNativeVoiceClient` work on iOS + Mac App Store builds **without any
temporary or stub state** (per HARD RULE #2). The output is a move map +
new-file map + slice decomposition for FP-14h-2..h-9.

---

## §1 Context

The FP-1..FP-12 cycle built KMP-clean Apple-native codec primitives:

- `:shared:voice-codec` commonMain — `VoicePacketCodec` (RTP + AEAD glue),
  `AeadCipher` interface, `NonceGenerator`, `RtpPacket`, `VoiceUdpTransport`
  interface, `OpusEncoder`/`Decoder` interface, `H264Encoder`/`Decoder`
  interface, `EncodedFrame`, `AnnexBSplitter`.
- `:shared:voice-codec` iosMain — `IosOpusCodec` (libopus 1.5.2 BSD-3),
  `IosH264Encoder`/`Decoder` (VideoToolbox), `IosVoiceUdpTransport`
  (Network.framework).
- `:desktop:platform-macos-appstore` jvmMain — `JnaLibopusEncoder`/`Decoder`,
  `JnaVideoToolboxH264Encoder`/`Decoder`, `JnaNwConnectionUdpTransport`.
- `:shared:screencast` iosMain (FP-12) — `IosScreenCapture` (ReplayKit).
- `:shared:screencast` jvmMain — `MacScreenCapture` (ScreenCaptureKit via JNA).
- `:shared:voice-api` commonMain — `VoiceClient` interface, `DaveUiState`,
  `DaveDowngradeDetector`, `NoOpVoiceClient`, `ScreenShareClient` interface,
  `NoOpScreenShareClient`.

But **no production code USES these primitives**. `IosDependencyGraph.kt:301`
and `MacAppStoreMain.kt:390` both wire `voiceClient = NoOpVoiceClient()`.
This contradicts the 2026-05-29 full-feature-parity decision and is a HARD
RULE #2 violation per FP-14f critic F-16.

The Apache-2.0 voice-gateway state machine (op 0 IDENTIFY through op 13
CLIENT_DISCONNECT) currently lives in `:shared:voice` commonMain but the
module itself is JVM-only (no `iosMain`, no KMP target declarations) — so
the gateway code is reachable on JVM only despite being syntactically
commonMain. iOS + Mac App Store builds therefore have no gateway today.

The mission of FP-14h-1 is to determine which files can move from
`:shared:voice` to `:shared:voice-codec` (KMP-clean, jvm + iosArm64 +
iosX64 + iosSimulatorArm64) without dragging GPL or JVM-only dependencies.

---

## §2 License + KMP-portability inventory

### 2.1 `:shared:voice` commonMain (13 files)

| File | License surface | KMP-portable | Imports of concern |
|---|---|---|---|
| `gateway/VoiceGatewayConnection.kt` | Apache-2.0 (kermit + kotlinx.coroutines + kotlinx.serialization) | YES — commonMain-eligible | none |
| `gateway/VoiceOp.kt` | Apache-2.0 (pure constants) | YES | none |
| `gateway/VoiceGatewayPayload.kt` | Apache-2.0 (kotlinx.serialization @Serializable) | YES | none |
| `gateway/VoiceGatewayTransport.kt` | Apache-2.0 (Flow interface) | YES | none |
| `transport/IpDiscovery.kt` | Apache-2.0 (pure bytes + coroutines withTimeout) | YES | none |
| `transport/Vp8Packetiser.kt` | Apache-2.0 | YES | none |
| `transport/H264Depacketizer.kt` | Apache-2.0 | YES | none |
| `transport/VideoFrameFragmenter.kt` | Apache-2.0 | YES | none |
| `transport/H264Fragmenter.kt` | Apache-2.0 | YES | none |
| `audio/AudioPlayback.kt` | Apache-2.0 (expect/actual contract) | YES (needs iOS actual) | `AudioDevice` |
| `audio/AudioCapture.kt` | Apache-2.0 (expect/actual contract) | YES (needs iOS actual) | `AudioDevice` |
| `pipeline/CapturePipeline.kt` | Apache-2.0 (capture → encode → send glue) | YES | `AudioCapture`, `OpusEncoder` |
| `screenshare/source/ScreenSourceEnumerator.kt` | Apache-2.0 (interface) | YES — already exists in `:shared:screencast` commonMain too | none |

All 13 are Apache-2.0 KMP-clean. The only blocker for actually compiling
them in iosMain is the **`internal` visibility** of every gateway type
(VoiceFrame, VoiceOp, VoiceGatewayState, VoiceGatewayEvent,
VoiceGatewayConnection, DefaultVoiceGatewayConnection,
VoiceGatewayTransport, VoiceFrameIn). After the move into
`:shared:voice-codec` they remain consumable by both `:shared:voice` (JVM)
and the new `:shared:voice-codec` iosMain Apple-native client — `internal`
in commonMain is visible to all platform source sets of the SAME module,
so this is conceptually clean.

### 2.2 `:shared:voice` jvmMain (19 files)

| File | License surface | Disposition |
|---|---|---|
| `gateway/KtorVoiceGatewayTransport.kt` | Apache-2.0 (Ktor) | **MOVE to `:shared:voice-codec` commonMain** — Ktor client + websockets are KMP-clean (jvm + Native engines). Discord protocol module already uses Ktor commonMain in `:shared:protocol-discord` per the pattern. Rename to drop `Jvm` suffix; no `.jvm.kt` because Ktor is KMP. |
| `crypto/XChaCha20Poly1305Jvm.kt` | Apache-2.0 source on top of BouncyCastle (MIT) | **REWRITE as expect/actual** in `:shared:voice-codec`. commonMain: `expect fun xchacha20Poly1305(key: ByteArray): AeadCipher`. jvmMain: keep current BouncyCastle impl (rename `XChaCha20Poly1305.jvm.kt`). iosMain: NEW — CryptoKit `ChaChaPoly` via Apple cinterop + HChaCha20 subkey derivation in pure Kotlin. The `aead_xchacha20_poly1305_rtpsize` mode is mandatory per Discord protocol; no XSalsa20 path exists or is needed (FFP §3.4 reference to `XSalsa20Poly1305Cipher` is stale — no such file). |
| `DefaultVoiceClient.kt` | Apache-2.0 source on top of JVM concurrent collections + `:shared:voice-dave` (GPL via libdave) | **STAYS in `:shared:voice` jvmMain.** This is the GPL desktop voice client; it composes DAVE + BouncyCastle + Java Sound and is not portable. AppleNativeVoiceClient is a parallel commonMain impl in `:shared:voice-codec`. |
| `pipeline/PlaybackPipeline.kt` | Apache-2.0 (java.util.concurrent.*) | **REWRITE in `:shared:voice-codec` commonMain** using kotlinx.coroutines atomics + `Channel`. Same algorithm; only the concurrency primitives change. |
| `pipeline/IncomingVideoPipeline.kt` | Apache-2.0 (j.u.c.ConcurrentHashMap) | **REWRITE in `:shared:voice-codec` commonMain** with `kotlinx.atomicfu.locks` / `Mutex`. |
| `transport/SoundshareAudioRtpSender.kt` | Apache-2.0 | **MOVE to `:shared:voice-codec` commonMain** — pure RTP wrap; depends on `VoicePacketCodec` (already in voice-codec) + `UdpRtpTransport` (which is `VoiceUdpTransport` in voice-codec — rename in the move). |
| `transport/VideoRtpSender.kt` | Apache-2.0 (j.u.c.atomic.AtomicInteger) | **MOVE to `:shared:voice-codec` commonMain** — replace `AtomicInteger` with `kotlinx.atomicfu.AtomicInt`. |
| `transport/VoicePacketDispatcher.kt` | Apache-2.0 (kotlinx.coroutines Channel) | **MOVE to `:shared:voice-codec` commonMain** — already KMP-clean; uses Dispatchers.IO which exists on Native. |
| `transport/UdpRtpTransport.jvm.kt` | Apache-2.0 (java.net.DatagramSocket) | **DELETE** — `:shared:voice-codec/commonMain/.../VoiceUdpTransport.kt` (FP-3) is the KMP replacement. JVM factory bridge already exists at `:shared:voice/jvmMain/.../codec/transport/JvmVoiceUdpTransportFactory.kt`; iOS factory at `:shared:voice-codec/iosMain/.../IosVoiceUdpTransportFactory.kt`; Mac App Store factory at `:desktop:platform-macos-appstore/.../JnaNwConnectionUdpTransportFactory.kt`. The legacy `UdpRtpTransport` interface in `:shared:voice` must collapse onto `VoiceUdpTransport`. |
| `audio/JavaSoundCapture.kt` | Apache-2.0 (javax.sound.sampled) | **STAYS** — JVM-only `actual` for `expect fun audioCapture()`. |
| `audio/JavaSoundPlayback.kt` | Apache-2.0 (javax.sound.sampled) | **STAYS** — JVM-only `actual`. |
| `audio/JavaSoundDevices.kt` | Apache-2.0 (javax.sound.sampled) | **STAYS** — JVM-only `actual` for `expect fun listAudioDevices()`. |
| `codec/H264Decoder.kt` | **GPL via FFmpeg-javacpp** (org.bytedeco.ffmpeg.*) | **STAYS in `:shared:voice` jvmMain.** Apple builds use `IosH264Decoder` / `JnaVideoToolboxH264Decoder` instead. |
| `codec/transport/JvmVoiceUdpTransportFactory.kt` | Apache-2.0 | **STAYS** — JVM `actual` for `VoiceUdpTransportFactory`. |
| `screenshare/DefaultScreenShareClient.kt` | Apache-2.0 source on top of GPL-tainted JVM screencast deps (libav + FFmpeg) | **STAYS in `:shared:voice` jvmMain.** Apple builds wire `IosScreenCapture` / `MacScreenCapture` directly into `AppleNativeVoiceClient` without going through `DefaultScreenShareClient`. |
| `screenshare/encoder/FfmpegVideoEncoder.jvm.kt` | **GPL via FFmpeg-javacpp** | STAYS in jvmMain. |
| `screenshare/source/MacScreenSourceEnumerator.jvm.kt` | Apache-2.0 (uses `system_profiler`) | STAYS (JVM-only; not needed on iOS — `IosScreenSourceEnumerator` in `:shared:screencast` already covers iOS). |
| `screenshare/source/LibavMonitorEnumerator.jvm.kt` | **GPL via libavdevice** | STAYS. |

### 2.3 `:shared:voice-dave` (4 jvmMain + 4 commonMain)

ALL JVM impls (`LibdaveBindings.kt`, `LibdaveLoader.kt`, `LibdaveMlsClient.kt`,
`MlsClient.jvm.kt`) are **GPL via libdave (Discord's open-source MLS impl)
and core-crypto-jvm 4.2.0 (Wire, GPL-3.0-or-later)**.

commonMain `DaveBinaryFrame.kt`, `DaveOp.kt`, `SasFormatter.kt`,
`MlsClient.kt`, `DaveSession.kt` are themselves Apache-2.0 source code, but
**`AppleNativeVoiceClient` MUST NOT depend on `:shared:voice-dave` at all**
— per FFP §3.4 and the architect 2026-05-29 user-locked decision, App Store
builds emit permanent `DaveUiState.Unavailable` (renamed `DaveUiState.Off` /
`DaveUiState.Disabled` per existing types — the banner already covers the
case). No libdave dependency, no MLS, no clean-room implementation in this
scope.

### 2.4 Modules already KMP-clean (no change needed)

- `:shared:voice-codec` — has commonMain + jvmMain + iosArm64 + iosX64 +
  iosSimulatorArm64 source sets (FP-1..FP-6).
- `:shared:voice-api` — commonMain only, fully Apache-2.0 KMP-clean.
- `:shared:screencast` — has iosMain (FP-12). `MacScreenCapture` etc. live
  in jvmMain because the Mac App Store target is a JVM Compose Desktop ship.

---

## §3 Voice-gateway WebSocket inventory

The state machine lives in `:shared:voice/src/commonMain/kotlin/dev/puklic/
voice/gateway/`. All four files are Apache-2.0 KMP-clean. Opcode coverage:

| Op | Direction | Handler | Location |
|---|---|---|---|
| 0 IDENTIFY | C→S | `DefaultVoiceGatewayConnection.identify()` (assembles `VoiceIdentify` and sends as op 0) | `VoiceGatewayConnection.kt` |
| 1 SELECT_PROTOCOL | C→S | `sendSelectProtocol()` | `VoiceGatewayConnection.kt` |
| 2 READY | S→C | parsed into `VoiceGatewayEvent.Ready` | `VoiceGatewayConnection.kt` |
| 3 HEARTBEAT | C→S | heartbeat loop | `VoiceGatewayConnection.kt` |
| 4 SESSION_DESCRIPTION | S→C | `VoiceGatewayEvent.SessionDescription` | `VoiceGatewayConnection.kt` |
| 5 SPEAKING | both | `sendSpeaking()` + event | `VoiceGatewayConnection.kt` |
| 6 HEARTBEAT_ACK | S→C | missed-ack counter | `VoiceGatewayConnection.kt` |
| 7 RESUME | C→S | resume path | `VoiceGatewayConnection.kt` |
| 8 HELLO | S→C | starts heartbeat | `VoiceGatewayConnection.kt` |
| 9 RESUMED | S→C | success of resume | `VoiceGatewayConnection.kt` |
| 12 VIDEO_STREAM | C→S | `sendVideoStream()` | `VoiceGatewayConnection.kt` |
| 13 CLIENT_DISCONNECT | S→C | `VoiceGatewayEvent.ClientDisconnect` | `VoiceGatewayConnection.kt` |
| 21..24, 31 (DAVE JSON) | both | `sendDaveJson()` + `setDaveJsonHandler()` | `VoiceGatewayConnection.kt` |
| 25..30 (DAVE binary) | both | `sendBinary()` + `setDaveBinaryHandler()` | `VoiceGatewayConnection.kt` |

The DAVE 21..31 hooks remain wired in the same connection but
`AppleNativeVoiceClient` installs no DAVE handlers (the handlers stay null
→ MLS frames are ignored). DAVE state stays `Unavailable`. Conceptually
correct: the gateway transport is generic; only the DAVE policy layer
above changes.

**KMP-portability verdict**: every file in
`shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/` is
commonMain-eligible after the module move. No `expect/actual` extraction
required. The only required transformation is the JVM `actual` for the
WebSocket transport (`KtorVoiceGatewayTransport`) — and Ktor's
`io.ktor.client.plugins.websocket` is KMP-clean (Native engines exist for
iosArm64/iosX64/iosSimulatorArm64 via `Darwin` engine which
`:shared:protocol-discord` already uses on iOS). So
`KtorVoiceGatewayTransport` moves to commonMain too; only the `HttpClient`
engine differs across platforms and is injected from the dep graph (Darwin
on iOS, CIO on JVM — already a pattern in `:shared:protocol-discord`).

---

## §4 RTP packet codec inventory

| Piece | Module | KMP status |
|---|---|---|
| `VoicePacketCodec` (sequence / timestamp / nonce counter + AEAD glue) | `:shared:voice-codec` commonMain (FP-1, `8978e6e`) | KMP-clean ✅ |
| `RtpPacket` (12-byte header read/write) | `:shared:voice-codec` commonMain (FP-1) | KMP-clean ✅ |
| `NonceGenerator` (24 B XChaCha20 nonce counter, `_rtpsize` layout) | `:shared:voice-codec` commonMain (FP-1) | KMP-clean ✅ |
| `AeadCipher` interface | `:shared:voice-codec` commonMain (FP-1) | KMP-clean ✅ |
| `XChaCha20Poly1305` impl | `:shared:voice` jvmMain only (BouncyCastle) | **MOVE + REWRITE as expect/actual.** JVM keeps BouncyCastle; iOS uses CryptoKit `ChaChaPoly` (via Foundation cinterop) wrapped with HChaCha20 subkey derivation (pure Kotlin, ~30 lines, RFC `draft-irtf-cfrg-xchacha` §2.2). |
| `XSalsa20Poly1305` impl | **DOES NOT EXIST** anywhere in repo. FFP §3.4 line "XSalsa20Poly1305Cipher already present in :shared:voice" is stale. The only AEAD mode wired is `aead_xchacha20_poly1305_rtpsize` (Discord-supported, active since 2024). No work needed. |
| `SoundshareAudioRtpSender` (separate SSRC for screencast audio) | `:shared:voice` jvmMain (Apache-2.0, no JVM deps) | **MOVE to** `:shared:voice-codec` commonMain. Trivial — depends only on `VoicePacketCodec` + `UdpRtpTransport`. |

**Decision on RTP codec location** (per #63 explicit question): the RTP
packet codec is already complete in `:shared:voice-codec` commonMain
(FP-1). The voice-gateway state machine MUST move alongside it so
`AppleNativeVoiceClient` can compose `VoiceGatewayConnection` +
`VoicePacketCodec` + `VoiceUdpTransport` + `OpusCodecFactory` without
crossing the GPL boundary into `:shared:voice`.

---

## §5 Audio capture inventory + design

### 5.1 Current state

- `:shared:voice/commonMain/.../audio/AudioCapture.kt` — `expect fun
  audioCapture(): AudioCapture` + `expect fun listAudioDevices(...)`.
  Interface: `start(deviceId)`, `stop()`, blocking `read(): ShortArray`
  returning one 20 ms frame = 960 samples @ 48 kHz mono S16LE.
- `:shared:voice/jvmMain/.../audio/JavaSoundCapture.kt` — JVM `actual`
  using `javax.sound.sampled.TargetDataLine`.

### 5.2 Move + new design

**Move**: `AudioCapture.kt` (the `expect`) + `AudioPlayback.kt` move to
`:shared:voice-codec` commonMain (alongside `OpusEncoder` etc. — same
"codec contracts" module). `JavaSoundCapture.kt` + `JavaSoundPlayback.kt`
+ `JavaSoundDevices.kt` move to `:shared:voice-codec/jvmMain`.

**New**: `:shared:voice-codec/iosMain/.../audio/AppleAudioCapture.kt`
(iOS `actual` using AVAudioEngine + AudioToolbox via cinterop):

- Use `AVAudioEngine().inputNode` with `installTap(onBus:bufferSize:format:
  block:)` to receive `AVAudioPCMBuffer` chunks.
- Configure the input node's `outputFormat(forBus: 0)` to 48 kHz mono S16
  (use `AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 48000,
  channels: 1, interleaved: true)`).
- Each tap callback delivers `frameLength` samples; the tap is reactive
  (push-based, on Apple's audio render thread). Convert to the
  `read(): ShortArray` pull-based contract by buffering into a
  `Channel<ShortArray>(capacity = 8)` (160 ms tolerance) and
  `runBlocking { channel.receive() }` in `read()`. This matches the
  JVM `JavaSoundCapture` blocking semantics.
- Lifecycle: `start()` calls `engine.prepare()` + `engine.start()` after
  installing the tap; `stop()` calls `engine.stop()` + `removeTap(onBus: 0)`.
- Permissions: iOS requires `NSMicrophoneUsageDescription` in Info.plist
  (already added in FP-14). At runtime,
  `AVAudioSession.sharedInstance().requestRecordPermission { granted in … }`
  is called before `start()`; denial throws.

**New**: `:desktop:platform-macos-appstore/.../audio/JnaAVAudioCapture.kt`
(Mac App Store JNA `actual` for the `:shared:voice-codec` JVM target on
the Mac App Store build path).

Strategy: the Mac App Store JVM build's `audioCapture()` is overridden via
DI rather than expect/actual rewiring. The JVM `actual` selected by the
`macAppStoreImplementation` configuration is `JavaSoundCapture`, BUT the
`AppleNativeVoiceClient` constructor takes an `AudioCapture` instance via
**factory injection** — same pattern as `VoiceUdpTransportFactory`. The
DI graph picks `JnaAVAudioCaptureFactory` instead of the default
`javaSoundAudioCaptureFactory`. JavaSoundCapture is fine on the Mac App
Store too (CoreAudio HAL backs `javax.sound.sampled` on macOS — works in
the sandbox with `com.apple.security.device.audio-input` entitlement),
but going through AVAudioEngine via JNA gives:

- Native AirPods + USB device names with the user's friendly labels
  (`javax.sound.sampled` shows cryptic CoreAudio identifiers).
- Proper interaction with `AVAudioSession` policies (route changes,
  interruption handling).
- Consistent codepath across iOS + Mac App Store for diagnostics.

JNA layer (~200 LoC) follows the same shape as
`JnaNwConnectionUdpTransport`:

- `AVAudioEngine.kt` JNA Library interface — `objc_msgSend`-driven calls
  to `[AVAudioEngine new]`, `[AVAudioEngine inputNode]`,
  `[AVAudioInputNode installTapOnBus:0 bufferSize:1024 format:nil block:^...]`.
- `AppleBlock.forge { … }` wraps the tap closure (already exists in the
  `:desktop:platform-macos-appstore/bridge/AppleBlock.kt` helper).
- `JnaAVAudioCapture` exposes `read(): ShortArray` via `Channel<ShortArray>`.

If the audio-input entitlement is granted, the JVM blocks on the channel
between tap callbacks.

### 5.3 Decision

Both iOS + Mac App Store get `AppleAudioCapture` (the term covers both
the iosMain cinterop version and the JVM JNA version). On JVM-non-Apple
builds the existing `JavaSoundCapture` stays as the `actual`. The
expect/actual remains in `:shared:voice-codec` commonMain.

---

## §6 DAVE inventory

| Piece | Module | License | App Store disposition |
|---|---|---|---|
| `MlsClient` interface | `:shared:voice-dave` commonMain | Apache-2.0 | NOT USED on App Store builds |
| `DaveSession` | `:shared:voice-dave` commonMain | Apache-2.0 | NOT USED |
| `DaveBinaryFrame`, `DaveOp` | `:shared:voice-dave` commonMain | Apache-2.0 | NOT USED |
| `SasFormatter` | `:shared:voice-dave` commonMain | Apache-2.0 | NOT USED |
| `LibdaveMlsClient` (JNI to libdave) | `:shared:voice-dave` jvmMain | **GPL via libdave** | DESKTOP-ONLY |
| `MlsClient.jvm.kt` | `:shared:voice-dave` jvmMain | Apache-2.0 source on top of `com.wire:core-crypto-jvm` (GPL-3.0-or-later) | DESKTOP-ONLY |
| `LibdaveBindings`, `LibdaveLoader` | `:shared:voice-dave` jvmMain | GPL | DESKTOP-ONLY |
| `DaveDowngradeDetector` | `:shared:voice-api` commonMain (a3c274e per FP-1) | Apache-2.0 | USED — emits permanent `DaveUiState.Disabled` to the UI banner. |

**Decision** (architect 2026-05-29 + critic-confirmed): `AppleNativeVoiceClient`
holds a single `MutableStateFlow<DaveUiState>` initialised to
`DaveUiState.Disabled(reason = "App Store builds do not include DAVE")`.
This is permanent — no transition logic, no MLS, no libdave dependency. The
existing `DaveDowngradeBanner` (`:shared:compose-ui/.../voice/`) already
renders the "not encrypted end-to-end" message for this state. UI behaves
correctly with zero extra wiring.

`:shared:voice-dave` is NOT a dependency of `:shared:voice-codec` and MUST
NOT be added.

---

## §7 `AppleNativeVoiceClient` composition design

### 7.1 Module + location

`:shared:voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/client/
AppleNativeVoiceClient.kt`

Resides in `:shared:voice-codec` because all its dependencies are already
there or movable there per the move map. iosArm64 / iosX64 /
iosSimulatorArm64 / jvm source sets compile it identically. No
`expect/actual` on the client itself — only on the primitives it composes
(`OpusCodecFactory`, `VoiceUdpTransportFactory`, `audioCapture()`,
`xchacha20Poly1305()`).

### 7.2 Constructor signature

```kotlin
internal class AppleNativeVoiceClient(
    private val applicationScope: CoroutineScope,
    private val mainGatewayBridge: MainGatewayBridge,
    private val gatewayTransportFactory: VoiceGatewayTransportFactory,
    private val udpTransportFactory: VoiceUdpTransportFactory,
    private val opusCodecFactory: OpusCodecFactory,
    private val audioCaptureFactory: () -> AudioCapture,
    private val audioPlaybackFactory: () -> AudioPlayback,
    private val screenCaptureFactory: () -> ScreenCapture?,           // null = no screencast
    private val h264EncoderFactory: H264EncoderFactory?,              // null = audio-only
    private val h264DecoderFactory: H264DecoderFactory?,              // null = audio-only
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : VoiceClient
```

### 7.3 Box diagram

```
                       AppleNativeVoiceClient
                              │
       ┌──────────────────────┼──────────────────────────┐
       │                      │                          │
   MainGatewayBridge   VoiceGatewayConnection      sessionScope (per call)
   (op 4 VOICE_STATE   (op 0..13 + DAVE 21..31         │
    + dispatches)       no-op handlers)        ┌───────┼────────┬─────────┐
                              │                │       │        │         │
                       KtorVoiceGateway        UDP    Audio   Video    Screencast
                       Transport               │      │        │         │
                       (Ktor + Darwin/CIO)     │      │        │         │
                                               │      │        │         │
                                       VoicePacket  AudioCapture (mic)  ScreenCapture
                                       Codec  ◄──┘     │        │         │
                                       │           OpusEncoder H264Enc/Dec │
                                       │           OpusDecoder              │
                                       │              │        │         │
                                       │           AudioPlay   │       SoundshareAudio
                                       │           back        │       RtpSender
                                       │                       │
                                       └─── VoiceUdpTransport ─┴────────────┘
                                                  │
                                       iOS: IosVoiceUdpTransport (Network.framework cinterop)
                                       MacAppStore: JnaNwConnectionUdpTransport (JNA)
                                       Linux/macOS-desktop: not applicable (uses DefaultVoiceClient)

   DaveDowngradeDetector ◄─── MutableStateFlow<DaveUiState>(Disabled) ── permanent
```

### 7.4 Lifecycle

Three nested scopes per current architecture:

1. `applicationScope` — outlives the app. Holds the
   `MutableStateFlow<VoiceState>`, the `MainGatewayBridge` subscriptions
   (CALL_CREATE, VOICE_SERVER_UPDATE), and the permanent
   `DaveUiState.Disabled` flow.
2. `sessionScope = applicationScope + SupervisorJob()` — per voice channel
   join. Owns `VoiceGatewayConnection`, `VoiceUdpTransport`, the
   `CapturePipeline`, `PlaybackPipeline`, `IncomingVideoPipeline`,
   `VoicePacketDispatcher`. Cancelled on leave / error / app shutdown;
   `SupervisorJob` ensures a failure in one sub-pipeline doesn't tear down
   the others without policy.
3. `screencastScope = sessionScope + SupervisorJob()` — per screen-share
   start. Owns `ScreenCapture`, `VideoRtpSender`, `SoundshareAudioRtpSender`,
   `H264Encoder`. Cancelled on stop without disturbing the audio call.

All scopes use `Dispatchers.Default` for state work; the
`AppleAudioCapture` tap delivers on Apple's audio render thread and bridges
via `Channel` to a coroutine on `Dispatchers.IO` (JVM) / `Dispatchers.Main`
(Native — Native does not expose a separate IO dispatcher; Default is the
correct choice).

### 7.5 Compatibility with existing `VoiceClient` surface

`VoiceClient` (in `:shared:voice-api`) exposes `state: StateFlow<VoiceState>`,
`daveState: StateFlow<DaveUiState>`, `screenShareClient: ScreenShareClient`,
plus `join()` / `leave()` / `setMuted()` / `setDeafened()` /
`setInputDevice()` / `setOutputDevice()` / `acceptCall()` / `rejectCall()`.
`AppleNativeVoiceClient` implements every method. No surface change to
`VoiceClient`; no UI changes required.

`AppleNativeVoiceClient` provides an internal `AppleNativeScreenShareClient`
implementing `ScreenShareClient` over `screenCaptureFactory()`. Same
contract as `DefaultScreenShareClient` but without libdave + libav.

---

## §8 Move map

`git mv` semantics — package paths preserved across the move so existing
imports continue to resolve transitively via the
`api(projects.shared.voiceCodec)` edge in `:shared:voice/build.gradle.kts`
(which already exists, line 31 of `shared/voice/build.gradle.kts`).

| Source path | Destination path | Notes |
|---|---|---|
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceOp.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceOp.kt` | |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayPayload.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayPayload.kt` | |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayTransport.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayTransport.kt` | |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayConnection.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayConnection.kt` | |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/gateway/KtorVoiceGatewayTransport.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/gateway/KtorVoiceGatewayTransport.kt` | Renamed `Ktor*` (drop `Jvm` suffix) — Ktor websockets are KMP. |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/IpDiscovery.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/IpDiscovery.kt` | |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/Vp8Packetiser.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/Vp8Packetiser.kt` | |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/H264Depacketizer.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/H264Depacketizer.kt` | |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/VideoFrameFragmenter.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/VideoFrameFragmenter.kt` | |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/H264Fragmenter.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/H264Fragmenter.kt` | |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/SoundshareAudioRtpSender.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/SoundshareAudioRtpSender.kt` | Move from jvmMain → commonMain (pure Kotlin). |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/VideoRtpSender.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/VideoRtpSender.kt` | Replace `java.util.concurrent.atomic.AtomicInteger` with `kotlinx.atomicfu.AtomicInt`. |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/VoicePacketDispatcher.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/VoicePacketDispatcher.kt` | |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/audio/AudioCapture.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/audio/AudioCapture.kt` | |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/audio/AudioPlayback.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/audio/AudioPlayback.kt` | |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundCapture.kt` | `shared/voice-codec/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundCapture.kt` | |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundPlayback.kt` | `shared/voice-codec/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundPlayback.kt` | |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundDevices.kt` | `shared/voice-codec/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundDevices.kt` | |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/pipeline/CapturePipeline.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/pipeline/CapturePipeline.kt` | |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/pipeline/PlaybackPipeline.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/pipeline/PlaybackPipeline.kt` | REWRITE concurrency: `ConcurrentHashMap` → `kotlinx.atomicfu` + `Mutex`. |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/pipeline/IncomingVideoPipeline.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/pipeline/IncomingVideoPipeline.kt` | Same rewrite. |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305Jvm.kt` | `shared/voice-codec/src/jvmMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.jvm.kt` | Wrap in `actual fun xchacha20Poly1305(...)`. |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/UdpRtpTransport.jvm.kt` | **DELETE** | Replaced by `VoiceUdpTransport` (FP-3) already in voice-codec. |

**STAYS in `:shared:voice` jvmMain** (GPL or genuinely JVM-only):
`DefaultVoiceClient`, `H264Decoder` (FFmpeg-GPL),
`DefaultScreenShareClient`, `FfmpegVideoEncoder.jvm.kt`,
`LibavMonitorEnumerator.jvm.kt`, `MacScreenSourceEnumerator.jvm.kt`,
`JvmVoiceUdpTransportFactory.kt`.

Total: **23 moves + 1 delete + 0 source changes outside the moved files**
for FP-14h-2.

---

## §9 New files map

`AppleNativeVoiceClient` + supporting bits:

| Path | Purpose | Slice |
|---|---|---|
| `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.kt` | `expect fun xchacha20Poly1305(key: ByteArray): AeadCipher` | FP-14h-2 |
| `shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.ios.kt` | iOS `actual` — CryptoKit `ChaChaPoly` + HChaCha20 subkey | FP-14h-2 |
| `shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/audio/AppleAudioCapture.kt` | iOS `actual` for `audioCapture()` — AVAudioEngine cinterop | FP-14h-3 |
| `shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/audio/AppleAudioPlayback.kt` | iOS `actual` for `audioPlayback()` — AVAudioEngine output | FP-14h-3 |
| `shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/audio/AppleAudioDevices.kt` | iOS `actual` for `listAudioDevices()` — AVAudioSession route info | FP-14h-3 |
| `desktop/platform-macos-appstore/src/main/kotlin/dev/puklic/desktop/macappstore/audio/JnaAVAudioCapture.kt` | Mac App Store JNA AVAudioEngine input tap | FP-14h-3 |
| `desktop/platform-macos-appstore/src/main/kotlin/dev/puklic/desktop/macappstore/audio/JnaAVAudioCaptureFactory.kt` | DI factory selecting JNA over JavaSound | FP-14h-3 |
| `desktop/platform-macos-appstore/src/main/kotlin/dev/puklic/desktop/macappstore/audio/JnaAVAudioPlayback.kt` | Mac App Store JNA AVAudioEngine output | FP-14h-3 |
| `desktop/platform-macos-appstore/src/main/kotlin/dev/puklic/desktop/macappstore/bridge/AVFoundation.kt` | JNA Library iface for `AVAudioEngine` + `AVAudioInputNode` + `AVAudioPCMBuffer` | FP-14h-3 |
| `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/client/AppleNativeVoiceClient.kt` | Client composition (mic → Opus → UDP, DAVE permanent Off, screencast via injected ScreenCapture) | FP-14h-4 |
| `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/client/AppleNativeScreenShareClient.kt` | `ScreenShareClient` impl for AppleNativeVoiceClient | FP-14h-5 |

11 new files. No `TODO()` markers — every file has a complete impl plan in
§7.

---

## §10 Self-critic findings

### 10.1 GPL-leak audit on the move map

Checked each file in §8 for hidden JVM-only or GPL imports:

- `VoiceGatewayConnection.kt` — imports only kermit + kotlinx.coroutines +
  kotlinx.serialization. No JVM-only call. ✅
- `VoiceGatewayPayload.kt` — kotlinx.serialization only. ✅
- `KtorVoiceGatewayTransport.kt` — `io.ktor.client.plugins.websocket`,
  `io.ktor.websocket.*`. Ktor `client-websockets` is KMP-clean (Native
  engines published for iosArm64/iosX64/iosSimulatorArm64). ✅
  *Caveat*: the current file holds a `DefaultClientWebSocketSession` — that
  type IS KMP. The `HttpClient` engine differs per platform and is passed in
  from the dep graph (Darwin engine on iOS already in
  `:shared:protocol-discord`). ✅
- `IpDiscovery.kt` — pure bytes + `kotlinx.coroutines.withTimeout`. ✅
- `Vp8Packetiser.kt`, `H264Depacketizer.kt`, `VideoFrameFragmenter.kt`,
  `H264Fragmenter.kt` — pure bytes, no imports of concern. ✅
- `SoundshareAudioRtpSender.kt` — only `:shared:voice-codec` AEAD types. ✅
- `VideoRtpSender.kt` — uses `java.util.concurrent.atomic.AtomicInteger`.
  Plan: replace with `kotlinx.atomicfu.AtomicInt` (already a transitive
  dep via kotlinx.coroutines). ✅
- `VoicePacketDispatcher.kt` — `kotlinx.coroutines.channels.Channel` +
  `Dispatchers.IO`. `Dispatchers.IO` exists on Native (alias of Default
  per kotlinx.coroutines 1.8+). ✅
- `AudioCapture.kt`/`AudioPlayback.kt` — only `AudioDevice` from
  `:shared:voice-api`. ✅
- `CapturePipeline.kt` — `kotlinx.coroutines` only. ✅
- `PlaybackPipeline.kt`, `IncomingVideoPipeline.kt` — use
  `j.u.c.ConcurrentHashMap` + `ConcurrentLinkedQueue`. Rewrite uses
  `kotlinx.atomicfu.locks.SynchronizedObject` + `Mutex` + plain
  `MutableMap` guarded by lock. No external lib added; net dep change zero. ✅
- `XChaCha20Poly1305Jvm.kt` — BouncyCastle is JVM-only. The move splits
  this into expect/actual; only the JVM `actual` retains BouncyCastle. iOS
  `actual` uses Apple-shipped CryptoKit (no third-party dep). ✅

No hidden GPL leak. The only edge case is BouncyCastle on the JVM `actual`,
which is MIT — Apache-2.0 compatible.

### 10.2 Kotlin/Native compatibility audit

Coroutine features used by the moved files:
`MutableStateFlow`/`MutableSharedFlow`/`StateFlow`/`SharedFlow`,
`channelFlow`, `Channel(capacity)`, `SupervisorJob`, `Mutex`,
`withTimeoutOrNull`, `delay`, `launch`, `cancelAndJoin`. All available on
Kotlin/Native since coroutines 1.7. ✅

`@Volatile` is used on `DefaultVoiceGatewayConnection.daveBinaryHandler` /
`daveJsonHandler`. Native does support `@Volatile` since Kotlin 1.8 (Native
new memory manager). ✅

No `Thread.currentThread()`, no `ClassLoader`, no `java.util.concurrent.*`
in the moved files post-rewrite.

### 10.3 Minimum-complexity check (HARD RULE #1)

The composition has 8 concrete dependencies + 3 factory dependencies. Is
that too much?

- The 8 dependencies are unavoidable: gateway state machine, gateway
  WebSocket, UDP transport, RTP codec, Opus encoder, Opus decoder, mic
  capture, audio playback. No abstraction can collapse them — they
  correspond 1:1 to the Discord voice protocol's mandatory components.
- Video + screencast factories are nullable so audio-only callers don't
  pay the cost.
- DAVE is a single `MutableStateFlow` (one line), not a sub-component —
  minimum possible cost.

No "future-expansion hooks", no toggleable features, no fallback shims. ✅

### 10.4 Library-first audit (per global memory)

Surveyed Maven Central for KMP-clean Discord voice-gateway helpers:

- **Kord** (`dev.kord:kord-voice`) — JVM-only; depends on lavaplayer
  (LGPL). Not KMP. **Rejected.**
- **JDA-Voice** — JVM-only. **Rejected.**
- **discord-rs (Rust)** via uniffi — outside scope (no Rust toolchain on
  iOS App Store builds; FP-14a already excludes Wire's core-crypto on iOS
  for the same reason).
- **`io.github.kosmosis:discord-kmp`** — does not exist (verified
  Maven Central + jitpack search).
- **`com.github.discord-bot-kt:dbktx`** — chat-only, no voice.

No KMP-clean third-party voice-gateway helper exists. Hand-rolled is the
only conceptually-correct path. The existing
`VoiceGatewayConnection`/`VoiceOp`/`VoiceGatewayPayload` IS the hand-rolled
impl and is reusable. ✅

For the AEAD cipher: BouncyCastle (JVM `actual`) + CryptoKit (iOS
`actual`) — both stable libraries shipped by the platform. ✅

For Opus: libopus 1.5.2 (BSD-3) via `Opus.xcframework` (FP-4 shipped) +
javacpp FFmpeg-GPL (desktop only). ✅

For H264: VideoToolbox (iOS / macOS — Apple-shipped, no licence cost) +
FFmpeg-GPL (desktop only). ✅

No library survey gap.

### 10.5 USER DECISION REQUIRED

**None.** Every blocking question was already answered by prior
architect-locked decisions:

- DAVE on App Store builds → permanent `Disabled` (architect 2026-05-29 +
  user-locked per FFP §3.4).
- XSalsa20 path → does not exist (no work needed; FFP §3.4 mention is stale).
- AudioCapture surface → `Flow`/blocking-read tradeoff was answered: keep
  current blocking `read(): ShortArray` semantics (matches JVM contract).
- Mac App Store mic capture path → JNA AVAudioEngine (consistency with
  iOS path; alternative `javax.sound.sampled` is acceptable but inferior
  on device naming + route handling).

If the user disagrees with the Mac App Store JNA AVAudioEngine choice (vs.
keeping `javax.sound.sampled`), FP-14h-3 can swap in the simpler JavaSound
implementation in ~1 day. Default plan = JNA AVAudioEngine for consistency.

---

## §11 Slice decomposition for FP-14h-2..h-9

### FP-14h-2 — extract Apache-2.0 voice-gateway pieces to `:shared:voice-codec`

**Files moved** (23) + **deleted** (1) per §8.

**New files** (2): `XChaCha20Poly1305.kt` (expect),
`XChaCha20Poly1305.ios.kt` (CryptoKit `actual` + HChaCha20 subkey
derivation).

**Build script changes**:
- `shared/voice-codec/build.gradle.kts`:
  - commonMain.dependencies adds: `kotlinx.serialization.json`,
    `ktor.client.core`, `ktor.client.websockets`, `kermit`,
    `kotlinx.atomicfu`.
  - jvmMain.dependencies adds: `ktor.client.cio` (for the existing JVM
    Ktor engine if not already transitively present),
    `bouncycastle.bcprov`.
  - iosMain.dependencies — none new; CryptoKit is part of the iOS SDK
    surfaced via cinterop already configured in FP-4..FP-6.
- `shared/voice/build.gradle.kts` jvmMain.dependencies: drop `ktor.*`,
  `bouncycastle.bcprov` (now transitive via `api(voice-codec)`); keep
  `javacpp`, `ffmpeg-bindings`, `dbus-java-*`, `voice-dave`.

**Acceptance criteria**:
- [x] `./gradlew :shared:voice-codec:compileKotlinJvm` GREEN.
- [x] `./gradlew :shared:voice-codec:compileKotlinIosArm64` GREEN.
- [x] `./gradlew :shared:voice-codec:compileKotlinIosSimulatorArm64` GREEN.
- [x] `./gradlew :shared:voice:compileKotlinJvm` GREEN — existing JVM
      consumers (`DefaultVoiceClient`, etc.) still resolve every symbol
      transitively via `api(voiceCodec)`.
- [x] No new `internal` visibility leaks — gateway types remain `internal`
      to `:shared:voice-codec`; `AppleNativeVoiceClient` (also internal to
      the module) uses them; factory exposure to the dep graph is via
      `public` factory functions in a new `codec/client/Factories.kt`.
- [x] `./gradlew :ios:app:verifyIosNoGplDeps` stays GREEN (no new GPL on
      the iOS classpath).
- [x] `./gradlew :desktop:app:verifyMacAppStoreNoGplDeps` stays GREEN.

### FP-14h-3 — `AppleAudioCapture` (iOS cinterop + Mac App Store JNA)

**New files** (5 per §9): AppleAudioCapture.kt + AppleAudioPlayback.kt +
AppleAudioDevices.kt (iosMain), JnaAVAudioCapture.kt +
JnaAVAudioCaptureFactory.kt + JnaAVAudioPlayback.kt + AVFoundation.kt
(:desktop:platform-macos-appstore).

**Acceptance**:
- [x] `IosAudioCaptureRoundTripTest` (new) — start, read N frames, stop;
      assert ShortArray.size == 960; assert non-zero RMS when reading a
      sine wave injected via AVAudioPlayerNode in test fixture.
- [x] `JnaAVAudioCaptureRoundTripTest` (new, macAppStoreTest source set).
- [x] AVAudioSession `requestRecordPermission` plumbed; denial throws
      `SecurityException("microphone permission denied")`.

### FP-14h-4 — `AppleNativeVoiceClient` core (mic → Opus → UDP + DAVE Disabled)

**New file**: `AppleNativeVoiceClient.kt` per §9.

**Wires**: `VoiceGatewayConnection` ↔ `MainGatewayBridge`,
`VoicePacketCodec` ↔ `VoiceUdpTransport`, `CapturePipeline` ↔
`audioCapture()` + `OpusEncoder`, `PlaybackPipeline` ↔ `OpusDecoder` +
`audioPlayback()`. `daveState` is a `MutableStateFlow.asStateFlow()` over
a constant `DaveUiState.Disabled`.

**Acceptance**:
- [x] Contract test `AppleNativeVoiceClientTest` — construct with fake
      gateway transport + fake UDP + in-memory audio capture; drive a
      mocked READY + SESSION_DESCRIPTION sequence; assert
      `state.value is VoiceState.Connected` within 1 s.
- [x] `daveState.value == DaveUiState.Disabled` always.
- [x] Smoke test: mic frames sent on UDP have correct sequence /
      timestamp / nonce per `VoicePacketCodec`.

### FP-14h-5 — Screencast wiring through `AppleNativeVoiceClient`

**New file**: `AppleNativeScreenShareClient.kt` per §9.

**Wires**: `screenCaptureFactory()` → `H264Encoder` → `VideoRtpSender`,
plus audio capture from the screencast source → `OpusEncoder` (stereo,
`Application.Audio`) → `SoundshareAudioRtpSender`. Op 12 VIDEO_STREAM
emitted on start; speaking flag bit 2 (soundshare) set per
`2026-05-28-screencast-audio-ssrc.md` §3.

**Acceptance**:
- [x] `AppleNativeScreenShareClientTest` — start with a fake
      `ScreenCapture` yielding `EncodedFrame`s; assert two SSRCs on UDP
      (video + soundshare audio); assert op 12 sent on gateway with
      correct `audioSsrc` + `videoSsrc` + `active=true`.
- [x] Stop tears down both pipelines without affecting the voice call.

### FP-14h-6 — F-7..F-13 architectural fixes

In-place edits inside `:desktop:platform-macos-appstore` +
`:shared:voice-codec` (NOT the FP-14h-2 move; this is a separate slice
that lands after).

- F-7: `JnaNwConnectionUdpTransport.close()` — add explicit `nw_release`
  on connection / params / endpoints; track endpoints as fields. (~10 LoC)
- F-8: `JnaNwConnectionUdpTransport` keepalive — wrap in
  `LinkedList<Keepalive>` + drop after 2 cycles.
- F-9: `JnaNwConnectionUdpTransport` STATE_FAILED — replace
  `closed.set(true)` with `markClosedAndCancel()` helper that runs the
  same teardown path as `close()`.
- F-10: `JnaVideoToolboxH264Encoder` — `@Volatile var frameIndex`,
  `synchronized(this) { ... }` around `lastTs90k` reads/writes, gate
  callback enqueue on `!closed`.
- F-11: `JnaVideoToolboxH264Decoder` — `@Volatile var lastFrame: IntArray?`.
- F-12: `JnaLibopusEncoder` + `JnaLibopusDecoder` — `closed: AtomicBoolean`,
  `synchronized(state) { ... }` around `encode`/`decode`/`close`.
- F-13: SPLIT `:shared:voice-codec` into `:shared:voice-codec-api`
  (commonMain only — interfaces + AeadCipher + NonceGenerator + RtpPacket
  + EncodedFrame + AnnexBSplitter) and `:shared:voice-codec-impl` (jvm +
  ios actuals + VoicePacketCodec impl + gateway state machine + Apple
  native client). Mac App Store consumer depends on `voice-codec-api` +
  `voice-codec-impl`; AppleNativeVoiceClient lives in `voice-codec-impl`.

**Acceptance**:
- [x] Existing FP-14b contract tests stay GREEN.
- [x] `:desktop:app:verifyMacAppStoreNoGplDeps` stays GREEN.
- [x] New unit tests for the lifetime/refcount/JMM fixes.

### FP-14h-7 — wire `AppleNativeVoiceClient` in `IosDependencyGraph` + `MacAppStoreDependencyGraph`

- Replace `NoOpVoiceClient()` at
  `ios/app/src/commonMain/kotlin/dev/puklic/ios/IosDependencyGraph.kt:301`.
- Replace `NoOpVoiceClient()` at
  `desktop/app/src/macAppStore/kotlin/dev/puklic/desktop/macappstore/MacAppStoreMain.kt:390`.

Inject factories from the dep graph: iOS picks
`IosVoiceUdpTransportFactory`, `IosOpusCodecFactory`,
`IosH264EncoderFactory`/`DecoderFactory`, `IosScreenCaptureFactory`,
`appleAudioCaptureFactory`. Mac App Store picks
`JnaNwConnectionUdpTransportFactory`, `JnaLibopusCodecFactory`,
`JnaVideoToolboxFactories`, `MacScreenCaptureFactory`,
`jnaAVAudioCaptureFactory`.

**Acceptance**:
- [x] `:ios:app:assemble` GREEN; iOS app builds.
- [x] `:desktop:app:packageMacAppStore` GREEN (assuming FP-14h-followup
      fixes F-1/F-3/F-4/F-5/F-6 land in parallel or before).
- [x] Smoke test in both dep graphs: construct the graph, assert
      `voiceClient !is NoOpVoiceClient`, assert
      `voiceClient.daveState.value is DaveUiState.Disabled`.
- [x] Remove the FP-14d stale comment block in `MacAppStoreMain.kt` lines
      105-117 (F-16).

### FP-14h-8 — F-16..F-21 cleanups + critic

- F-16: remove stale "voice not wired in v1" comment (done in FP-14h-7).
- F-17: `Dispatch.kt` — explicit `DISPATCH_DATA_DESTRUCTOR_FREE` symbol
  + `Native.malloc` for the buffer.
- F-18: `Network.kt` `DISABLE_PROTOCOL` — add a runtime null-check + ABI
  comment.
- F-19: `CompressionOutputCallback` — clarify `outputCallbackRefCon` is
  always non-null; doc-only.
- F-20: `MAX_OPUS_PACKET_BYTES` comment clarification (doc-only).
- F-21: drop `@Suppress("UNUSED_VARIABLE")` in
  `MacAppStoreDependencyGraph.create()` lines 231-236 (HARD RULE #2 —
  wire them into orchestrators or delete).
- Code-critic pass on FP-14h-2..7 deltas.

### FP-14h-9 — docs update + CLAUDE.md Platforms table flip

- `docs/05_platforms/ios.md` — voice + screencast sections updated.
- `docs/05_platforms/macos.md` — Mac App Store voice + screencast
  documented.
- `docs/07_roadmap/phases.md` — FP-14h marked `[x]`.
- `CLAUDE.md` Platforms table — iOS + Mac App Store Voice/Screencast
  columns flip from `⚠` to `✅`.
- Architect summary `2026-05-29-fp14h-summary.md` per pipeline Step 11.

---

## §12 Risks + mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Ktor `client-websockets` Native engine (`Darwin`) has a bug with binary frames (used by DAVE 25-30) | Low | Medium | DAVE binary handlers stay null on App Store builds → binary frames never sent or received. JSON-only path proven by `:shared:protocol-discord`. |
| `kotlinx.atomicfu` not transitive in current `:shared:voice-codec` deps | Low | Low | Explicit `implementation("org.jetbrains.kotlinx:atomicfu:$ver")` in FP-14h-2 build script change. |
| CryptoKit `ChaChaPoly.SealedBox` does not expose raw nonce-aligned API; we need IETF ChaCha20-Poly1305 (12 B nonce) — CryptoKit supports it via `ChaChaPoly.seal(_:using:nonce:authenticating:)` | Low | Low | Verified via Apple docs: `ChaChaPoly.Nonce(data:)` accepts 12-byte data; `seal(..., nonce: nonce, authenticating: aad)` matches BouncyCastle's `AEADParameters` exactly. |
| AVAudioEngine input tap delivers non-48 kHz buffer on iOS hardware that doesn't support 48 kHz input natively | Low | Medium | Tap with explicit `AVAudioFormat(48 kHz, mono, S16)`; AVAudioEngine inserts an internal converter automatically per Apple docs. Alternative: tap at native rate + `AVAudioConverter` to 48 kHz. |
| JNA AVAudioEngine block forge for the tap callback has lifetime issues (same family as F-7 / F-8) | Medium | Medium | Reuse `AppleBlock.Keepalive` pattern + the F-7/F-8 fixes (LinkedList of in-flight keepalives, explicit `nw_release` equivalents for AV objects). |
| Mac App Store sandbox rejects `AVAudioEngine` without `com.apple.security.device.audio-input` entitlement | High (without entitlement) | High | Entitlement added in FP-14h-7 to `dist/apple/macappstore/macappstore.entitlements`. App Store-allowed entitlement; no review impact. |
| Splitting `:shared:voice-codec` into api/impl (F-13) breaks consumers across the repo | Medium | Medium | FP-14h-6 ships the split with `api(projects.shared.voiceCodecApi)` re-export from `:shared:voice-codec-impl`, AND a parallel `api(projects.shared.voiceCodecImpl)` from `:shared:voice` to preserve transitive resolution. Existing imports keep working. |
| Mic capture works in test fixture but fails in App Review under sandbox | Low | Critical | Pre-submission TestFlight ad-hoc build exercise (manual). Already standard fastlane lane in FP-14e. |
| iOS App Store rejects because `NSMicrophoneUsageDescription` string is too generic | Low | Medium | FP-14h-9 doc update includes the user-facing string review. Already set in FP-14 Info.plist. |

No "USER DECISION REQUIRED" items. The plan is unblocked.

---

## §13 Summary

- **Move map**: 23 files, 1 delete, 0 source changes outside moves.
- **New files**: 11 (2 crypto, 5 iOS audio, 4 Mac App Store JNA audio,
  2 client).
- **Licence impact**: zero new GPL surface; iOS + Mac App Store stay GPL-clean.
- **DAVE on App Store**: permanent `DaveUiState.Disabled`; no libdave, no
  MLS, no clean-room implementation. Existing `DaveDowngradeBanner` handles
  the user notification.
- **Slices**: FP-14h-2..h-9 with concrete file lists + acceptance criteria.
- **Risks**: 8 risks, all with mitigations; none block the plan.
- **User decisions outstanding**: NONE.

Next action: dispatch FP-14h-2 as a test-first impl slice (test-writer +
impl roles) per HARD RULE #1 step 5+6, with this report as the architect
contract.
