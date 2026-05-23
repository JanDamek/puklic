# Puklic Phase 4 — Screenshare Architecture Plan (2026-05-23)

## Summary

Phase 4 extends `:shared:voice` rather than spawning a new module: a `ScreenShareClient` facade riding on the same UDP socket, AEAD encryptor, and voice gateway as audio, using a second SSRC (`Ready.video_ssrc`) and a video RTP payload type with H.264 FU-A fragmentation and 90 kHz timestamps. Shortest path to a working demo is an **ffmpeg subprocess** (avfoundation → libx264 → H.264 stdout) read by a Kotlin coroutine that FU-A-fragments and forwards to the existing transport; defers all native bindings (no AVFoundation JNI, no libx264 JNA) and works cross-platform once ffmpeg is installed. Six implementation slices cover macOS MVP; Wayland portal + PipeWire VP8 lands in Phase 4.1.

## 1. Goal & non-goals

**Goal (4.0 macOS-first MVP).** User clicks "Share Screen" in voice status bar, picks a display/window, others in voice channel see it in official Discord.

**Non-goals:**
- No inline video render inside Puklic (no decode of remote streams)
- No simulcast/SVC/adaptive bitrate
- No FEC/RTX/NACK
- No camera (go-live only)
- No DAVE video (reuse audio AEAD key)
- No Windows; Linux Wayland → Phase 4.1

## 2. Module structure

Extend `:shared:voice`. Same UDP socket, AEAD state, gateway, heartbeat.

```
screenshare/
├── ScreenShareClient.kt        # commonMain facade
├── ScreenShareState.kt
├── ScreenSource.kt
├── VideoCodecChoice.kt
├── source/
│   ├── ScreenSourceEnumerator.kt   # expect
│   └── ScreenSourceEnumerator.jvm.kt  # macOS ffmpeg -list_devices parser
├── encoder/
│   ├── VideoEncoder.kt             # expect
│   └── FfmpegVideoEncoder.jvm.kt   # spawns ffmpeg, parses Annex-B / IVF
└── transport/
    └── VideoRtpSender.kt           # video PT + video SSRC, reuses Encryptor + UdpRtpTransport
```

Public API additions:

```kotlin
public interface ScreenShareClient {
    public val state: StateFlow<ScreenShareState>
    public suspend fun listSources(): List<ScreenSource>
    public suspend fun start(source: ScreenSource, shareAudio: Boolean)
    public suspend fun stop()
}

public sealed interface ScreenShareState {
    public data object Idle : ScreenShareState
    public data class Negotiating(val source: ScreenSource) : ScreenShareState
    public data class Active(val source: ScreenSource, val videoSsrc: Int, val withAudio: Boolean) : ScreenShareState
    public data class Failed(val reason: String) : ScreenShareState
}

public sealed interface ScreenSource {
    public val id: String
    public val displayName: String
    public data class Monitor(override val id, override val displayName, val widthPx: Int, val heightPx: Int) : ScreenSource
    public data class Window(override val id, override val displayName, val appName: String, val widthPx: Int, val heightPx: Int) : ScreenSource
}

public enum class VideoCodecChoice(public val payloadType: Int, public val rtpClockHz: Int) {
    H264(101, 90_000), VP8(103, 90_000)
}
```

`VoiceClient.screenShare: ScreenShareClient` added.

## 3. Tech choices

