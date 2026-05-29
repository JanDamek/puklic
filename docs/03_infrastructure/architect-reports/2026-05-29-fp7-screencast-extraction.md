# FP-7 — `:shared:screencast` module + JVM Linux actual (PipeWire move)

**Date**: 2026-05-29
**Issue**: JanDamek/puklic#47
**Slice of**: full-feature-parity refactor — see
`docs/03_infrastructure/architect-reports/2026-05-29-full-feature-parity.md`
§3.3 + §7.

Pre-approved per the blanket non-UX architectural authorisation for the
feature-parity refactor (refactor only, no user-visible change).

## 1. Goal

Create a new `:shared:screencast` Apache-2.0 KMP module and move the existing
Linux PipeWire / libavcodec screencast implementation into its `jvmMain`.
This is the foundation slice:

- FP-8 — macOS `ScreenCaptureKit` actual (jvmMain, JNA)
- FP-9 — Windows `IDXGIOutputDuplication` actual (jvmMain, JNA)
- FP-12 — iOS `ReplayKit` + `VideoToolbox` actual (iosMain, cinterop)

…all land in this module in later slices.

## 2. Reuse audit

- `:shared:voice-codec` already owns `EncodedFrame`, `RtpPacket`,
  `H264Encoder` (commonMain). The new module depends on it transitively
  via the public surface — no duplication.
- `:shared:voice-api` already owns `ScreenSource` (Apache-2.0,
  KMP-wide). The new module re-exports it via `api(projects.shared.voiceApi)`;
  there is no second move.
- The Linux portal + libavcodec encoder + Annex-B parser already live in
  `:shared:voice/jvmMain/screenshare/{linux,encoder,source}`. They are
  physically relocated (same `dev.puklic.voice.screenshare.*` packages —
  see §5 for the conscious decision not to rename).

## 3. Exact file moves (`git mv`, history-preserving)

### Into `:shared:screencast/src/commonMain/kotlin/dev/puklic/voice/screenshare/encoder/`

From `:shared:voice/src/commonMain/kotlin/dev/puklic/voice/screenshare/encoder/`:

- `VideoEncoder.kt`
- `VideoCodec.kt`

