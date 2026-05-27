# VP8 end-to-end — SDP-driven codec selection + RFC 7741 RTP packetiser

Issue: [#26](https://github.com/JanDamek/puklic/issues/26). Follow-up to commit `05ab616`
(Phase 4.1 VP8 encoder). Brings the screen-share send pipeline to full codec parity for the
two codecs Discord advertises (H.264, VP8) so the negotiated codec from
`SessionDescription.video_codec` actually drives both the encoder backend AND the RTP
packetisation strategy.

## Components

| File | Role |
|---|---|
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/VideoFrameFragmenter.kt` | Strategy interface + `H264FrameFragmenter` adapter (Annex-B split + RFC 6184 FU-A). |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/Vp8Packetiser.kt` | RFC 7741 §4 single-octet descriptor VP8 packetiser (S bit on first packet, MTU-safe fragmentation). |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/screenshare/encoder/VideoCodec.kt` | Adds `payloadType()` mapping H264→0x65, VP8→0x67. |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayPayload.kt` | `VoiceSessionDescription` extended with optional `video_codec` String. |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayConnection.kt` | `SessionDescription` event now carries `videoCodec: String?`. |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/VideoRtpSender.kt` | Takes a `VideoFrameFragmenter` strategy; codec-agnostic. |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/screenshare/DefaultScreenShareClient.kt` | Accepts `videoCodec: VideoCodec`; selects fragmenter + payload type + encoder codec. |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/DefaultVoiceClient.kt` | Pipes `SessionDescription.videoCodec` through `chooseCodec(...)` into the screen-share client. |

## RFC 7741 single-octet descriptor

```
 0 1 2 3 4 5 6 7
+-+-+-+-+-+-+-+-+
|X|R|N|S|R| PID |
+-+-+-+-+-+-+-+-+
```

Selected profile: `X=0` (no extension), `N=0` (reference frame, libvpx-rt default),
`PID=0` (single partition, libvpx default), `R` bits reserved 0. Only `S` varies between
RTP packets of a single VP8 frame: `S=1` on the first packet, `S=0` on continuations.
Descriptor byte values: `0x10` (start) and `0x00` (continuation).

This is the simplest RFC-compliant VP8 packetisation that interoperates with the
libvpx-produced bitstream from `LibavVideoEncoder` (CBR, GOP-aligned, single partition,
no temporal layers). PictureID / TL0PICIDX / TID extensions are deliberately not used —
they would add bytes without benefit for the screen-share use case.

## Strategy interface

`VideoFrameFragmenter.fragment(EncodedFrame): List<Fragment>` is the seam between codec
and transport. `VideoRtpSender` no longer knows about NAL units or VP8 descriptors —
it just iterates the returned fragments, writing RTP headers (marker bit on the
fragment whose `end=true`) and encrypting with `aead_xchacha20_poly1305_rtpsize`.

For H.264 the adapter does Annex-B split → FU-A fragment per NAL → concatenate, marking
the final fragment of the final NAL as `end=true` so the marker bit lands on the last
RTP packet of the frame (RFC 6184 convention preserved from the previous impl).

## Codec selection plumbing

1. Op 1 SelectProtocol advertises both codecs from `DEFAULT_CODECS` (H264 priority 1000,
   VP8 priority 2000 — lower wins per Discord convention).
2. Discord replies with Op 4 SessionDescription including `video_codec: "H264"` or
   `"VP8"` (newly parsed field — was silently dropped before).
3. `VoiceGatewayConnection` lifts the field into the `SessionDescription` event.
4. `DefaultVoiceClient.installScreenShareClient` runs `chooseCodec(listOf(name))` and
   passes the resolved `VideoCodec` into `DefaultScreenShareClient`.
5. `DefaultScreenShareClient.start` picks the matching `VideoFrameFragmenter` strategy
   and the matching `payloadType()` for the RTP header. The encoder factory receives
   the codec so libavcodec uses `libx264` or `libvpx` accordingly.

When `video_codec` is missing (older fixture servers, partial server builds) the client
falls back to `VideoCodec.H264` — our highest-priority offer. `chooseCodec(emptyList())`
would have returned `null`; an explicit fallback avoids a hard failure during session
setup for that benign case.

## Test coverage

New tests:

- `Vp8PacketiserTest` (3) — single-packet S=1, multi-packet S=1/S=0 split, MTU bound.
- `VideoCodecPayloadTypeTest` (2) — H264→0x65, VP8→0x67.
- `VideoRtpSenderVp8Test` (2) — round-trip small + fragmented VP8 frame, asserts
  payload type, sequence numbers, marker bit on last packet.
- `VoiceGatewayConnectionTest.session_description_exposes_negotiated_video_codec` (1) —
  Op 4 JSON with `video_codec: "VP8"` surfaces on the event.

All pre-existing tests still pass after `VideoRtpSender` refactor (the H.264 path is
covered by the unchanged `VideoRtpSenderTest` and `H264FragmenterTest`).

## HARD RULE #2 compliance

No temporary code. No "VP8 works for browser only" / "behind a flag" / TODO. The whole
chain — `chooseCodec` → encoder backend → packetiser → RTP payload type — is wired and
exercised by tests. Either codec works end-to-end the moment Discord picks it.
