# FP-11 — iOS ReplayKit Broadcast Extension target + App Group IPC bridge

**Date**: 2026-05-29
**Authors**: Claude (architect + impl)
**Status**: Step 2 design + Step 5/6 implementation in same slice (pure plumbing — pre-approved blanket; UX placement deferred to FP-12)
**Slice**: FP-11 of `2026-05-29-full-feature-parity.md` §7
**Issue**: #51

## 1. Goal

Add the ReplayKit Broadcast Upload Extension Xcode target to the existing `iosApp/` project and the matching Kotlin/Native bridge in `:shared:screencast` iosMain so that FP-12 can plug an `IosScreenCapture : ScreenCapture` implementation on top of a working, signal-driven frame pipeline. This slice ships:

1. A second Xcode target `PuklicBroadcastUpload` (bundle id `cz.damek.puklic.app.broadcast`) embedded into the iosApp app bundle.
2. A working `RPBroadcastSampleHandler` subclass that writes BGRA video and Float32 PCM audio into an App Group memory-mapped ring file and posts a Darwin `notify_post` after every write.
3. A SwiftUI `BroadcastPickerView` (UIViewRepresentable around `RPSystemBroadcastPickerView`) — declared, not yet placed in any visible UI. FP-12 owns the placement (under HARD RULE #3 UX approval).
4. A Kotlin/Native `BroadcastExtensionBridge` (internal, iosMain) that mmaps the same App Group file, registers a `notify_register_dispatch` listener and emits BGRA frames + PCM audio buffers as cold `Flow`s.

Out of scope (FP-12): the `ScreenCapture.frames: Flow<EncodedFrame>` actual that takes BGRA from this bridge and runs it through the existing iOS H264 encoder; the picker placement in `:shared:compose-ui`.

## 2. Architecture decisions (LOCKED)

| Decision | Value | Rationale |
|---|---|---|
| App Group id | `group.cz.damek.puklic.app` | Apple groups must start with `group.`. Sharing the app's reverse-DNS prefix keeps Dev Portal mental model linear. |
| Extension bundle id | `cz.damek.puklic.app.broadcast` | Apple rule: extension bundle id must be prefixed by host app bundle id. Host = `cz.damek.puklic.app`. |
| Ring buffer slot count | 4 | Empirically enough for 60 fps consumer to drain without starving a 60 fps producer under transient GC pauses; at 1280×720 BGRA = 4 × ~3.5 MB ≈ 14 MB ≪ 50 MB extension cap. |
| Slot payload max | 1280×720 BGRA = 3 686 400 B | Discord screen-share spec ceiling at the resolution Puklic targets on iOS; larger frames truncated to a one-byte invalid-frame marker (header `flags` bit 0). Producer downsamples upstream — never expected at runtime. |
| Audio sub-ring slots | 16 | ReplayKit delivers ~10 ms PCM chunks; 16 slots = 160 ms headroom. At 48 kHz × stereo × Float32 = 1920 B per 10 ms → 16 slots = ~31 KB total — negligible. |
| Signal mechanism | Darwin `notify_post` / `notify_register_dispatch` | The only IPC signal that works between a sandboxed broadcast extension and the host app without Mach-port entitlements; built-in to libSystem; dispatched on a queue we control, no polling required. |
| Video signal name | `cz.damek.puklic.broadcast.video` | Reverse-DNS, namespaced by transport channel. |
| Audio signal name | `cz.damek.puklic.broadcast.audio` | Separate name so the consumer can wake selectively. |
| Liveness signal name | `cz.damek.puklic.broadcast.lifecycle` | Posted on extension `broadcastStarted` and `broadcastFinished`; lets host detect `extension_alive` flips without polling. |
| IPC transport | App Group container, single file `broadcast-ring.bin`, mmap'd RW on both sides | Pure App Group — Mach ports under the ReplayKit broadcast sandbox have stricter entitlements and add nothing here. Single file keeps lifecycle obvious (extension `broadcastStarted` truncates + initialises; host opens read/write to mmap the same pages). |
| Backpressure policy | Producer (extension) always wins — overwrites oldest slot when `write_seq - read_seq >= slot_count` | Discord screen-share cares about lowest latency, not gapless capture; FP-12 encoder will skip dropped frames cleanly. Documented in `SampleHandler.swift`. |
| Audio format in extension | PCM Float32 interleaved stereo @ 48 kHz | What `RPSampleBufferType.audioApp` delivers. The Kotlin bridge passes raw bytes through; FP-12 / the Opus encoder converts to S16. |
| Header atomicity | 64-bit aligned `_Atomic uint64_t` fields written with `os_unfair_lock` for the multi-field "publish slot" transaction | One lock serialises the brief "stamp slot header + bump write_seq" sequence; reader uses sequence numbers for lock-free polling between signals. Producer holds the lock for ≤ a memcpy + 16 B header write — well under any frame deadline. |

## 3. Ring buffer file layout (LOCKED)

File path: `${appGroupContainerURL}/broadcast-ring.bin`.

```
offset 0       : RingFileHeader (4 KB padded for page-alignment)
offset 4096    : VideoSlot[0] (3 686 400 + 64 B header = 3 686 464 B, rounded to 4 KB → 3 690 496 B)
offset 4096 + 3 690 496 : VideoSlot[1]
...
offset 4096 + 4 * 3 690 496 : AudioSlot[0..15] (each 2048 B header+payload, rounded to 4 KB → 4 096 B)
```

### 3.1 `RingFileHeader` (first 4 KB)

| Field | Size | Notes |
|---|---|---|
| `magic` | 4 B | ASCII `"PRKB"` (Puklic ReplayKit Broadcast) |
| `version` | 4 B | `0x0001` |
| `video_slot_count` | 4 B | `4` |
| `audio_slot_count` | 4 B | `16` |
| `video_slot_stride` | 4 B | `3690496` |
| `audio_slot_stride` | 4 B | `4096` |
| `video_data_base_offset` | 8 B | `4096` |
| `audio_data_base_offset` | 8 B | `4096 + video_slot_count * video_slot_stride` |
| `video_write_seq` | 8 B | atomic, monotonically increasing slot index; slot = `seq % video_slot_count` |
| `video_read_seq` | 8 B | reader-owned hint; producer reads to detect overrun-or-not for stats |
| `audio_write_seq` | 8 B | atomic |
| `audio_read_seq` | 8 B | reader-owned hint |
| `extension_alive` | 4 B | atomic int32; `1` between `broadcastStarted` and `broadcastFinished`, `0` otherwise |
| reserved | rest of 4 KB | zero |

### 3.2 `VideoSlot` (3 690 496 B)

| Offset | Size | Field |
|---|---|---|
| 0 | 8 B | `seq` (matches the write_seq at publish time; reader validates) |
| 8 | 4 B | `width` |
| 12 | 4 B | `height` |
| 16 | 4 B | `bytes_per_row` |
| 20 | 4 B | `payload_bytes` |
| 24 | 8 B | `pts_ns` (CMTime converted to nanoseconds since broadcast start) |
| 32 | 4 B | `flags` (bit 0 = invalid frame / truncated) |
| 36 | 28 B | reserved |
| 64 | up to 3 686 400 B | BGRA payload |

### 3.3 `AudioSlot` (4 096 B)

| Offset | Size | Field |
|---|---|---|
| 0 | 8 B | `seq` |
| 8 | 4 B | `frame_count` (sample frames in payload) |
| 12 | 4 B | `channel_count` (`2`) |
| 16 | 4 B | `sample_rate` (`48000`) |
| 20 | 4 B | `payload_bytes` |
| 24 | 8 B | `pts_ns` |
| 32 | 32 B | reserved |
| 64 | up to 4 032 B | Float32 interleaved PCM payload (typical ReplayKit chunk = 480 frames × 2 ch × 4 B = 3 840 B; fits) |

Larger audio chunks are split into multiple consecutive slots by `SampleHandler` (incrementing `seq` for each).

## 4. Producer protocol (`SampleHandler.swift`)

1. `broadcastStarted(withSetupInfo:)`:
   - Resolve App Group container URL via `FileManager.default.containerURL(forSecurityApplicationGroupIdentifier:)`.
   - Open `broadcast-ring.bin` `O_RDWR | O_CREAT`, `ftruncate` to total size, `mmap` `PROT_READ | PROT_WRITE | MAP_SHARED`.
   - Memset header, write magic + layout constants, atomically set `extension_alive = 1`.
   - `notify_post("cz.damek.puklic.broadcast.lifecycle")`.

2. `processSampleBuffer(_: sampleBuffer, with: type)`:
   - `.video`: lock `os_unfair_lock`; compute slot index = `video_write_seq % video_slot_count`; `CVPixelBufferLockBaseAddress`; memcpy plane 0 → slot payload; populate slot header + `seq`; bump `video_write_seq`; unlock; `notify_post("cz.damek.puklic.broadcast.video")`. Unlock pixel buffer.
   - `.audioApp`: extract `CMSampleBufferGetAudioBufferList(...)`; copy interleaved Float32 into the next audio slot(s); bump `audio_write_seq`; `notify_post("cz.damek.puklic.broadcast.audio")`.
   - `.audioMic`: ignored (Discord screen-share spec sends app audio only; mic audio rides on the regular voice channel).

3. `broadcastPaused()` / `broadcastResumed()`: no-op (frames simply stop arriving / resume). Documented.

4. `broadcastFinished()`: set `extension_alive = 0`, `notify_post("cz.damek.puklic.broadcast.lifecycle")`, `munmap`, `close`.

## 5. Consumer protocol (`BroadcastExtensionBridge.kt`)

1. `videoFrames()` / `audioFrames()` return cold `Flow`s. On first collect:
   - Resolve App Group container URL via `NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(...)`.
   - Open the ring file; `mmap` `PROT_READ | PROT_WRITE | MAP_SHARED` (write access only to update `*_read_seq` hints).
   - If `magic != "PRKB"` or `extension_alive == 0`, suspend until the lifecycle notify fires (no spurious empty Flow emissions).
   - `notify_register_dispatch` on a dedicated serial dispatch queue, callback drains all unread slots into a `Channel(Channel.BUFFERED)` whose receive side is what the `Flow` emits.

2. On `Flow` cancellation: `notify_cancel` the registered tokens; `munmap`; close fd; signal `Channel` close. Atomic — safe under coroutine cancellation.

3. Sequence-based reader: the dispatch callback reads `video_write_seq` (atomic), iterates from `video_read_seq + 1` to `write_seq`, copies each slot's payload into a fresh `ByteArray` and emits a `BgraFrame`. After the loop, atomically stores `video_read_seq = write_seq`.

4. Overrun detection: if `write_seq - read_seq > video_slot_count`, the bridge skips ahead to `write_seq - video_slot_count + 1` and emits a single sentinel `BgraFrame` with `dropped = true` so FP-12 can record the metric. Documented; not a silent failure.

## 6. Backpressure rationale (LOCKED)

Discord screen-share is latency-first: encoders downstream prefer "newest frame" over "every frame in order". Therefore the producer never blocks. The consumer always catches up to `write_seq`, dropping older slots. The bridge surfaces drops as a per-frame `dropped` boolean rather than swallowing them, so FP-12 can report telemetry up the stack (also feeds the existing `DaveDowngradeDetector`-style banner pattern in the UI if drops persist).

This is a complete, conceptually-correct protocol (HARD RULE #2): there is no "v1 limitation". Drop-on-overrun is the *intended* semantic, not a stopgap.

## 7. Xcode target wiring

`iosApp/project.yml` gains a second target. Salient bits:

- `type: app-extension.broadcast-services-upload`
- `dependencies: [{ sdk: ReplayKit.framework }]` — extension links ReplayKit explicitly.
- `entitlements:` on both `iosApp` and `PuklicBroadcastUpload` map to `.entitlements` plist files declaring `com.apple.security.application-groups = [group.cz.damek.puklic.app]`.
- The `iosApp` target gains an `Embed App Extensions` build phase via `dependencies: [{ target: PuklicBroadcastUpload, embed: true }]`.
- `CODE_SIGN_ENTITLEMENTS` build setting points to the file for each target. Re-using the existing `DEVELOPMENT_TEAM: GR74KSG8M9`.

`xcodegen generate` re-materialises `iosApp.xcodeproj`. The build sequence becomes:

1. `xcodebuild` builds `PuklicBroadcastUpload` (Swift only — no Kotlin framework dependency).
2. `xcodebuild` builds `iosApp` (Kotlin framework via the existing pre-build script + the new extension Swift).
3. `iosApp` embeds the extension `.appex` automatically.

## 8. Apple Developer portal follow-ups (USER ACTION)

Before App Store submission, the user (account holder for team `GR74KSG8M9`) must:

1. Register an App ID for the extension: `cz.damek.puklic.app.broadcast`.
2. Enable the **App Groups** capability on both App IDs (`cz.damek.puklic.app` and `cz.damek.puklic.app.broadcast`).
3. Create / select the App Group `group.cz.damek.puklic.app` and add both App IDs to it.
4. Regenerate the provisioning profiles for both bundle ids.

Local builds with `CODE_SIGNING_ALLOWED=NO` (the gate this slice asserts in CI) skip the above. Real device / TestFlight / App Store builds will fail signing without these steps. **NOT this slice's job.**

## 9. Self-critic (Step 3)

| Concern | Resolution |
|---|---|
| 50 MB ReplayKit cap | 14 MB video ring + ~64 KB audio ring + ReplayKit + Swift runtime ≈ ~25–30 MB worst case → ~20 MB headroom. Healthy. |
| Darwin notify reliability | `notify_register_dispatch` is the same primitive Apple uses for Darwin notification center; documented under `notify(3)`. Backed by libsystem; not deprecated. |
| Sequence wraparound | 64-bit; at 60 fps it wraps in 9 × 10^9 years. Not a concern. |
| Reader starvation during very fast bursts | Drop-on-overrun policy: reader always converges to "latest frame". Documented intentional behaviour. |
| Lock contention | `os_unfair_lock` is the cheapest contemporary lock on Darwin; producer holds it for one memcpy + 16 B of header writes (< 100 µs at 1280×720). No realistic contention with the consumer's lock-free read path. |
| Extension never started → bridge hangs forever | `videoFrames()` suspends on the lifecycle signal; cancellation on the parent scope unwinds cleanly. FP-12's screencast factory wraps this in a timeout if the UI expects an "is broadcasting" state machine. |
| TODO / temporary code | None. The ring buffer protocol is final; the extension's audio + video handlers do real work; the bridge does real mmap + notify dispatch. |
| UX surface change | None in this slice. `BroadcastPickerView` is declared but never inserted into the view hierarchy. FP-12 owns placement under HARD RULE #3. |

## 10. Files added

| Path | Purpose |
|---|---|
| `iosApp/project.yml` (edit) | Adds the extension target + entitlements references + dependency embed |
| `iosApp/iosApp/iosApp.entitlements` | Main app App Group entitlement |
| `iosApp/Broadcast/Info.plist` | Extension Info.plist with NSExtension dict |
| `iosApp/Broadcast/PuklicBroadcastUpload.entitlements` | Extension App Group entitlement |
| `iosApp/Broadcast/SampleHandler.swift` | `RPBroadcastSampleHandler` subclass — full impl |
| `iosApp/Broadcast/SharedRingBuffer.swift` | Producer-side mmap + atomic header + slot publish |
| `iosApp/iosApp/Sources/BroadcastPickerView.swift` | `UIViewRepresentable` over `RPSystemBroadcastPickerView` |
| `shared/screencast/src/iosMain/kotlin/dev/puklic/screencast/ios/BroadcastExtensionBridge.kt` | Kotlin/Native consumer: mmap + notify_register_dispatch + Flow |
| `shared/screencast/src/iosTest/kotlin/dev/puklic/screencast/ios/BroadcastExtensionBridgeContractTest.kt` | Compile-only contract test on the Kotlin surface |
| `shared/screencast/build.gradle.kts` (edit) | Add iosMain + iosTest source set wiring (Foundation is on the SDK; no extra deps) |

## 11. Verification commands (Step 6)

```
./gradlew :shared:screencast:compileKotlinIosArm64
./gradlew :shared:screencast:compileKotlinIosX64
./gradlew :shared:screencast:compileKotlinIosSimulatorArm64
./gradlew :ios:app:verifyIosNoGplDeps
(cd iosApp && xcodegen generate)
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO
```

All five must pass.