| Concern | Choice | Why |
|---|---|---|
| Capture+encode | **ffmpeg subprocess, stdout pipe** | Zero JNA/JNI. macOS `-f avfoundation`; Linux 4.1 `-f pipewire`. |
| Discovery | `which ffmpeg` + `PUKLIC_FFMPEG` env override | No bundled binary 4.0; install hint if missing. |
| Source picker (macOS) | `ffmpeg -f avfoundation -list_devices true` parser; window enum via `osascript` (4.0.1) | No AVFoundation JNI. |
| Source picker (Linux 4.1) | `org.freedesktop.portal.ScreenCast` D-Bus via `gdbus`/JNA `libdbus-1` | Portal returns PipeWire node id. |
| Codec | **H.264 baseline via libx264** v1 | Discord prefers H.264 on macOS clients. VP8 with Linux. |
| RTP video | Reuse `UdpRtpTransport` + `Encryptor`; `RtpPacket` gains PT + marker params | Single socket per Discord design. |
| Negotiation | Extend `SelectProtocol.codecs` with H264/101 + VP8/103 | Mirrors Acheron's `Codec` DTO. |
| Audio share macOS | BlackHole / multi-output device; pipe to second `-f avfoundation` input → existing Opus pipeline with `SOUNDSHARE` flag | macOS has no system loopback. |

## 4. Discord voice gateway video protocol

Verified from Acheron `VoiceEntities.hpp`:

- **Op 2 Ready** extends with `video_ssrc: u32`. Today dropped — wire it.
- **Op 1 SelectProtocol** `data.codecs` array. We send:
  ```json
  "codecs": [
    {"name":"opus","type":"audio","payload_type":120,"priority":1000,"encode":true,"decode":true},
    {"name":"H264","type":"video","payload_type":101,"rtx_payload_type":102,"priority":1000,"encode":true,"decode":false},
    {"name":"VP8","type":"video","payload_type":103,"rtx_payload_type":104,"priority":2000,"encode":true,"decode":false}
  ]
  ```
  `decode:false` — we don't render incoming video in 4.0, so Discord won't waste bandwidth.
- **Op 12 VideoStream** (C→S): `{"audio_ssrc":<u32>,"video_ssrc":<u32>,"rtx_ssrc":<u32>,"streams":[{"type":"video","rid":"100","quality":100,"ssrc":<video_ssrc>,"rtx_ssrc":<rtx_ssrc>,"max_bitrate":2500000,"active":true}]}`. Confirm shape empirically (Wireshark on official client).
- **Op 5 Speaking** mask: `SOUNDSHARE=2` when sharing system audio. Video itself is not a Speaking flag.

## 5. RTP for video

Same wire framing as voice. Differences:

| Field | Audio | Video |
|---|---|---|
| Payload type byte | `0x78` (120) | `0x65` (101) H.264 / `0x67` (103) VP8 |
| SSRC | `Ready.ssrc` | `Ready.video_ssrc` |
| Timestamp clock | 48 kHz (+960/20 ms) | 90 kHz (+3000 at 30 fps) |
| Marker bit | always 0 | **1 on last RTP packet of frame** |
| MTU | one Opus frame fits | H.264 NAL > 1200 B → FU-A (RFC 6184 §5.8); VP8 descriptor byte (RFC 7741) |

`RtpPacket.writeHeader(seq, ts, ssrc, payloadType = PAYLOAD_TYPE_OPUS, marker = false)`. Encryption + AAD unchanged. Same AEAD key for audio+video (Discord design).

Sequence space **separate per SSRC**. Nonce counter **shared** across audio+video on the single Encryptor.

## 6. ffmpeg invocations

**macOS monitor share (no audio):**
```
ffmpeg -hide_banner -loglevel error -nostdin \
  -f avfoundation -capture_cursor 1 -framerate 30 -pixel_format nv12 \
  -i "<screen-idx>:none" \
  -vf "scale=w=min(1920\,iw):h=-2" \
  -c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline \
  -x264-params "keyint=60:min-keyint=60:scenecut=0:repeat-headers=1" \
  -b:v 2500k -maxrate 2500k -bufsize 1250k \
  -pix_fmt yuv420p -f h264 pipe:1
```
- `repeat-headers=1` — SPS/PPS prefix every keyframe (mid-join decode works)
- `keyint=60` — keyframe every 2 s