These were `internal` in `:shared:voice`. They become `public` here because
they are now cross-module surface: `DefaultScreenShareClient`
(`:shared:voice/jvmMain`) and `DefaultVoiceClient` (`:shared:voice/jvmMain`)
consume them. Visibility widening is the conceptually-correct change — these
types describe the screencast module's public encoder contract; there is
no `internal` interpretation that survives the split (HARD RULE #2).

### Into `:shared:screencast/src/jvmMain/kotlin/dev/puklic/voice/screenshare/`

From `:shared:voice/src/jvmMain/kotlin/dev/puklic/voice/screenshare/`:

- `encoder/LibavVideoEncoder.jvm.kt`
- `encoder/AnnexBStreamReader.kt`
- `linux/LinuxPortalScreenCast.kt`
- `linux/PipeWireAudioReader.kt`
- `linux/PortalStream.kt`
- `source/LinuxScreenSourceEnumerator.jvm.kt`

`LibavVideoEncoder` and `LinuxScreenSourceEnumerator` were `internal class`
in `:shared:voice/jvmMain`; they become `public class` here for the same
cross-module-surface reason as above.

### NOT moved (stay in `:shared:voice`)

- `screenshare/source/ScreenSourceEnumerator.kt` (commonMain `expect fun`) —
  the JVM `actual fun` dispatches Linux vs Mac and remains co-located with
  the dispatcher.
- `screenshare/source/MacScreenSourceEnumerator.jvm.kt` (Mac actual + the
  JVM dispatcher `actual fun screenSourceEnumerator`).
- `screenshare/source/LibavMonitorEnumerator.jvm.kt` (Mac avfoundation
  libavdevice helper).
- `screenshare/encoder/FfmpegVideoEncoder.jvm.kt` (Mac legacy subprocess
  encoder).
- `screenshare/DefaultScreenShareClient.kt` (orchestrator — see §6).

The Mac surface lands in `:shared:screencast/jvmMain` in slice **FP-8**;
moving it now without its replacement would be temporary state (HARD RULE
#2). Until FP-8, the Mac screencast path keeps living in `:shared:voice`,
and `:shared:voice` `api(projects.shared.screencast)` re-exports the
Linux-side moved symbols so existing imports keep resolving.

## 4. `:shared:screencast` commonMain interfaces

Per architect report §3.3 (full-feature-parity) and issue #47:

```kotlin
public interface ScreenCapture : AutoCloseable {
    public val frames: Flow<EncodedFrame>
    public val audio: Flow<ShortArray>?
}

public interface ScreenSourceEnumerator {
    public suspend fun list(): List<ScreenSource>
}

public typealias H264EncoderFactory = () -> H264Encoder

public interface ScreenCaptureFactory {
    public fun create(
        source: ScreenSource,
        shareAudio: Boolean,
        h264EncoderFactory: H264EncoderFactory,
    ): ScreenCapture
}
```

These are **plain `interface`** rather than `expect class`. The architect
report §3.3 sketched `expect class ScreenCapture(source, shareAudio)`, but
introducing `expect class` without iOS / macOS / Windows `actual`s in the
same slice would either (a) fail iOS compilation, or (b) require empty
stub actuals — both violate HARD RULE #2. Plain interfaces give every
later actual full freedom over its constructor and lifecycle (ReplayKit
needs an `RPBroadcastSampleHandler` extension wire-up; ScreenCaptureKit
needs an `SCContentFilter`; Desktop Duplication needs an `IDXGIOutput`).
FP-12 / FP-8 / FP-9 each provide one concrete implementing class plus a
`ScreenCaptureFactory` `actual object` or registry entry.

### Architectural note — source-driven vs push-frame

`LibavVideoEncoder.encode(): Flow<EncodedFrame>` is source-driven: the
demuxer thread pulls frames from PipeWire and the encoder thread pulls
NALs from libx264. `ScreenCapture.frames: Flow<EncodedFrame>` is the
same shape. On Linux the JVM-side adapter is a one-liner — wrap the
existing `LibavVideoEncoder` instance and expose its `encode()` flow as
`frames` (plus the existing `PipeWireAudioReader.read()` as `audio`).
The gap from FP-2 (where `H264Encoder.encode(YuvFrame): EncodedFrame` is
push-based for iOS VideoToolbox) is preserved as architecture: iOS and
macOS actuals (FP-12 / FP-8) supply their own platform-driven capture
loop that internally drives the push-based `H264Encoder`. The
`ScreenCapture.frames` flow on those platforms is fed by that loop.
No commonMain abstraction has to reconcile the two — the interface
is consumer-facing only.

## 5. Package rename — explicitly NOT done

All moved files keep the existing `dev.puklic.voice.screenshare.*`
package names. Reasons:

1. Every consumer in `:shared:voice` and `:shared:compose-ui` already
   imports under that FQN. A rename would force a sweep across
   `MainViewModel.kt`, `VoiceDock.jvm.kt`,
   `ScreenSharePickerDialog.kt`, `VoiceStatusBar.kt` plus every
   `:shared:voice/jvmTest` file — outside FP-7's scope.
2. `:shared:voice-codec` set the precedent at FP-1: the Opus codec
   types kept the `dev.puklic.voice.codec.*` FQN after being moved
   out of `:shared:voice`. Doing the same here is consistent.
3. The package name does not encode a licence or runtime claim. The
   Gradle module boundary does — and that is what guards iOS classpath
   purity via `verifyIosNoGplDeps`.

A future rename (e.g. `dev.puklic.screencast.*`) is a separate
cosmetic-only refactor and is not coupled to the feature parity work.

## 6. `DefaultScreenShareClient` — call site unchanged in this slice

The instruction permits keeping desktop call sites unchanged when a
refactor would touch too much. `DefaultScreenShareClient` is a 324-line
JVM-only orchestrator with **five** factory seams
(`encoderFactory`, `portalScreenCastFactory`, `audioReaderFactory`,
`opusEncoderFactory`, `soundshareSenderFactory`) that are heavily exercised
by `DefaultScreenShareClientTest`. Rewriting it onto `ScreenCaptureFactory`
in the same slice would either:

- break ~10 tests and force them to be rewritten, or
- introduce a parallel code path behind a flag — temporary state
  forbidden by HARD RULE #2.

The conceptually-correct sequence is: FP-8 + FP-9 + FP-12 deliver their
respective `ScreenCaptureFactory` actuals first, then a follow-up slice
rewrites `DefaultScreenShareClient` to consume the unified factory and
drops the per-platform branching. That slice is the logical owner of
the test churn.

In FP-7, `DefaultScreenShareClient` continues to construct
`LibavVideoEncoder` directly. The only change visible to it is that
`LibavVideoEncoder`, `LinuxPortalScreenCast`, `PipeWireAudioReader`,
`LinuxScreenSourceEnumerator`, `VideoEncoder`, `VideoCodec`, and
`chooseCodec` now resolve through the `api(projects.shared.screencast)`
re-export rather than from `:shared:voice` itself. Imports do not change
(packages preserved).

## 7. Build graph

`shared/screencast/build.gradle.kts`:

```
plugins { alias(libs.plugins.kotlin.multiplatform); id("org.jetbrains.kotlinx.kover") }

kotlin {
    jvm()
    iosArm64(); iosX64(); iosSimulatorArm64()
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.voiceApi)        // re-export ScreenSource
            api(projects.shared.voiceCodec)      // re-export EncodedFrame, H264Encoder, RtpPacket
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotest.assertions.core)
        }
        jvmMain.dependencies {
            // FFmpeg GPL bundle for libx264 / libvpx / pipewire demuxer /
            // swresample. JVM-only — never on the iOS classpath, never linked
            // into :ios:app (which does not depend on this module). The same
            // pattern :shared:voice-codec uses for the JVM Opus path.
            implementation(libs.javacpp)
            implementation(libs.ffmpeg.bindings)
            runtimeOnly("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.get()}:${detectFfmpegClassifier()}")
            // PipeWire portal stream + audio node id come back via D-Bus
            // session bus from xdg-desktop-portal — the same path the rest
            // of the Linux screencast already uses from :shared:voice.
            implementation(libs.dbus.java.core)
            implementation(libs.dbus.java.transport.jnr.unixsocket)
            implementation(libs.kermit)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
            runtimeOnly(libs.ffmpeg.platform.gpl)
        }
    }
}
```

`:shared:voice/build.gradle.kts`: `api(projects.shared.screencast)`
added to `commonMain.dependencies`. The matching `runtimeOnly` and
`dbus-java` declarations remain in `:shared:voice/jvmMain` because the
Mac screencast path + `DefaultScreenShareClient` still live there. Both
modules pulling the FFmpeg-GPL classifier into the desktop classpath is
fine — Gradle dedupes coordinates.

`settings.gradle.kts`: `include(":shared:screencast")` added between
`:shared:voice-codec` and `:shared:voice`.

## 8. Tests

- Existing `:shared:voice/jvmTest` files
  (`LibavVideoEncoderTest`, `LinuxPortalScreenCastTest`,
  `LinuxScreenSourceEnumeratorTest`, `DefaultScreenShareClientTest`)
  continue to live in `:shared:voice` and continue to import from the
  preserved package paths. They pass unchanged because the symbols are
  re-exported via `api`.
- New `:shared:screencast/commonTest` compile-only contract test
  (`ScreenCaptureContractTest.kt`) instantiates fake implementations of
  `ScreenCapture`, `ScreenSourceEnumerator`, and `ScreenCaptureFactory`
  to lock the surface in.

## 9. dep-policy.md update

Add row:

| `:shared:screencast` | desktop today (via `:shared:voice` `api`); FP-8/9 add macOS + Windows JVM actuals; FP-12 adds iOS iosMain actual | Apache-2.0 (commonMain surface) + **GPL-3.0** (jvmMain transitive: FFmpeg-GPL, libx264, libvpx, libpipewire demuxer) | Apache-2.0 KMP module containing the screen capture contract + Linux JVM actual. The GPL transitive lives only in jvmMain and is never on the iOS classpath. `verifyIosNoGplDeps` covers the iOS guarantee mechanically. |

## 10. Done criteria

- [x] `:shared:screencast` created with explicit jvm + iosArm64 + iosX64 + iosSimulatorArm64 targets (no Android — same as `:shared:voice-codec`).
- [x] Apache-2.0 commonMain surface; jvmMain pulls FFmpeg-GPL transitively.
- [x] All listed files moved via `git mv` (history preserved).
- [x] `:shared:voice` `api(projects.shared.screencast)` so JVM consumers' imports are unchanged.
- [x] commonTest contract test for `ScreenCapture` / `ScreenCaptureFactory` / `ScreenSourceEnumerator`.
- [x] `:shared:screencast:build`, `:shared:screencast:compileKotlinIosArm64/X64/SimulatorArm64`, `:shared:voice:build`, `:desktop:app:compileKotlin`, `:ios:app:verifyIosNoGplDeps` all green.
- [x] `dep-policy.md` updated.
- [x] No TODO, no temporary state, no commented-out code.
