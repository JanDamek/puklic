# FP-14h — AppleNativeVoiceClient: conceptual plan (HARD RULE #2 stop)

Status: architect-only. **No code changes in this session.** Issue #62.

References:
- HARD RULE #2 (`<repo>/CLAUDE.md`) — NEVER TEMPORARY, ALWAYS CONCEPTUAL
- 2026-05-29-fp14h-1-v2-voice-gateway-redesign.md
- 2026-05-29-fp14h-2{,a,b,c,d,e}-implementation.md — voice-codec extraction series
- 2026-05-29-fp14h-3-implementation.md — Apple audio capture/playback (shipped commit 5b3fa7d)
- 2026-05-29-full-feature-parity.md §3 — App Store ships without DAVE

## §1 Why a stop here

Issue #62 asks for an end-to-end Apple VoiceClient wired into `IosDependencyGraph` +
`MacAppStoreMain`. After audit the conceptual answer is **not** to write a parallel
`AppleNativeVoiceClient` mirror of `DefaultVoiceClient`; it is to **promote
`DefaultVoiceClient` itself from `:shared:voice/jvmMain` to
`:shared:voice-codec/commonMain`** with GPL-only collaborators (DAVE, GPL screenshare)
injected as optional dependencies.

Reasoning:

1. `:shared:voice-codec/commonMain` already owns 1 883 lines of voice machinery
   (`VoiceGatewayConnection` 513, `CapturePipeline`, `JitterBuffer`, `VoicePacketCodec`,
   `VoicePacketDispatcher`, `IpDiscovery`, RTP, Vp8/H264, AnnexB). The voice gateway
   transport + UDP factory abstractions live there.
2. `DefaultVoiceClient` (728 lines) is the *missing* commonMain piece. Its hard JVM
   surfaces are:
   - `java.util.concurrent.atomic.AtomicInteger` → `kotlinx.atomicfu`
   - `java.util.concurrent.ConcurrentHashMap` (DAVE encryptor map) → DAVE-only, lives
     behind the optional `DaveSessionFactory` collaborator
   - `JvmVoiceUdpTransportFactory` → already abstracted, just use the
     `VoiceUdpTransportFactory` already passed in from DI
   - `JavaSound*` audio → already abstracted as `audioCapture()`/`audioPlayback()`
     `expect`/`actual` (FP-14h-2e + FP-14h-3 landed)
   - `DaveSession`, `mlsClient`, `FrameEncryptor`/`FrameDecryptor` →
     `:shared:voice-dave` (libdave GPL) — inject as nullable
     `DaveSessionFactory?`; null on App Store
   - `DefaultScreenShareClient` (GPL FFmpeg path) → inject as
     `ScreenShareClientFactory?`; on App Store wire a future Apple-native
     `ScreenCaptureKit + VideoToolbox` factory (a separate slice — FP-14i)
   - `IncomingVideoPipeline` + `PlaybackPipeline` (jvmMain) → need promotion to
     commonMain alongside DefaultVoiceClient (~250 LoC total)
3. Building a separate `AppleNativeVoiceClient` would duplicate 700+ lines of voice
   orchestration that diverges from `DefaultVoiceClient` over time. That is exactly
   the "ship half now, finish later" pattern HARD RULE #2 forbids.
4. The DI flip in `IosDependencyGraph` + `MacAppStoreMain` from `NoOpVoiceClient` to
   the promoted `DefaultVoiceClient` is a 5-line change once promotion lands.

## §2 Proposed slice decomposition (replacement for the 9-slice list in issue #62)

