# FP-14h-3 — Apple audio capture + playback (iOS cinterop + Mac App Store JNA)

Status: impl-role architect plan. Implements the Apple `actual` side of the
`AudioCapture` / `AudioPlayback` contracts already living in
`:shared:voice-codec/commonMain` since FP-14h-2e (see
`2026-05-29-fp14h-2e-implementation.md`).

Issue: #62 (FP-14h umbrella). Sub-slice FP-14h-3.

References:
- HARD RULE #2 (`<repo>/CLAUDE.md`) — NEVER TEMPORARY, ALWAYS CONCEPTUAL
- FP-14h-1-v2 `2026-05-29-fp14h-1-v2-voice-gateway-redesign.md` §5 + §13.3
- FP-14h-2e `2026-05-29-fp14h-2e-implementation.md` §1.1 deferred items
- FP-8 ObjC/JNA bridge pattern: `shared/screencast/src/jvmMain/.../macos/ObjcRuntime.kt`
- FP-14c codec wrappers: `desktop/platform-macos-appstore/src/main/.../codec/JnaVideoToolbox*.kt`

## §1 Goal

Today `:shared:voice-codec/jvmMain/JavaSoundCapture.kt` (and the playback sibling)
provide the only `AudioCapture` / `AudioPlayback` implementations. They use
`javax.sound.sampled.*`, which:

1. Does **not** ship inside the iOS framework — no JVM there.
2. On macOS App Store the JavaSound provider works for the desktop `.dmg` but
   for the Mac App Store sandbox build the App Sandbox + `device.audio-input`
   entitlement (already in `dist/apple/macappstore/Puklic.entitlements`) is
   served by AVAudioEngine, the documented Apple API for sandboxed apps.
   JavaSound only works coincidentally because Apple still ships CoreAudio HAL
   underneath, but this is exactly the kind of "happens-to-work shim" HARD
   RULE #2 forbids us from depending on once a first-class path exists.

This slice ships the first-class Apple-native paths:

