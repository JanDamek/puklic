# FP-4 — iOS Opus codec via libopus 1.5.2 XCFramework

Date: 2026-05-29
Slice: FP-4 (issue #44)
Parent: `2026-05-29-full-feature-parity.md` §3.2, §5.1, §6, §7

## 1. Context

`:shared:voice-codec` already targets `iosArm64`, `iosX64`, and `iosSimulatorArm64` via
the `puklic.kmp-library` convention plugin. It currently contains only
licence-clean Kotlin transport code (cipher contract, RTP framing, voice
packet codec, H264 interfaces). The Opus codec contract
(`OpusEncoder` / `OpusDecoder` / `OpusEncoderConfig` / `OpusException` /
`expect object OpusCodecFactory`) still lives in `:shared:voice` commonMain,
which is JVM-only and therefore unreachable from `:ios:app`.

FP-4 unblocks `:ios:app` voice by:

1. Moving the Opus codec contract into `:shared:voice-codec` commonMain so
   both JVM and iOS targets can supply `actual`s.
2. Moving the JVM `actual` (FFmpeg-GPL libopus wrapper) into
   `:shared:voice-codec` jvmMain. Because that pulls FFmpeg-GPL into a
   previously licence-clean module, the `org.bytedeco:ffmpeg*` deps must NOT
   appear in the published `:shared:voice-codec` iOS Kotlin/Native artefact
   set, and the existing `:ios:app:verifyIosNoGplDeps` task continues to
   guard the iOS classpath.
3. Adding an iOS `actual` backed by upstream libopus 1.5.2 via cinterop on
   top of a multi-arch `Opus.xcframework`.

## 2. Decisions

### 2.1 `expect object OpusCodecFactory` location

**Move from `:shared:voice` commonMain to `:shared:voice-codec` commonMain.**
Rationale: `:shared:voice-codec` is the licence-clean home for transport-
adjacent codec contracts (already true for `H264Encoder`/`Decoder`); Opus
fits the same bracket. `:shared:voice-api` is types-only by intent and
should remain free of factory contracts.

`:shared:voice-codec` commonMain depends on `:shared:voice-api` already
indirectly (it pulls in `EncodedFrame`-style types but not `AudioConstants`
yet). FP-4 adds `api(projects.shared.voiceApi)` to
`:shared:voice-codec` so the moved Opus contract can keep referencing
`AudioConstants.SAMPLES_PER_FRAME` etc.

### 2.2 libopus version pin

`v1.5.2` — latest stable upstream tag at xiph/opus gitlab as of 2026-05-29.
Pinned by `OPUS_VERSION` env var in `dist/apple/build-libopus-xcframework.sh`
and recorded in `shared/voice-codec/libs/Opus.xcframework.sha256`.

### 2.3 Build vs commit prebuilt XCFramework

**Commit prebuilt XCFramework** (`shared/voice-codec/libs/Opus.xcframework/`)
plus a SHA-256 manifest (`Opus.xcframework.sha256`). Build script
(`dist/apple/build-libopus-xcframework.sh`) is reproducible and idempotent;
CI does not rebuild on every run — only when the manifest SHA or the
upstream tag changes (manual bump). The script emits the SHA at the end so
local rebuilds can be diffed against the committed manifest.

Rationale: ~1 MB binary, deterministic, avoids 5-10 min build per CI run.
Matches how `:shared:voice-dave` ships its prebuilt libdave bundle today.

### 2.4 cinterop `.def` shape

```
language = Objective-C
package = dev.puklic.voice.codec.libopus
headers = opus.h opus_defines.h opus_types.h opus_multistream.h
staticLibraries = libopus.a
```

Per-target `libraryPaths` injected from `build.gradle.kts` so each Kotlin/
Native target points at the correct slice of `Opus.xcframework`.

### 2.5 Licence

`libopus` is BSD-3-Clause. Compatible with the App Store. `dep-policy.md`
gains a row noting the in-repo XCFramework binary.

## 3. Module deltas

| Path | Action |
|---|---|
| `shared/voice/src/commonMain/.../codec/OpusCodec.kt` | DELETE — moved |
| `shared/voice/src/jvmMain/.../codec/OpusCodec.jvm.kt` | DELETE — moved |
| `shared/voice-codec/src/commonMain/.../codec/OpusCodec.kt` | NEW (same content as moved) |
| `shared/voice-codec/src/jvmMain/.../codec/OpusCodec.jvm.kt` | NEW (same content as moved) |
| `shared/voice-codec/src/iosMain/.../codec/IosOpusCodec.kt` | NEW — actual via libopus cinterop |
| `shared/voice-codec/src/iosMain/native/libopus.def` | NEW |
| `shared/voice-codec/src/iosTest/.../codec/IosOpusRoundTripTest.kt` | NEW |
| `shared/voice-codec/libs/Opus.xcframework/` | NEW (prebuilt binary) |
| `shared/voice-codec/libs/Opus.xcframework.sha256` | NEW (manifest) |
| `shared/voice-codec/build.gradle.kts` | jvm: add FFmpeg-GPL + JavaCPP deps (moved from `:shared:voice`); ios: cinterop block |
| `shared/voice/build.gradle.kts` | drop FFmpeg/JavaCPP deps that moved into voice-codec; keep transitively via `api(projects.shared.voiceCodec)` |
| `dist/apple/build-libopus-xcframework.sh` | NEW |
| `docs/03_infrastructure/dep-policy.md` | add libopus row |

`:shared:voice` already re-exports `:shared:voice-codec` via `api(...)`, so
moving the Opus types from `:shared:voice` to `:shared:voice-codec` does not
break existing JVM consumers — same package path `dev.puklic.voice.codec.*`
resolves transitively.

The FFmpeg-GPL JVM deps that today sit in `:shared:voice` for the Opus
actual move with the actual into `:shared:voice-codec` jvmMain. The
licence-clean Kotlin/Native compilation of `:shared:voice-codec` is not
affected — those deps never reach iosArm64/iosX64/iosSimulatorArm64
classpaths (KMP target-specific dependency isolation). The
`:ios:app:verifyIosNoGplDeps` task continues to scan the iOS module graph
and will pass because the FFmpeg deps are only in the JVM compilation.

## 4. Self-critic findings

1. **Cinterop staticLibrary path per target.** XCFramework slices have
   distinct paths (`ios-arm64/libopus.a` vs
   `ios-arm64_x86_64-simulator/libopus.a`). The `.def` cannot pick
   per-target, so per-target `cinterops { libopus { ... } }` blocks must set
   `libraryPaths` from Gradle. Implemented.
2. **`opus_multistream.h` not strictly required** for mono/stereo encode/
   decode. Kept in `headers =` for forward compatibility with future
   surround channels (cheap — headers don't bloat the klib).
3. **Static linking — no separate framework needed.** The `libopus.a`
   slices are pulled directly into the Kotlin/Native binary. No
   `-framework Opus` at link time; cinterop `staticLibraries` handles it.
4. **Build script reproducibility.** Uses a tagged shallow clone of upstream
   so the produced binary is byte-stable across machines (modulo Xcode/llvm
   minor differences). SHA emitted at the end; manifest committed.
5. **Test gating.** Kotlin/Native iOS tests require a simulator; on the
   build host without `iosX64`/`iosSimulatorArm64` runners we accept the
   compile-only gate (`compileKotlinIosArm64`). The round-trip test is
   still useful when run on a real simulator.

## 5. Risks

- **Building libopus needs `autoconf`, `automake`, `libtool`, `pkg-config`,
  Xcode CLT.** First-time setup. Install commands in script header.
- **Future xiph repo move** — pin upstream URL in script; if URL changes,
  bump `OPUS_VERSION` block.
- **App Store binary size** — ~120 KB per arch, ~360 KB total. Negligible.