**macOS with audio (BlackHole):**
```
ffmpeg ... -f avfoundation -i "<screen-idx>:<blackhole-idx>" ... \
  -map 0:v:0 -map 0:a:0 \
  -c:v libx264 ... -f h264 pipe:1 \
  -c:a libopus -b:a 64k -application audio -frame_duration 20 -f data pipe:3
```
Two pipes; coroutine per pipe.

**Linux Wayland (4.1):**
```
ffmpeg -f pipewire -i <node-id> -framerate 30 \
  -c:v libvpx -deadline realtime -cpu-used 8 -b:v 2500k -f ivf pipe:1
```

Bitrate ladder v1: 2500 kbps @ 1080p30, 1500 kbps @ 720p30 fallback.

## 7. Source picker

**macOS** — parse `ffmpeg -f avfoundation -list_devices true -i "" 2>&1`. Indices = `-i "<idx>:..."`. Window enum (4.0.1) via `osascript`. Privacy prompt triggered automatically; document `Settings → Privacy → Screen & System Audio Recording`.

**Linux (4.1)** — portal D-Bus: `CreateSession → SelectSources(types=monitor|window) → Start` via `gdbus` subprocess. Portal returns PipeWire node fd + id.

## 8. Audio sharing

macOS — no first-class loopback. Picker offers BlackHole route (install BlackHole 2ch + Multi-Output Device). Per-app capture via ScreenCaptureKit = native code, defer.

Linux 4.1 — PipeWire portal returns audio with video when `types=monitor` + `share_audio=true`. Trivial.

Mic + share mixed into same Opus stream + same voice SSRC. Speaking bitmask `MICROPHONE|SOUNDSHARE=3` during share.

## 9. UI

Inside existing `VoiceStatusBar`:
- New monitor/share icon button between gear and hangup. Disabled unless `Connected`.
- Opens `ScreenSharePickerDialog`: Screens / Windows tabs, thumbnail grid (one-shot ffmpeg frame grabs cached at `XDG_CACHE_HOME/puklic/screen-thumbs/`), "Share system audio" checkbox.
- When `Active`: button red + "Sharing"; click stops.
- No incoming-video render in 4.0.

## 10. Implementation slices (macOS MVP — 6 slices)

1. **DTO + protocol scaffold.** `video_ssrc` on Ready DTO. Extend `Codec` + `SelectProtocol.codecs`. Add `Op12VideoStream` payload. DTO test only.
2. **Video RTP framing.** Widen `RtpPacket` (PT + marker). `VideoRtpSender(udp, encryptor, videoSsrc, codec)` with H.264 Annex-B → FU-A NALU fragmentation. Unit-test FU-A round-trip.
3. **Ffmpeg encoder facade.** `FfmpegVideoEncoder` spawns process, parses Annex-B NAL boundaries (`00 00 00 01`), emits `EncodedFrame` per NAL at 30 fps. Stderr drained; process death → `Failed`. Unit-test with `testsrc` lavfi 5 s.
4. **Source enumerator (macOS).** `ffmpeg -list_devices` parser. Window enum stub (returns empty). Integration test gated by `puklic.tests.macos=true`.
5. **Wire it up.** `ScreenShareClient.start` → Op 12, spawn ffmpeg + VideoRtpSender, → `Active`. `stop()` → Op 12 `active:false` + kill ffmpeg. Smoke: official Discord on second machine renders our share.
6. **UI + audio share.** Picker dialog, status-bar button, BlackHole detection, audio mixing in CapturePipeline. Manual: share with Music playing, confirm video+audio at viewer.

Linux/Wayland portal + PipeWire = Phase 4.1.

## 11. Risks

1. **Op 12 wire shape undocumented** — Acheron only reads `video_ssrc`. Verify empirically (Wireshark + known voice key) before slice 5.
2. **macOS screen-recording permission** — first ffmpeg-avfoundation invocation prompts; if denied stderr is opaque. Match stderr → user message.
3. **No NACK / single MTU** — Wi-Fi loss → visible artifacts. Acceptable v1; document. RTX → 4.2.
4. **Nonce-counter shared audio+video** — ~200 pps combined; wrap at 2^32 ≈ 0.68 yr. Phase 3 disconnect-on-wrap remains on combined counter.
5. **Ffmpeg version skew** — `-f pipewire` requires ≥ 6.1. Minimum supported ffmpeg = 6.1; gate at startup.