- **iOS** (`:shared:voice-codec/iosMain`) — AVFoundation **cinterop**
  (`platform.AVFAudio.AVAudioEngine` + `AVAudioSession`) implementing
  `AudioCapture` and `AudioPlayback` directly in Kotlin/Native. Becomes the
  default once the iOS DI graph (`IosDependencyGraph`) wires voice (out of
  scope for this slice — issue #62 follow-up). Today it just compiles + tests.
- **Mac App Store** (`:desktop:platform-macos-appstore`) — JNA bridge to
  `AVAudioEngine` mirroring the FP-8 ObjC runtime pattern used by
  `ScreenCaptureKitBridge`. Replaces JavaSound at the `MacAppStoreMain`
  DependencyGraph seam.

The plain Linux desktop `.deb`/`.AppImage` build is **untouched** — it keeps
JavaSound (`audioCapture()` / `audioPlayback()` from FP-14h-2e). The two
factories continue to exist; the Mac App Store DI swaps them out.

## §2 Surface decision (conceptual)

The task brief proposed a new `AppleAudioCapture(sampleRate, channels)` type
shipped as `expect class` in commonMain. **Rejected** per HARD RULE #2:
introducing a parallel surface that duplicates `AudioCapture` would either
require a temporary adapter (forbidden) or invalidate FP-14h-2e's just-landed
contract.

Decision: the existing `AudioCapture` / `AudioPlayback` interfaces stay as
the SoT. They already encode the Discord-voice frame contract (48 kHz mono
S16 LE, 20 ms = 960 samples, blocking I/O). New impls implement these
interfaces. Sample-rate / channels are not parameters: they're hard
contracts (`AudioConstants.SAMPLE_RATE_HZ` + `CHANNELS_MONO`) that match
Opus' Discord profile. Adding parametrisation would invite "what if 16 kHz
later" branches we don't ship.

## §3 Files

### 3.1 `:shared:voice-codec/iosMain`

```
src/iosMain/kotlin/dev/puklic/voice/audio/
    IosAVAudioEngineCapture.kt    # AVAudioEngine input-node tap → Channel<ShortArray>
    IosAVAudioEnginePlayback.kt   # AVAudioEngine player-node + scheduleBuffer
    IosAudioSession.kt            # AVAudioSession singleton config (category + activate)
    IosAudioFactories.kt          # iosAudioCapture() / iosAudioPlayback() public fns
```

`IosAudioSession` exists because both capture and playback need
`AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .voiceChat)`
exactly once. Activation is reference-counted: `acquire()` from each impl,
`release()` on `close()`. Last release deactivates the session. This avoids
fighting iOS's "category goes idle when nothing claims it" semantics.

### 3.2 `:desktop:platform-macos-appstore`

```
src/main/kotlin/dev/puklic/desktop/macappstore/bridge/
    AVFAudio.kt                   # AVAudioEngine / AVAudioPlayerNode / AVAudioFormat selectors,
                                  # AVAudioPCMBuffer + audioBufferList access via JNA + ObjcRuntime
    AVAudioObjcRuntime.kt         # Mac App Store local copy of the FP-8 ObjcRuntime + Foundation,
                                  # scoped to this module to avoid pulling :shared:screencast (GPL deps).
```

```
src/main/kotlin/dev/puklic/desktop/macappstore/audio/
    JnaAVAudioEngineCapture.kt    # AudioCapture impl via JNA AVAudioEngine input node tap
    JnaAVAudioEnginePlayback.kt   # AudioPlayback impl via JNA AVAudioEngine + player node
    JnaAudioFactories.kt          # public jnaAudioCapture() / jnaAudioPlayback() factories
```

`AVAudioObjcRuntime.kt` is duplicated from `:shared:screencast` rather than
extracted to a shared module because `:shared:screencast` jvm pulls FFmpeg-GPL
(per `verifyMacAppStoreNoGplDeps`). The duplication is ~200 lines of stable
runtime glue; extracting a `:shared:apple-objc` module is a larger refactor
out of FP-14h-3 scope. Tracked as a future ticket: "extract Mac App Store
ObjC runtime to its own Apache-2.0 module".

> HARD RULE #2 check: this duplication is **not** temporary — it's a final
> design decision until a separate extraction ticket lands. No `// TODO
> extract`. The shared symbols are identical drop-ins.

### 3.3 Wiring deltas — none in this slice

**Inspection 2026-05-31** of
`desktop/app/src/macAppStore/.../MacAppStoreMain.kt` line 388-390:
the Mac App Store ship currently wires `VoiceClient = NoOpVoiceClient()`,
matching the iOS App Store posture (`IosDependencyGraph.kt:301`). A real
voice client composed from FP-14c JNA codec/transport primitives is a
separate follow-up slice (already noted in the MacAppStoreMain header
comment lines 105-113). FP-14h-3 ships the audio bridge primitives that
the future Apple-native VoiceClient slice will compose; it does NOT
touch `DefaultVoiceClient` (which today only runs on the GPL Linux
build via JavaSound).

iOS likewise stays on `NoOpVoiceClient` until iOS voice gets wired
end-to-end (separate slice). The `iosAudioCapture()` / `iosAudioPlayback()`
factories ship and compile under `:shared:voice-codec/iosMain` so the
future wiring slice has them ready.

This isolation is intentional per HARD RULE #1 minimum-complexity:
re-shaping `DefaultVoiceClient` to take overridable audio factories
would conflate the Apple-audio deliverable with a `:shared:voice`
refactor that has no current Mac App Store caller.

## §4 iOS impl details

### 4.1 AVAudioSession (iOS-only)

```kotlin
private val session = AVAudioSession.sharedInstance()
session.setCategory(AVAudioSessionCategoryPlayAndRecord,
                    AVAudioSessionModeVoiceChat,
                    AVAudioSessionCategoryOptionDefaultToSpeaker
                    | AVAudioSessionCategoryOptionAllowBluetooth, error = null)
session.setActive(true, error = null)
```

Deactivated on the last `close()`.

### 4.2 IosAVAudioEngineCapture

- Construct `AVAudioEngine`. Its `inputNode` is the system mic.
- `inputNode.installTapOnBus(0, bufferSize = 960u, format = inputNode.inputFormatForBus(0u))`
  — the format will be hardware-native (often 48 kHz Float32 stereo,
  sometimes 44.1 kHz Float32 mono on simulator). We rate/format-convert
  ourselves to S16 mono 48 kHz inside the tap closure.
- The tap block receives `AVAudioPCMBuffer`. Read its
  `audioBufferList.pointee.mBuffers[0].mData` Float32 channel(s), downmix to
  mono (avg L+R), convert each sample to Int16 (`(f * 32767f).toInt()
  .coerceIn(-32768, 32767).toShort()`), and append to a 960-sample
  staging deque. When 960 samples accumulate, push to `Channel<ShortArray>`
  and reset.
- For non-48 kHz sources (simulator on Intel hosts can deliver 44100 Hz), we
  use `AVAudioConverter` (an AVFoundation class designed exactly for this).
  Configure converter with output `AVAudioFormat(commonFormat = .pcmFormatInt16,
  sampleRate = 48000, channels = 1, interleaved = true)` and call
  `convertToBuffer:fromBuffer:error:`.
- `read()` (the blocking `AudioCapture.read()` API) translates to a blocking
  `runBlocking { channel.receive() }` — wait, we can't `runBlocking` in
  Kotlin/Native on the main thread. Instead, `IosAVAudioEngineCapture`
  exposes the existing `read(): ShortArray` API by using
  `kotlinx.coroutines.runBlocking` from the `kotlinx-coroutines-core`
  Kotlin/Native runtime, which **is** available on Darwin native (since
  coroutines 1.7). The voice capture pipeline calls `read()` from a
  dedicated capture worker thread, never from the main UI thread, so
  blocking semantics are correct. Verified: `:shared:voice-codec`
  commonMain already depends on `kotlinx-coroutines-core` (api). Native
  has `runBlocking` since 1.7.

### 4.3 IosAVAudioEnginePlayback

- `AVAudioEngine` + `AVAudioPlayerNode` attached.
- Output format: `AVAudioFormat(pcmFormatInt16, 48000, 1, interleaved=true)`.
- `write(pcm)` creates an `AVAudioPCMBuffer` of `frameCapacity = 960`, copies
  the ShortArray into `audioBufferList.pointee.mBuffers[0].mData` Int16
  channel, sets `frameLength = 960`, and calls `player.scheduleBuffer(buf)`.
- Buffer scheduling is async — `write()` does **not** wait for playback. The
  AVAudioEngine internal mixer queue drains them at line rate (48 kHz).
- Backpressure: the existing `AudioPlayback.write` contract says "blocks if
  the platform buffer is full". On AVAudioEngine there is no explicit upper
  bound on scheduled-buffer queue. We cap at 8 outstanding buffers (160 ms)
  by reading `player.outputPresentationLatency` + bookkeeping; if 8 are
  outstanding, `write()` semaphore-waits for the completion callback of the
  oldest before scheduling. This mirrors the JavaSound INTERNAL_BUFFER_MS=80
  policy in `JavaSoundPlayback`.

## §5 Mac App Store JNA impl details

Mac AVAudioEngine has **no** AVAudioSession — that class is iOS-only. macOS
goes straight to the engine. This is the key §13.3 self-critic point.

### 5.1 ObjC runtime in this module

Mirror `shared/screencast/src/jvmMain/.../macos/ObjcRuntime.kt` 1:1 (rename
package to `dev.puklic.desktop.macappstore.bridge`). Includes:
- `objc_getClass`, `sel_registerName`
- `objc_msgSend_*` arity overloads (the same 16 we use)
- `objc_allocateClassPair` / `class_addMethod` / `objc_registerClassPair`
- `_NSConcreteGlobalBlock` resolution + `BlockLiteral` / `BlockDescriptor`
- `Foundation.kt` NSString/NSError helpers

### 5.2 AVFAudio.kt JNA selectors

The bridge resolves:
- `AVAudioEngine`, `AVAudioPlayerNode`, `AVAudioPCMBuffer`, `AVAudioFormat`,
  `AVAudioConverter`
- Class methods: `[AVAudioFormat alloc] initStandardFormatWithSampleRate:channels:`
  and `initWithCommonFormat:sampleRate:channels:interleaved:`
- Instance methods: `attachNode:`, `connect:to:format:`, `inputNode`,
  `mainMixerNode`, `outputNode`, `prepare`, `startAndReturnError:`, `stop`,
  `installTapOnBus:bufferSize:format:block:`, `removeTapOnBus:`,
  `scheduleBuffer:completionHandler:`, `play`, `pause`, `audioBufferList`

The capture tap requires a synchronous block (`AVAudioNodeTapBlock` =
`void(^)(AVAudioPCMBuffer*, AVAudioTime*)`). The bridge constructs a
`_NSConcreteGlobalBlock` instance with `invoke` pointing at a JNA Callback,
identical to FP-8's `getShareableContentWithCompletionHandler:` block.

### 5.3 JnaAVAudioEngineCapture flow

Same as iOS: read AudioBufferList Float32, downmix to mono, convert to S16,
accumulate, push to `BlockingQueue<ShortArray>` (capacity 8 = 160 ms). The
`AudioCapture.read()` impl pulls from the queue with `take()`.

For non-48 kHz host inputs (Intel macs at 44.1 kHz), use `AVAudioConverter`
exactly as iOS does — same selectors.

### 5.4 JnaAVAudioEnginePlayback flow

`AVAudioPlayerNode.scheduleBuffer(_:completionHandler:)` lets us drive
back-pressure: the completion handler decrements an `outstanding`
`AtomicInteger`. `write()` blocks on a `Semaphore` if `outstanding >= 8`.
This is the macOS equivalent of the JavaSound `SourceDataLine.write` block.

## §6 Self-critic (Step 3)

1. **AVAudioSession differs between iOS and macOS.** macOS has no
   `AVAudioSession` class at all; using `platform.AVFAudio` cinterop on
   macOS-target would not compile. Mitigation: iOS code lives in
   `:shared:voice-codec/iosMain` (only iOS Kotlin/Native targets see it).
   Mac App Store JNA code lives in `:desktop:platform-macos-appstore`
   (JVM jvmMain only). They never share a compilation. ✓ Documented in §5.

2. **`runBlocking` on Kotlin/Native main thread.** AudioCapture.read is
   blocking. iOS callers must invoke from a non-main thread. The existing
   JVM JavaSoundCapture has the same constraint (it blocks the calling
   thread on `line.read(...)`); voice's `CapturePipeline` already spawns
   a dedicated capture coroutine on `Dispatchers.IO`. On iOS the
   equivalent is `Dispatchers.Default` — verified used elsewhere in this
   repo. So callers are correct. iOS impl uses `runBlocking` only on
   non-main threads. ✓

3. **AVAudioEngine sample-rate mismatch on simulator.** The Intel
   simulator delivers 44.1 kHz input. AVAudioConverter handles
   resampling. ✓ Both impls use it.

4. **Float32 → Int16 saturation.** Coerce range `[-32768, 32767]`.
   Float32 mic input is typically in `[-1, 1]` but can transiently
   exceed it. Clamping prevents Int16 wrap. ✓

5. **Block GC on JNA side.** Per FP-8 DelegateClass pattern, hold strong
   references to Callback instances in fields of the impl class so JNA's
   trampoline pointer stays alive while the engine holds the block. ✓

6. **Linux `verifyNoGplDeps`.** The new JNA module continues to depend
   only on `:shared:voice-codec`, `:shared:voice-api`, `jna`,
   `jna-platform`, coroutines, kermit — same as today. No new GPL.
   `verifyMacAppStoreNoGplDeps` continues green. ✓

7. **`verifyIosNoGplDeps`.** New iosMain code uses only stdlib +
   `platform.AVFAudio` cinterop + `platform.Foundation`. All Apache-2.0
   /Apple-system. No GPL. ✓

8. **`AVAudioEngine.startAndReturnError:` failure.** On a denied mic
   permission, AVAudioEngine raises an NSError. `start()` throws an
   `IllegalStateException` with the localized error description. This
   matches the JavaSound contract ("Throws if not started" semantics)
   surfaced upstream as a voice-connect failure. No fallback to a stub
   — HARD RULE #2.

9. **No camera/screen capture entitlement collision.** Mac App Store
   entitlements already declare `device.audio-input`. iOS App Store
   bundle declares `NSMicrophoneUsageDescription` in
   `ios/app/iosApp.xcodeproj/.../Info.plist` — verify and add if
   missing. (Inspection step in §7.)

10. **Channel race in IosAVAudioEngineCapture.** The capture tap runs
    on AVAudioEngine's internal queue. We push to a
    `Channel<ShortArray>(capacity = 8)` which is thread-safe in coroutines.
    `Channel.trySend` is non-blocking; if the consumer is slow, oldest
    frames are dropped (better than blocking the audio render thread,
    which would xrun the system). Logged via kermit at WARN. ✓

11. **`AVAudioConverter` lifecycle.** Hold one converter instance per
    impl, reset on stop. Re-create on the next start if the input format
    changed.

## §7 Verification matrix

```bash
./gradlew :shared:voice:build :shared:voice-codec:build
./gradlew :shared:voice-codec:compileKotlinIosArm64 \
          :shared:voice-codec:compileKotlinIosX64 \
          :shared:voice-codec:compileKotlinIosSimulatorArm64
./gradlew :ios:app:verifyIosNoGplDeps :desktop:app:verifyMacAppStoreNoGplDeps
./gradlew :desktop:platform-macos-appstore:build
./gradlew :desktop:app:macAppStoreTest --no-configuration-cache
./gradlew :ios:app:linkReleaseFrameworkIosArm64
```

## §8 Out of scope

- Wiring iOS voice into `IosDependencyGraph.buildIosSession` (still
  `NoOpVoiceClient` per `IosDependencyGraph.kt:301`) — separate slice once
  iOS UDP transport + DefaultVoiceClient are KMP-clean.
- Sample-accurate timing measurement / latency profiling — pre-MVP.
- Bluetooth source switching UI — pre-MVP.
