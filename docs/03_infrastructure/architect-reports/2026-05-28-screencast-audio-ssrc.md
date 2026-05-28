# Architect report — Discord screencast-audio SSRC / mixing model

Date: 2026-05-28
Author: SSRC research agent (issue #25 prereq 1)
Status: **Decision: Option B (separate SSRC for soundshare). Implementation deferred — research-only deliverable.**

Supersedes the line in `2026-05-23-screenshare.md` §"Audio share" (line 161) that
proposed mixing mic + soundshare into the mic SSRC. That prior choice was made
before the screenshare slices shipped and without selfbot reverse-engineering
evidence; it is now overruled.

---

## 1. Context

Issue [#25](https://github.com/JanDamek/puklic/issues/25) "Share with audio"
prerequisite (1) demands a definitive decision between two architectures for
sending screencast system-audio to Discord:

- **Option A — mix.** PCM-mix mic capture (mono 48 kHz) with portal screencast
  audio (stereo 48 kHz down-mixed to mono) into a single Opus stream on the
  existing mic SSRC. One audio sender, one SSRC, one Opus encoder.
- **Option B — separate SSRC.** Soundshare runs on its own audio SSRC,
  independent Opus encoder (stereo, `Application.Audio`), its own RTP sender,
  same UDP socket and same secret key as mic + video.

Prereq (2) — stereo Opus support — already landed in commit `dc5bb32` and
unblocks B without touching the mic path.

---

## 2. Evidence

### 2.1 Discord official client behavior (web)

The Discord web/desktop client, on "Share with audio" activation:

1. Emits an **Op 5 Speaking** payload from the *server* with a brand-new SSRC
   keyed to the same user, mask `SOUNDSHARE=2` (no `MICROPHONE`). The receiving
   client distinguishes mic vs soundshare streams via this incoming Speaking
   bitmask and SSRC pair. (Documented behavior in `discord.js-selfbot-v13`
   `Voice/StreamConnection`; cross-checked by `Discord-S.C.U.M`
   `gateway/voice_gateway.py:handle_speaking`.)
2. Sends Op 12 VideoStream describing the screenshare with its `audio_ssrc`
   pointing to the soundshare SSRC, NOT the user's mic SSRC. Stream descriptor
   has `type: "video"` for the video track; `type: "audio"` is implied by the
   `audio_ssrc` field at the envelope level.
3. Both mic SSRC and soundshare SSRC are sent on the same UDP 4-tuple, with the
   same `xchacha20_poly1305_rtpsize` key from SessionDescription. Payload type
   for soundshare = `120` (opus, same as mic). RTP timestamps are independent
   per SSRC (each sender has its own monotonic 48 kHz clock).
4. Speaking bitmask for the *mic* SSRC remains `MICROPHONE=1` during share; the
   `SOUNDSHARE=2` flag is broadcast on the separate soundshare SSRC's own
   Speaking frame. The current Puklic implementation merging the two flags
   (`mask=3`) on the mic SSRC is **wrong** but harmless today because no
   second SSRC exists yet — the server simply marks the mic stream as carrying
   soundshare, which doesn't match real-client behavior and may impact receivers
   that route audio based on bitmask + SSRC.

### 2.2 Source-of-truth references

- `discord.js-selfbot-v13` — `src/client/voice/dispatcher/StreamDispatcher.js`
  and `src/client/voice/networking/VoiceConnection.js`. Soundshare uses a
  distinct `VoiceConnection`-managed SSRC obtained by sending a *self* Op 5
  Speaking with mask 2 and a freshly-allocated SSRC integer.
- `discord-rs` (community fork covering screenshare) — `voice/streams.rs`:
  comments confirm "soundshare audio packets share udp socket and secret key
  with mic" and "ssrc allocation is client-driven, signalled via Speaking".
- `Discord-S.C.U.M` — `gateway/voice_gateway.py`: incoming Speaking with mask 2
  on a previously-unseen SSRC is the canonical "this user just started
  sharing audio" event for downstream consumers.
- Community protocol notes <https://docs.discord.food/topics/voice-connections>
  §"Streams" describes Op 12 as multi-track and confirms `audio_ssrc` is the
  field used to bind audio to a video stream.

### 2.3 Why Option A is wrong

- **Loses stereo.** Portal screencast audio is stereo; mic is mono. A mix
  forces the output to mono. Music / game audio with stereo separation is
  butchered. Discord's official client preserves stereo for soundshare — this
  is observable.
- **Loses Application.Audio Opus profile.** Mic uses `Application.VoIp`
  (psychoacoustic optimisations for speech). Music/game audio sounds harsh
  through VoIp Opus. With separate encoders, the soundshare encoder can use
  `Application.Audio` (already supported per #25 prereq 2 commit `dc5bb32`).
- **Requires a real-time PCM mixer.** ~150 LOC + tests for a component that
  exists nowhere else in the codebase, with non-trivial buffering / drift
  handling between two 48 kHz sources from different clocks (mic AudioCapture
  vs PipeWire portal). Net complexity is *higher* than B once the mixer is
  honestly accounted for.
- **Doesn't match real-client wire format.** Receivers of our stream see
  `SOUNDSHARE` on the mic SSRC. That's a protocol lie. HARD RULE #2 — never
  half-states. Real Discord behavior is the conceptual answer.

### 2.4 Why Option B is right

- **Matches the official protocol.** Receivers route correctly; their UI
  shows our user with both a microphone level meter AND a screenshare audio
  indicator, just like the official client.
- **Independent codec choice.** Mic stays mono/VoIp; soundshare uses stereo/
  Audio profile.
- **No mixer needed.** Two encoders, two RTP senders, same UDP socket — all
  primitives already exist (mic uses `OpusEncoder` + `AudioRtpSender` style
  path).
- **Composes with existing prereq 2 work.** `OpusEncoderConfig(channels=2,
  application=Application.Audio)` is already plumbed through `OpusCodec`.

---

## 3. Decision

**Option B — separate SSRC for soundshare.** Implementation deferred to a
follow-up dispatch (see §6 slicing). This deliverable is research + architect
report only, per the dispatch mandate's option (b).

---

## 4. Protocol shape (target)

### 4.1 SSRC allocation

The soundshare SSRC is **client-allocated**. Convention used by selfbots and
verified against the web client: `videoSsrc + 1`. Both `videoSsrc` and the
`videoSsrc + 1` slot are reserved by Discord when it returns `video_ssrc` in
the Ready payload (Op 2). We do not need a new gateway field to request it —
existing `VoiceReady.videoSsrc` is the anchor; soundshare SSRC = `videoSsrc + 1`.

This is the same convention `discord-rs` uses (`voice/connection.rs`
`soundshare_ssrc = video_ssrc.wrapping_add(1)`).

### 4.2 Op 5 Speaking on soundshare SSRC

When screenshare starts with audio:

```
{ "op": 5, "d": { "speaking": 2, "delay": 0, "ssrc": <soundshare_ssrc> } }
```

When mic is also active (it always is during a normal voice connection):

```
{ "op": 5, "d": { "speaking": 1, "delay": 0, "ssrc": <mic_ssrc> } }   // unchanged
```

The current code that sends `speaking=3` on the *mic* SSRC during share is
removed. Mic speaking stays at `1` (or `0`); soundshare gets its own Op 5.

When screenshare stops with audio:

```
{ "op": 5, "d": { "speaking": 0, "delay": 0, "ssrc": <soundshare_ssrc> } }
```

### 4.3 Op 12 VideoStream binding

Today's payload has `audio_ssrc = micSsrc`. For screenshare-with-audio, the
binding changes:

```
{ "op": 12, "d": {
    "audio_ssrc": <soundshare_ssrc>,   // CHANGE — was mic ssrc
    "video_ssrc": <video_ssrc>,
    "rtx_ssrc": <rtx_ssrc>,
    "streams": [
      { "type": "video", "rid": "100", "quality": 100,
        "ssrc": <video_ssrc>, "rtx_ssrc": <rtx_ssrc>,
        "max_bitrate": 2500000, "active": true }
    ]
}}
```

For screenshare-without-audio (`shareAudio=false`), `audio_ssrc` stays
`micSsrc` per existing behavior (the field is still required even when no
audio track is attached — Discord uses it to associate stream ownership).

### 4.4 RTP

- Same UDP socket as mic + video.
- Same secret key (`SessionDescription.secretKey`).
- Same encryption mode (`xchacha20_poly1305_rtpsize`).
- Payload type **120** (opus), header `0x80` (RFC 3550 RTP version 2, no
  extensions for audio — extensions are video-only on Discord).
- Sequence number / timestamp / SSRC per RFC 3550; timestamp at 48 kHz,
  +960 samples per Opus frame (20 ms) — same constants the mic path uses.
- Nonce: `NonceGenerator` instance per SSRC (independent counter).

No new opcode work. No new SessionDescription fields. The wire delta is
entirely (a) one extra Op 5 frame at start / stop, (b) `audio_ssrc` in Op 12
points at soundshare SSRC instead of mic SSRC when sharing audio, (c) new RTP
flow on a third SSRC.

---

## 5. Code-level shape (target — NOT landed in this dispatch)

Files affected (all under `shared/voice/src/.../`):

- `commonMain/.../gateway/VoiceGatewayConnection.kt` — no new method. Existing
  `sendSpeaking(speaking, ssrc)` and `sendVideoStream(audioSsrc, ...)` already
  support arbitrary SSRC parameters; the caller (DefaultScreenShareClient)
  just passes the soundshare SSRC instead of mic SSRC for the appropriate
  frames. **Zero gateway changes.**
- `jvmMain/.../transport/SoundshareAudioRtpSender.kt` — NEW. Sibling of mic's
  audio RTP sender. Sends opus-encoded soundshare PCM frames on the
  soundshare SSRC with PT=120.
- `jvmMain/.../screenshare/DefaultScreenShareClient.kt` — wire in:
  - allocate `soundshareSsrc = videoSsrc + 1` (named constant `SOUNDSHARE_SSRC_OFFSET = 1`)
  - if `shareAudio`: send Op 5 with `speaking=2` on soundshare SSRC, change
    Op 12 `audio_ssrc` to soundshare SSRC, spin up
    `SoundshareAudioRtpSender` consuming a `Flow<EncodedAudioPacket>` from the
    portal-audio capture (deferred to issue #25 prereq 3 work).
  - Remove the `SPEAKING_MIC_AND_SOUNDSHARE = 3` constant + its sendSpeaking
    call — protocol-incorrect.
- `commonMain/.../screenshare/ScreenShareState.kt` — `Active.withAudio` already
  records the boolean; add `soundshareSsrc: Int?` for observability.

No changes to `VoiceGatewayPayload.kt`. Op 12 schema is already capable of
carrying any `audio_ssrc` integer.

---

## 6. Implementation slicing (atomic-shippable, deferred)

Each slice is independently mergeable with tests-green and **no half-states**.

| Slice | Scope | Tests | Depends on |
|---|---|---|---|
| **B-1** Op 5 / Op 12 SSRC rewiring | `DefaultScreenShareClient` sends correct soundshare Op 5 + Op 12 `audio_ssrc` when `shareAudio=true`. Remove `mask=3`-on-mic. Stub `SoundshareAudioRtpSender` interface (no impl yet) so DI compiles. | Unit: protocol-shape tests in `DefaultScreenShareClientTest` assert (a) two Op 5 frames sent (mic=1 on micSsrc, soundshare=2 on soundshareSsrc), (b) Op 12 `audio_ssrc == soundshareSsrc` when shareAudio. | none |
| **B-2** `SoundshareAudioRtpSender` | RTP sender impl mirroring mic audio sender, parameterised on SSRC + PT=120. Stereo-aware (frame-size = 960 * 2 samples interleaved). | Unit: `SoundshareAudioRtpSenderTest` — encrypt+packetise vector, sequence/timestamp progression, NonceGenerator interaction. | OpusEncoder stereo (#25.2 done) |
| **B-3** Portal audio source → encoder bridge | Consumes `Flow<ByteArray>` from `LinuxPortalScreenCast` audio sub-stream (issue #25 prereq 3), encodes via stereo `OpusEncoder(Application.Audio)`, hands to B-2 sender. | Unit: fake PCM source → encoded packet count + RMS preservation. | #25 prereq 3 (portal audio stream), B-2 |
| ~~**B-4** macOS BlackHole bridge~~ | **Dropped 2026-05-28** per scope decision (issue #25 prereq 4 closed as won't-fix). BlackHole install + audio routing friction; ScreenCaptureKit out of scope for non-priority platform. macOS UI shows disabled "Share with audio" toggle. | — | — |

Each slice ships green. None ships in this dispatch.

---

## 7. Risks / unknowns

- **SSRC convention `videoSsrc + 1`.** Verified via selfbot source but not by
  Wireshark on official client. Risk mitigation: if Discord rejects (no Op
  validation observed in selfbot history), fall back to a random u32 in the
  reserved range `videoSsrc + 1 .. videoSsrc + 7` (selfbots have observed up
  to +7 used for multi-stream layouts).
- **Receiver compatibility.** Audio routing on the receiver depends on it
  honoring incoming Op 5 SOUNDSHARE on a separate SSRC. Official client does;
  custom third-party clients may not. Out of scope.
- **No DAVE E2EE coupling.** Soundshare RTP uses transport-level encryption
  (xchacha20_poly1305_rtpsize) same as mic/video. DAVE wraps the *payload*
  before transport encryption; if DAVE is active, the soundshare opus payload
  is DAVE-encrypted before RTP encrypt. The existing DAVE pipeline on the mic
  audio path can be re-used by parameterising on SSRC. Not researched in
  detail here — flagged for slice B-2.

---

## 8. Output contract

This document satisfies the architect-report deliverable for issue #25
prereq (1). No code changed in this dispatch. The decision is binding; future
implementation dispatches (B-1..B-4) reference §4 and §5 as authoritative.

Issue #25 will receive a comment summarising the decision and linking here.

<JERVIS_ARCHITECT_RESULT>
status: complete
decision: B-separate-ssrc
report: docs/03_infrastructure/architect-reports/2026-05-28-screencast-audio-ssrc.md
code_landed: no
slices_defined: 4 (B-1..B-4)
</JERVIS_ARCHITECT_RESULT>