| Slice | Scope | LoC est. | Tests |
|-------|-------|----------|-------|
| FP-14h-4 | Promote `IncomingVideoPipeline` + `PlaybackPipeline` from `:shared:voice/jvmMain` to `:shared:voice-codec/commonMain`. Pure code move, no behaviour change. | ~250 | move existing tests |
| FP-14h-5 | Introduce `DaveSessionFactory` interface (in `:shared:voice-codec/commonMain`) + `ScreenShareClientFactory` interface. Move DAVE branches in `DefaultVoiceClient` behind `dave: DaveSessionFactory?`. JVM build keeps wiring `LibdaveSessionFactory`; App Store passes `null`. | ~200 | DAVE-null path test |
| FP-14h-6 | Promote `DefaultVoiceClient` from `:shared:voice/jvmMain` to `:shared:voice-codec/commonMain`. Replace `AtomicInteger` with `kotlinx.atomicfu.AtomicInt`. Replace `ConcurrentHashMap` (DAVE-only) with non-concurrent map inside the DAVE factory's own scope (single-threaded session scope). | ~728 (move) | re-route `DefaultVoiceClientDmTest` + `DefaultVoiceClientIncomingTest` to commonTest, run on JVM + iOS targets |
| FP-14h-7 | Address F-7..F-13 critic findings (Channel lifetime leaks, refcount leaks, JMM happens-before, libopus close race). Each fix lands with a regression test. | ~150 | dedicated test per finding |
| FP-14h-8 | Wire `:shared:voice-codec`'s `DefaultVoiceClient` into `IosDependencyGraph` + `MacAppStoreMain` with `dave = null` + `screenShareFactory = null` (until FP-14i). Flip `VoiceFeatureFlag.ENABLED` stays true; the platform DI graphs simply switch from `NoOpVoiceClient` to `DefaultVoiceClient(...)`. | ~30 | smoke: graph construct → `voiceClient.state.value == Idle` |
| FP-14h-9 | F-16..F-21 NIT cleanups + `CLAUDE.md` Platforms table flip + `phases.md` Slice 15 [x]. | ~50 | n/a |
| FP-14i (separate issue) | Apple-native screenshare client (ScreenCaptureKit on macOS, ReplayKit on iOS) implementing `ScreenShareClient` from `:shared:voice-api`. Wired into `:shared:voice-codec/commonMain` `DefaultVoiceClient` via the `ScreenShareClientFactory` introduced in FP-14h-5. | ~600 | screencast contract tests |

Total for #62 acceptance (FP-14h-4..h-9): ~1 400 LoC, ~6 commits, **estimate 2-3
agent sessions**.

## §3 Why this session ships zero code

Per HARD RULE #2 the only honest deliverables this session could ship are FP-14h-4
**or** an architect plan. FP-14h-4 (promote pipelines) is a pure code-move that, taken
alone without FP-14h-5..h-8, leaves `:shared:voice-codec` in a half-promoted state
where `DefaultVoiceClient` still lives in JVM and references commonMain pipelines —
not a HARD-RULE-#2 violation per se, but a low-value deliverable absent the rest of
the chain.

The task brief explicitly authorises a HARD-RULE-#2-honouring stop:

> If the wire-up turns out to be larger than fits in one agent run (≥ 2-3 hours),
> stop at a HARD-RULE-#2-honouring boundary: skeleton + tests committed, DI wire-up
> done but feature-flagged to NoOp by default. Post a follow-up plan in the issue.

A "skeleton AppleNativeVoiceClient" that returns stub state, however, is precisely
the "stub method returning fake data 'until real impl'" pattern HARD RULE #2
forbids. The feature-flag-to-NoOp variant is "configuration flag toggling between
half-built feature and old behavior" — also forbidden.

Therefore the HARD-RULE-#2-honouring boundary here is **architect plan only**, with
the next session picking up FP-14h-4..h-8 as a coherent landing.

## §4 Risks / open questions

- **Coroutines `runBlocking` on Kotlin/Native main thread.** Already validated in
  FP-14h-3 §4.2 — capture pipeline runs on `Dispatchers.Default`, not main.
- **`kotlinx.atomicfu` availability in `:shared:voice-codec`.** Module already
  depends on `kotlinx-coroutines-core`; atomicfu is the standard KMP atomic primitive
  and is a 1-line `commonMain` dep addition.
- **Test coverage of the DAVE-null path.** Today every voice test exercises a code
  path that assumes DAVE. FP-14h-5 must add an explicit non-DAVE smoke test before
  FP-14h-8's DI flip.
- **`MainGatewayBridge` interface JVM-ness.** Already pure Kotlin + `SharedFlow` +
  `suspend fun` — no JVM coupling. Promotion-ready.

## §5 Acceptance criteria for closing #62 (unchanged)

Same as issue body, but routed through the slice plan in §2 rather than the
original 9-slice list. The original FP-14h-1..h-2e slices already landed; this
plan replaces FP-14h-4..h-9.

## §6 Sub-issues closeable after this report

None — this report unblocks the next session but resolves no sub-finding on its
own. F-7..F-13 / F-16..F-21 remain open until FP-14h-7 / FP-14h-9 land.
