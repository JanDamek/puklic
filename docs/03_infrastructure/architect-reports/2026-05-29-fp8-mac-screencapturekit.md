# FP-8 — macOS ScreenCaptureKit JVM screen capture

**Date**: 2026-05-29
**Slice**: FP-8 (issue #48)
**Pipeline step**: 2 (design) + self-critic (3) — pre-approved per blanket macro.
**Module**: `:shared:screencast` `jvmMain` (macOS-only code paths).

## 1. Goal

Add a native ScreenCaptureKit capture path to `:shared:screencast` so the macOS
desktop `.dmg` build can screen-share without going through the AVFoundation
libavdevice demuxer (still GPL-tied, prompt-less, no audio share). Output of
this slice:

- `MacScreenSourceEnumerator` listing live displays + windows.
- `MacScreenCapture` driving a `SCStream` with H.264 video + optional system audio.
- `MacScreenCaptureFactory` selectable from `DefaultScreenShareClient` in a
  later slice (out of scope here).
- A JVM `H264Encoder` push-frame implementation built on libx264 from the
  existing JavaCPP ffmpeg-platform-gpl bundle, so platform raw-frame producers
  (Mac BGRA, Windows BGRA later) can reuse it.

## 2. Reuse audit (step 1)

| Need                                  | Reused                                                                   |
|---------------------------------------|--------------------------------------------------------------------------|
| H.264 encoder for raw frames          | `org.bytedeco:ffmpeg` libx264 (already in `:shared:screencast` jvm deps) |
| BGRA → YUV420P conversion             | libswscale from the same bundle                                          |
| Annex-B NAL split                     | `dev.puklic.voice.transport.AnnexBSplitter` (commonMain `:shared:voice-codec`) |
| H264Encoder interface                 | `dev.puklic.voice.codec.video.H264Encoder` (`:shared:voice-codec` commonMain) |
| `ScreenCapture` / `ScreenCaptureFactory` / `ScreenSourceEnumerator` | FP-7 commonMain |
| JNA dependency                        | `net.java.dev.jna:jna` 5.16.0 already declared in `libs.versions.toml`   |
| ScreenSource                          | `dev.puklic.voice.screenshare.ScreenSource`                              |

No new Maven dependencies. The full ScreenCaptureKit binding stays Apple-only
at runtime — the code compiles on any host; resolution of Objective-C symbols
happens lazily on first call from `MacScreenCapture` / `MacScreenSourceEnumerator`.

## 3. Architecture

### 3.1 Files

```
shared/screencast/src/jvmMain/kotlin/dev/puklic/screencast/macos/
    ObjcRuntime.kt                # JNA bindings to libobjc + Foundation runtime helpers
    CoreMedia.kt                  # CoreMedia / CoreVideo function bindings + helpers
    ScreenCaptureKitBridge.kt     # selector ids, Obj-C class lookups for SC*, NS*
    DelegateClass.kt              # one-time-registered Obj-C class implementing the SCStreamOutput protocol
    BgraToYuv420.kt               # libswscale wrapper, BGRA32 → YUV420P
    LibavPushH264Encoder.kt       # H264Encoder actual using libx264 (push-frame, no demuxer)
    AudioBufferConverter.kt       # CMSampleBuffer Float32 stereo 48 kHz → ShortArray PCM16
    MacScreenSourceEnumerator.kt
    MacScreenCapture.kt
    MacScreenCaptureFactory.kt
```

Test files:

```
shared/screencast/src/jvmTest/kotlin/dev/puklic/screencast/macos/
    MacScreenCaptureFactoryConstructTest.kt   # compile-time + reflective construct on mac host
    BgraToYuv420Test.kt                       # known-pixel BGRA → YUV420P bit-exact check
    LibavPushH264EncoderTest.kt               # encodes one solid YUV420 frame, verifies NAL output
```

### 3.2 JNA Obj-C runtime layer

`ObjcRuntime` exposes the minimum surface required:

- `objc_getClass(String): Pointer`
- `sel_registerName(String): Pointer`
- `objc_msgSend(...)` — declared once per argument-shape we use; JNA cannot
  vararg into a C `(...)` so we declare overloads with concrete signatures.
- `objc_allocateClassPair`, `class_addMethod`, `objc_registerClassPair` for the
  delegate class.
- `_NSConcreteGlobalBlock` symbol plus a `BlockLiteral` JNA `Structure` for
  constructing a synchronous block to pass to
  `getShareableContentWithCompletionHandler:`.

`Foundation` helpers convert between Java `String` and `NSString` via
`stringWithUTF8String:` + `UTF8String`.

### 3.3 SCShareableContent enumeration

`getShareableContentWithCompletionHandler:` is the only public API. It takes
an Obj-C block. We build a global-scope block whose `invoke` field points at
a JNA Callback `(Pointer block, Pointer content, Pointer error)`. The
callback signals a `CountDownLatch` plus stores the `NSArray*` of displays /
windows. The enumerator awaits the latch with a 10 s timeout, then walks the
arrays via `count` + `objectAtIndex:` and reads `displayID`, `width`,
`height` for displays and `windowID`, `title`, `frame` for windows.

The block ABI we forge:

```
struct BlockLiteral {
    void*   isa;            // _NSConcreteGlobalBlock
    int     flags;          // BLOCK_HAS_SIGNATURE | BLOCK_IS_GLOBAL
    int     reserved;
    void*   invoke;         // function pointer (JNA callback trampoline)
    void*   descriptor;     // points at a static BlockDescriptor
};
struct BlockDescriptor { unsigned long reserved; unsigned long size; const char* signature; };
```

This is documented Apple ABI — not reverse engineering, ABI is part of the
Blocks runtime in `libclosure`. The struct + signature `"v@?@@"`
(void, block, NSArray, NSError) is stable.

### 3.4 SCStream + output delegate

Delegate class:

- Register once at module init: `objc_allocateClassPair(NSObject, "PuklicSCDelegate")`.
- Add `stream:didOutputSampleBuffer:ofType:` via `class_addMethod` with a JNA
  Callback IMP. JNA accepts a `Callback` where a C function pointer is
  expected; we cast through `Pointer` retrieved from
  `CallbackReference.getFunctionPointer(callback)`.
- `objc_registerClassPair`. Instance allocated per `MacScreenCapture` so we
  can route the callback back through a `WeakHashMap<Pointer, MacScreenCapture>`
  keyed by the delegate instance pointer.

Pipeline per delivered video sample buffer:

1. `CMSampleBufferGetImageBuffer(buffer)` → `CVImageBuffer` (BGRA).
2. `CVPixelBufferLockBaseAddress(buf, 0)`, read `baseAddress`, `width`,
   `height`, `bytesPerRow`.
3. Copy into Kotlin `ByteArray` (we own a reusable scratch buffer sized
   `width * height * 4`).
4. `CVPixelBufferUnlockBaseAddress`.
5. Send on a `Channel<ByteArray>(Channel.CONFLATED)` to the encoder coroutine.
6. Encoder coroutine: BGRA → YUV420P via `BgraToYuv420`, hand to
   `LibavPushH264Encoder.encode(yuv)`, on non-null result emit on the
   `frames` `Flow`.

Audio:

- macOS 13+ runtime check via `NSProcessInfo.operatingSystemVersion`. If
  shareAudio=true on macOS 12.x we log a Kermit warning and proceed
  video-only (no fallback library — BlackHole is rejected, audio simply not
  available; the screen still shares). UI banner reflects this through the
  existing `DaveDowngradeDetector` style flag (`audio: Flow<ShortArray>?`
  returns `null` when audio unsupported / disabled).
- When supported we set `SCStreamConfiguration.capturesAudio = true,
  excludesCurrentProcessAudio = true, sampleRate = 48000, channelCount = 2`.
- The delegate also receives `SCStreamOutputType.audio` buffers — convert
  `CMSampleBuffer` Float32 interleaved to PCM16 via `AudioBufferConverter`,
  emit `ShortArray` on the audio channel.

### 3.5 Threading

- ScreenCaptureKit invokes the delegate on a private dispatch queue. JNA
  callbacks run on whatever native thread invokes them; we attach to the JVM
  if needed (JNA does this automatically).
- The delegate callback is non-blocking — it copies bytes into pre-allocated
  scratch arrays and offers to a `Channel`. If the channel is conflated and
  the consumer is slow, dropping the oldest frame is the documented behaviour
  (screen-share tolerates this; voice/audio sub-stream uses an unlimited
  channel because dropping audio causes pops).
- Encoder lives inside the `frames` cold flow via `channelFlow`. Cancellation
  closes the channel which exits the encoder loop and calls
  `SCStream.stopCaptureWithCompletionHandler:` plus
  `LibavPushH264Encoder.close()`.

### 3.6 `LibavPushH264Encoder`

A standalone push-frame H.264 encoder using libavcodec + libx264 directly
(no libavdevice, no demuxer). It is *not* a refactor of the existing
`LibavVideoEncoder` because that class is source-driven (libavdevice owns
the input format). Splitting `LibavVideoEncoder` would force a refactor of
the Linux path that this slice has no business touching. Instead this is a
focused 150-line class implementing the existing `H264Encoder` interface
from `:shared:voice-codec` commonMain. Both encoders share libx264 settings
constants — extracted into a small `LibavX264Settings` object referenced
from both, avoiding duplication of GOP / bitrate magic numbers.

### 3.7 `MacScreenCaptureFactory`

```kotlin
public object MacScreenCaptureFactory : ScreenCaptureFactory {
    override fun create(
        source: ScreenSource,
        shareAudio: Boolean,
        h264EncoderFactory: H264EncoderFactory,
    ): ScreenCapture = MacScreenCapture(
        source = source,
        shareAudio = shareAudio,
        encoderFactory = h264EncoderFactory,
    )
}
```

Wiring into `DefaultScreenShareClient` is deferred to a later slice (per the
issue acceptance list — "selectable via OS detection ... follow-up slice").

## 4. Self-critic (step 3)

- **TCC permission prompt** — first call to
  `SCShareableContent.getShareableContent...` triggers the system Screen
  Recording prompt. Documented in operator-facing docs under
  `docs/06_ops/`. No code change beyond honest error propagation when the
  user denies — the completion handler delivers an `NSError` which we surface
  as `IllegalStateException("ScreenCaptureKit: ${msg}")` on the cold flow,
  bubbling to the caller. No silent fallback.
- **macOS version gate** — ScreenCaptureKit itself requires 12.3+ (everywhere
  used in this code). Audio capture requires 13+. We hard-fail
  `MacScreenSourceEnumerator.list()` on < 12.3 with `IllegalStateException`
  rather than returning empty (which would be wrong — the caller would think
  the host has no displays). Audio on 12.3-12.x degrades to `audio = null`
  with a Kermit warning at construction time.
- **GPL transitive** — libx264 stays GPL; this slice only deepens the macOS
  jvm path's use of it. `.dmg` distribution is already GPL via the existing
  Linux path. `:ios:app:verifyIosNoGplDeps` is unaffected — it scans the iOS
  framework classpath which never sees `:shared:screencast` jvm artifacts.
- **JNA Block ABI assumption** — the `_NSConcreteGlobalBlock` symbol and the
  block-literal struct shape are stable across macOS versions back to 10.6
  when Blocks shipped. Risk: low. Mitigation: integration test stays out of
  scope (CI Mac runner would be required); manual smoke test plan documented
  in the architect report follow-up.
- **Memory ownership** — `CVPixelBuffer` is owned by the
  `CMSampleBuffer` which is owned by ScreenCaptureKit's dispatch queue. We
  copy bytes inside the callback before returning; no `CFRetain` needed. The
  `NSArray*` of displays from the completion block must be retained for the
  duration of the call — done via `[arr retain]` before the latch unblocks,
  released after enumeration.
- **Encoder thread-safety** — libx264 contexts are not thread-safe; the
  encoder coroutine is the sole consumer of `LibavPushH264Encoder.encode`,
  enforced by channelFlow's single-collector contract.
- **Cleanup ordering** — `MacScreenCapture.close()` must stop the SCStream
  before tearing down the encoder, otherwise late delivery from the delegate
  queue could call into a freed encoder. We synchronize via an
  `AtomicBoolean closed` checked at the top of every delegate callback path.

## 5. Out-of-scope

- VideoToolbox-from-JNA encoder (the issue explicitly says: keep libx264).
- Integration with `DefaultScreenShareClient`. The factory is exported but
  not yet selected.
- macOS 11 / 10.x support — ScreenCaptureKit doesn't exist there.
- Windows (FP-9) and iOS (FP-12) — separate slices.

## 6. Acceptance mapping

| Issue checkbox                                                      | This report § |
|---------------------------------------------------------------------|---------------|
| `:shared:screencast:build` green                                    | implementation builds clean Kotlin on any host (JNA dynamic) |
| JVM-only Mac ScreenCaptureKit smoke test optional                   | constructor test added; runtime test gated on `os.name = mac` |
| `:desktop:app:assemble` green on Mac                                | no surface change visible to `:desktop:app` |
| `:ios:app:verifyIosNoGplDeps` unchanged                             | no iOS-side touch — iosMain untouched |
| `MacScreenCaptureFactory` exists                                    | §3.7 |
