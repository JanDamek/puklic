// :shared:screencast — Apache-2.0 KMP screen capture contract + Linux JVM actual.
//
// Apache-2.0 + KMP (jvm + iOS) so future App Store iOS / macOS builds share the
// ScreenCapture / ScreenSourceEnumerator / ScreenCaptureFactory contracts with the
// GPL desktop build without pulling :shared:voice's FFmpeg-GPL / libx264 / libvpx
// transitives.
//
// Targets explicitly: jvm + iosArm64 + iosX64 + iosSimulatorArm64. NOT using the
// puklic.kmp-library convention plugin because it always declares an Android
// target, which we cannot satisfy honestly here (Android screen capture would
// require MediaProjection bindings that are non-shipping scaffolding per
// CLAUDE.md). Same rationale as :shared:voice-codec.
//
// commonMain contents:
//   - ScreenCapture / ScreenCaptureFactory / ScreenSourceEnumerator interfaces
//     (dev.puklic.screencast) — the KMP-wide public surface.
//   - VideoEncoder / VideoCodec / chooseCodec
//     (dev.puklic.voice.screenshare.encoder) — moved from :shared:voice 2026-05-29
//     (FP-7) to keep cross-module visibility of the encoder contract that the
//     Linux JVM actual implements. Package preserved so JVM consumers' imports
//     keep resolving transitively via :shared:voice → api(:shared:screencast).
//
// jvmMain contents (FP-7, all moved from :shared:voice/jvmMain):
//   - LibavVideoEncoder + AnnexBStreamReader — libavcodec H.264/VP8 encoder.
//   - LinuxPortalScreenCast + PortalStream + PipeWireAudioReader — xdg-desktop-portal
//     ScreenCast D-Bus + PipeWire PCM audio reader.
//   - LinuxScreenSourceEnumerator — Linux source picker (single synthetic
//     "portal" entry; portal Start dialog does the real selection).
//
// iosMain contents (later — FP-12):
//   - ReplayKit broadcast extension wiring + VideoToolbox H.264 encoder use.
//
// macOS / Windows JVM actuals land in FP-8 / FP-9.
//
// Licence isolation: the FFmpeg-GPL JVM deps below are restricted to the jvm
// compilation. Kotlin/Native iOS compilations never see them, and
// :ios:app:verifyIosNoGplDeps scans :ios:app's resolved classpath, which does
// not include this module's jvm artifacts (because :ios:app does not depend on
// the jvm target of :shared:screencast).
//
// See docs/03_infrastructure/architect-reports/2026-05-29-fp7-screencast-extraction.md
// See docs/03_infrastructure/architect-reports/2026-05-29-full-feature-parity.md §3.3, §7
// See docs/03_infrastructure/dep-policy.md

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    jvm()
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            // ScreenSource (Apache-2.0, KMP-wide) lives in :shared:voice-api; re-export
            // so screencast consumers get it transitively without a second module hop.
            api(projects.shared.voiceApi)
            // EncodedFrame, RtpPacket, H264Encoder (FP-2) live in :shared:voice-codec;
            // re-export so the ScreenCapture.frames: Flow<EncodedFrame> contract resolves
            // without forcing every consumer to add :shared:voice-codec explicitly.
            api(projects.shared.voiceCodec)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            // FFmpeg GPL bundle — libavcodec/libx264/libvpx for screen-share H.264/VP8
            // encoding and the `pipewire` libavdevice input format for portal video
            // node consumption plus swresample for portal audio resampling.
            // Self-contained — no system FFmpeg required; natives extract to
            // ~/.javacpp/cache/ on first use.
            implementation(libs.javacpp)
            implementation(libs.ffmpeg.bindings)
            // Per-OS classifier only — keeps installer size small. The umbrella
            // `ffmpeg-platform-gpl` artifact is used only in tests below for
            // cross-host CI convenience.
            runtimeOnly("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.get()}:${detectFfmpegClassifier()}")
            // Linux xdg-desktop-portal ScreenCast over session D-Bus. Same artefacts
            // :shared:voice uses for the rest of the portal flow.
            implementation(libs.dbus.java.core)
            implementation(libs.dbus.java.transport.jnr.unixsocket)
            // JNA — used by the macOS ScreenCaptureKit bridge (FP-8) to call the
            // Objective-C runtime + Foundation / CoreMedia / CoreVideo /
            // ScreenCaptureKit Apple frameworks via libobjc.dylib selectors. Same
            // version dimension as :shared:voice-dave's libdave bindings.
            // Also used by the Windows DXGI Output Duplication + WASAPI loopback
            // bridge (FP-9) to call COM vtables on Dxgi.dll / D3d11.dll /
            // Ole32.dll. jna-platform provides Win32 type aliases (HRESULT, GUID,
            // HMONITOR, HWND) plus the bundled User32 / Ole32 / Kernel32 wrappers
            // the bridge reuses for monitor enumeration and COM init.
            implementation(libs.jna)
            implementation(libs.jna.platform)
            implementation(libs.kermit)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
            // Umbrella artifact for tests — keeps host detection out of the way
            // when CI / IDE runs tests on a host whose classifier doesn't match
            // the one detectFfmpegClassifier() picked.
            runtimeOnly(libs.ffmpeg.platform.gpl)
        }
    }
}

/**
 * Detects the JavaCPP FFmpeg classifier for the *host* JVM running Gradle. Used to pick a
 * single OS/arch native bundle (~30 MB) instead of the umbrella `ffmpeg-platform-gpl`
 * artifact (~150 MB). GPL build (suffix `-gpl`) is required because Puklic links libx264
 * and libvpx for screen-share encoding (see voice + screenshare architect reports).
 */
fun detectFfmpegClassifier(): String {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    return when {
        osName.contains("linux") && osArch == "amd64" -> "linux-x86_64-gpl"
        osName.contains("linux") && osArch in setOf("aarch64", "arm64") -> "linux-arm64-gpl"
        osName.contains("mac") && osArch in setOf("aarch64", "arm64") -> "macosx-arm64-gpl"
        // Windows x86_64 added 2026-05-29 (FP-9) for the .exe / .msi distribution
        // channel — DXGI Output Duplication capture feeds frames into libx264
        // via the same javacpp FFmpeg-GPL bundle Linux uses.
        osName.contains("windows") && osArch == "amd64" -> "windows-x86_64-gpl"
        // macOS x86_64 is out of scope (CLAUDE.md §Platforms).
        else -> error("Unsupported OS/arch for FFmpeg native classifier: $osName / $osArch")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // JavaCPP extracts bundled natives to a per-user cache. Pin under build dir so
    // tests are hermetic — same convention as :shared:voice / :shared:voice-codec.
    systemProperty(
        "org.bytedeco.javacpp.cachedir",
        layout.buildDirectory.dir("javacpp-cache").get().asFile.absolutePath,
    )
}