## 12. Addendum 4.0.1 — macOS window picker (2026-05-23)

Slice 4 originally landed monitors-only. 4.0.1 adds window enumeration on macOS:

- `AppleScriptWindowEnumerator` runs `osascript` and returns visible `App|Title` records
- `AppleScriptWindowParser` (pure, unit-tested) turns the raw text into
  `ScreenSource.Window` entries with synthetic ids (`win:<index>`)
- `ScreenSharePickerDialog` gains a Screens / Windows tab pair
- Capture limitation: avfoundation has no per-window input, so when the user picks a window
  the encoder falls back to fullscreen capture of monitor 0. The picker shows a small
  caption noting this. Per-window capture via ScreenCaptureKit is deferred to 4.0.2.
- Privacy: `osascript` against `System Events` requires Automation permission; on first run
  macOS prompts. Denial → empty window list (graceful).

## 13. Phase 4.2 — incoming video receive (2026-05-23)

Slice 5 "view incoming" originally deferred to a later milestone is now implemented (H.264
only; VP8 still dropped). New modules under `:shared:voice/jvmMain`:

- `transport/VoicePacketDispatcher.kt` — single owner of the UDP receive loop. Reads each
  packet once, peeks at RTP byte 1 payload type (no decrypt), and fans out to two
  `Channel<ByteArray>` queues (audio cap 64, video cap 128). Decouples the playback and
  video pipelines so they can co-exist on the same UDP socket without contending on
  `transport.receive()`.
- `transport/H264Depacketizer.kt` — inverse of `H264Fragmenter`. Reassembles FU-A
  fragments (S/M/E flags), STAP-A aggregations, and Single NALs into Annex-B framed
  access units. Marker bit closes the access unit.
- `codec/H264Decoder.kt` — in-process libavcodec H.264 decoder (JavaCPP ffmpeg-gpl
  bundle, no system dep). YUV → RGBA via `swscale`. One decoder per remote SSRC; emits
  `DecodedFrame(rgba, width, height)`.
- `pipeline/IncomingVideoPipeline.kt` — wires dispatcher → AEAD decrypt (separate
  `VoicePacketCodec` instance, same secret key, decode-only — Discord uses independent
  nonce counters per SSRC) → depacketizer → decoder → `StateFlow<Map<ssrc, DecodedFrame>>`.

`PlaybackPipeline` gains a `packetSource: (suspend () -> ByteArray)? = null` constructor
parameter; when null the legacy single-consumer transport read is used (keeps
`PlaybackPipelineTest` green). `DefaultVoiceClient` now constructs the dispatcher and feeds
both pipelines from it.

Public API addition (`PublicApi.kt`):

- `data class IncomingVideoFrame(rgba, width, height)` — UI-facing decoded frame type.
- `VoiceClient.incomingVideo: StateFlow<Map<Int, IncomingVideoFrame>>` — latest decoded
  frame per remote video SSRC. Empty when nobody is screensharing.

UI (`:shared:compose-ui/jvmMain`): new `IncomingVideoPane` renders each frame as an
`Image` via `BufferedImage.TYPE_INT_ARGB` → `toComposeImageBitmap()`. `VoiceDock`
wraps the existing status bar in a `Column` so the pane sits above the dock when
non-empty. Performance: RGBA → ARGB conversion at 30 fps for 1080p is the bottleneck;
Skia direct-path optimisation deferred.

Slice deltas vs §10 plan: dispatcher pattern replaces the "PlaybackPipeline reads
non-Opus" idea — cleaner separation, no AEAD counter pollution. UI is a simple stacked
list, not a grid — grid + speaker focus is a later UX iteration.
