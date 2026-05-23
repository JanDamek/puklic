# Self-Contained Linux-First Bundle (2026-05-23)

## Summary

Puklic must work as a fully self-contained package: no system `libopus`, no system `ffmpeg`, no BlackHole user-install on macOS. Linux is the primary platform.

Refactor to **JavaCPP bundled natives** (`org.bytedeco:ffmpeg-platform-gpl:7.1-1.5.11`, verified on Maven Central — Opus ships *inside* this artifact; no separate `opus-platform` exists) + **dbus-java-core 5.1.1** (pure-Java D-Bus) for Linux Wayland screencast via xdg-desktop-portal + PipeWire libav input. Per-OS installers via `jpackage` + classifier-pinned `ffmpeg:<os>-<arch>-gpl` deps keep each installer ~80 MB.

## 1. Dependency replacement (verified Maven Central 2026-05-23)

| Current | Replace with | Coordinates |
|---|---|---|
| JNA + system libopus (`LibOpus.kt`, `OpusCodec.jvm.kt`) | libavcodec Opus via JavaCPP FFmpeg GPL | `org.bytedeco:ffmpeg-platform-gpl:7.1-1.5.11` |
| ffmpeg CLI subprocess (`FfmpegVideoEncoder.jvm.kt`) | In-process libavcodec / libavformat / libavdevice | same artifact |
| BlackHole 2ch hard dep | Linux: portal includes audio; macOS: optional fallback hint | n/a |
| (none) Wayland portal | `com.github.hypfvieh:dbus-java-core:5.1.1` (pure Java) + `dbus-java-transport-jnr-unixsocket:5.1.1` | as above |

Verification:
- `org.bytedeco:ffmpeg-platform` → 7.1-1.5.11 ✓
- `org.bytedeco:ffmpeg-platform-gpl` → 7.1-1.5.11 (with libx264/libx265/libopus) ✓
- `org.bytedeco:ffmpeg` per-classifier: `linux-x86_64-gpl`, `linux-arm64-gpl`, `macosx-x86_64-gpl`, `macosx-arm64-gpl`, `windows-x86_64-gpl` ✓
- `org.bytedeco:opus-platform` → does NOT exist (Opus only inside ffmpeg GPL build)
- `com.github.hypfvieh:dbus-java-core` → 5.1.1 ✓

**JavaCPP loading model**: jars contain natives for all platforms; extracted to per-user cache on first use (`~/.javacpp/cache/` on Linux). 2-5s first-launch extract; cached thereafter.

## 2. Linux Wayland screencast via portal + PipeWire

dbus-java-core 5.1.1 talks D-Bus wire protocol directly over Unix socket at `$XDG_RUNTIME_DIR/bus`. No native dbus library needed.

D-Bus sequence on `org.freedesktop.portal.Desktop` (`/org/freedesktop/portal/desktop`, interface `org.freedesktop.portal.ScreenCast`):

1. `CreateSession(options) → o request_handle` → listen on Response signal → `session_handle`
2. `SelectSources(session_handle, {types: u(monitor=1|window=2), multiple: b, cursor_mode: u(embedded=2)}) → o request_handle` → Response code=0
3. `Start(session_handle, parent_window, opts) → o request_handle` → Response carries `streams: a(ua{sv})` = `(pipewire_node_id, props)`
4. `OpenPipeWireRemote(session_handle, opts) → h fd` — fd transferred over D-Bus via SCM_RIGHTS (`dbus-java-transport-jnr-unixsocket` supports this)
5. Hand fd + node id to libavdevice `pipewire` input format

Generate `ScreenCast` D-Bus stubs via `dbus-java-utils.CreateInterface` ahead of time, commit to `shared/voice`.

## 3. In-process libavcodec H.264 + Opus

Replace subprocess with in-process:

```kotlin
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avutil.*

val codec = avcodec_find_encoder_by_name("libx264")
val ctx = avcodec_alloc_context3(codec).apply {
    width(1920); height(1080); pix_fmt(AV_PIX_FMT_YUV420P)
    time_base().num(1); time_base().den(30)
    gop_size(60); max_b_frames(0); bit_rate(2_500_000L)
}
av_opt_set(ctx.priv_data(), "preset", "veryfast", 0)
av_opt_set(ctx.priv_data(), "tune", "zerolatency", 0)
avcodec_open2(ctx, codec, null as AVDictionary?)
// per frame: avcodec_send_frame + while (avcodec_receive_packet == 0) emit
```

Opus same pattern: `avcodec_find_encoder(AV_CODEC_ID_OPUS)` → libopus inside the FFmpeg GPL build.

## 4. Linux libavdevice PipeWire ingest

```kotlin
avdevice_register_all()
val ifmt = av_find_input_format("pipewire")!!
val fmt = avformat_alloc_context()
val opts = AVDictionary()
av_dict_set(opts, "fd", fd.toString(), 0)
av_dict_set(opts, "node", nodeId.toString(), 0)
avformat_open_input(fmt, "", ifmt, opts)
```

Fallback B if pipewire not compiled in: bundle static `ffmpeg` CLI from javacpp jar `bin/`, extract + exec. Still mandate-compliant (bundled binary, not system).

## 5. macOS path

`avfoundation` input format in-process replaces the subprocess. ScreenCaptureKit-backed builds (ffmpeg 7+) capture system audio via the avfoundation graph — BlackHole no longer hard dep, becomes optional fallback hint.

`MacScreenSourceEnumerator` rewritten to use `avdevice_list_input_sources` instead of subprocess parsing.

## 6. Binary size + per-OS installers

- Fat jar with platform-gpl: ~170 MB (5 platform classifiers).
- Per-OS module:
  ```kotlin
  val ffmpegVer = "7.1-1.5.11"
  val cls = currentOsArchClassifier()  // e.g. "linux-x86_64-gpl"
  runtimeOnly("org.bytedeco:ffmpeg:$ffmpegVer:$cls")
  implementation("org.bytedeco:ffmpeg:$ffmpegVer")
  implementation("org.bytedeco:javacpp:1.5.11")
  ```
- Per-OS installer ~80 MB (matches CLAUDE.md target with `jlink --strip-debug --compress=zip-9`).

## 7. Distribution with jpackage

`org.beryx.jpackage` plugin. Targets:
- Linux: `.deb`, `.rpm`, `.AppImage`
- macOS: `.dmg` (`.pkg` optional)
- Windows: `.msi` (later)

Pre-extract natives in deb `postinst` to remove first-launch ~3s delay (optional).

## 8. Migration phases

1. **OPUS-JAVACPP** — swap libopus JNA → libavcodec Opus. Existing `OpusCodecTest` still passes.
2. **VIDEO-INPROC** — replace FfmpegVideoEncoder subprocess → LibavVideoEncoder in-process. Behind flag `puklic.voice.encoder=libav|cli` (default libav).
3. **LINUX-PORTAL** — dbus-java-core + portal session + PipeWire libav input. Linux-only code path gated by `Platform.isLinux()`.
4. **MAC-INPROC** — replace osascript + ffmpeg -list_devices subprocess with in-process libavdevice enum. BlackHole → informational hint.
5. **BUILD-JPACKAGE** — `org.beryx.jpackage` per-OS classifier matrix.
6. **CI-INSTALLERS** — GitHub Actions matrix builds installers per OS+arch.

## 9. Risks

1. **GPL impact on distributed binary.** libx264 GPL-2.0 forces distributed binary GPL. Puklic source stays Apache-2.0. Update NOTICE + LICENSE-third-party.txt + `docs/06_ops/licensing.md`.
2. **Opus-only payload pulls ffmpeg.** ~80 MB native even for audio-only. Acceptable — single payload covers voice + screenshare.
3. **JavaCPP first-launch ~3s extract.** Splash; optional pre-extract in installer postinst.
4. **dbus-java fd passing** — supported on Linux via `dbus-java-transport-jnr-unixsocket`. Confirm classpath.
5. **PipeWire libav input in javacpp build** — verify at runtime; if absent, bundled `ffmpeg` CLI fallback.
6. **Wayland on Compose Desktop today is XWayland.** Portal capture is independent; still works.
7. **Binary <80 MB target tight.** `jlink --strip-debug --no-man-pages --no-header-files --compress=zip-9` shaves ~15 MB.
8. **macOS BlackHole removal verification** — confirm Discord screen-share-with-audio works via in-process AVFoundation on macOS 14+.

## 10. Action checklist

- [x] Phase 1: Opus → javacpp libavcodec (abad861)
- [x] Phase 2: in-process libx264 encoder (LibavVideoEncoder via libavformat+libavcodec+libavdevice+libswscale; feature-flagged `puklic.voice.encoder=libav|cli`, default `libav`; in-process avfoundation monitor enum with subprocess fallback B; lavfi-testsrc smoke test green)
- [ ] Phase 3: Linux portal + PipeWire
- [ ] Phase 4: macOS in-process avfoundation
- [ ] Phase 5: jpackage per-OS installers
- [ ] Phase 6: CI matrix
- [ ] NOTICE / licensing docs (GPL bundle disclosure)
- [ ] `docs/05_platforms/linux-wayland.md` portal flow diagram
- [ ] `docs/03_infrastructure/native-dependencies.md` (new)
