// :shared:voice-codec — Apache-2.0 KMP Discord voice transport codec.
//
// Apache-2.0 + KMP-wide (jvm + iOS) so future App Store iOS / macOS builds
// can share the AEAD packet framing / nonce sequencing / RTP header /
// Opus codec contract with the GPL desktop build without pulling in
// :shared:voice's BouncyCastle / FFmpeg-GPL / libdave.
//
// Targets explicitly: jvm + iosArm64 + iosX64 + iosSimulatorArm64. The
// puklic.kmp-library convention plugin is intentionally NOT used here for
// the same reason as :shared:voice: it always declares an Android target,
// which would force `expect object OpusCodecFactory` to grow an Android
// `actual` that we cannot satisfy honestly (FFmpeg-GPL JavaCPP has no
// Android natives; Android is non-shipping scaffolding per CLAUDE.md).
// When an Android codec backend lands, this module adds the target
// alongside the real platform sources.
//
// commonMain contents:
//   - AeadCipher interface (the pluggable cipher contract)
//   - NonceGenerator (24-byte XChaCha20 nonce counter, _rtpsize layout)
//   - RtpPacket (12-byte RTP header read/write)
//   - VoicePacketCodec (encode/decode RTP + AEAD glue)
//   - EncodedFrame (Annex-B video payload + RTP timestamp + keyframe flag) — FP-2
//   - H264Encoder / H264Decoder + factories (KMP video codec contract) — FP-2,
//     platform impls land in FP-5 (iOS / macOS VideoToolbox)
//   - VoiceUdpTransport / Endpoint / VoiceUdpTransportFactory (KMP UDP transport
//     contract) — FP-3, JVM bridge in :shared:voice/jvmMain, iOS impl in FP-6
//   - OpusEncoder / OpusDecoder / OpusEncoderConfig / OpusApplication /
//     OpusException / `expect object OpusCodecFactory` — FP-4 (moved from
//     :shared:voice 2026-05-29). The `dev.puklic.voice.codec.*` package path
//     is preserved so JVM consumers' imports keep resolving transitively via
//     :shared:voice → api(:shared:voice-codec).
//
// jvmMain contents:
//   - LibavOpusEncoder / LibavOpusDecoder — actual for OpusCodecFactory via
//     libavcodec/libopus bundled in JavaCPP FFmpeg-GPL.
//
// iosMain contents (FP-4):
//   - IosOpusEncoder / IosOpusDecoder — actual for OpusCodecFactory via
//     upstream libopus 1.5.2 (BSD-3-Clause) cinterop on top of the committed
//     Opus.xcframework binary at `libs/Opus.xcframework`.
//
// Licence isolation: the FFmpeg-GPL JVM deps below are restricted to the
// jvm compilation. Kotlin/Native iOS compilations never see them, and
// :ios:app:verifyIosNoGplDeps scans :ios:app's resolved classpath, which
// does not include the JVM deps.
//
// See docs/03_infrastructure/architect-reports/2026-05-29-fp1-voice-codec-extraction.md
// See docs/03_infrastructure/architect-reports/2026-05-29-fp4-ios-opus-libopus.md
// See docs/03_infrastructure/architect-reports/2026-05-29-full-feature-parity.md §3.1, §3.2, §5.1
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
            // FP-3 VoiceUdpTransport surface uses kotlinx.coroutines Flow in commonMain.
            api(libs.kotlinx.coroutines.core)
            // AudioConstants and public voice types live here. Re-exported via `api`
            // so JVM consumers of :shared:voice can import `dev.puklic.voice.AudioConstants`
            // transitively (via :shared:voice → api(this) → api(voice-api)).
            api(projects.shared.voiceApi)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotest.assertions.core)
        }
        jvmMain.dependencies {
            // Opus codec JVM actual: libavcodec/libopus via JavaCPP FFmpeg GPL bundle.
            // Self-contained — no system libopus required. Natives extract to
            // ~/.javacpp/cache/ on first use. Moved here from :shared:voice 2026-05-29
            // (FP-4) so OpusCodecFactory `actual` colocates with the expect
            // declaration in this module.
            implementation(libs.javacpp)
            implementation(libs.ffmpeg.bindings)
            // Per-OS classifier only — keeps installer size small. Umbrella
            // `ffmpeg-platform-gpl` is used in tests only (cross-host CI convenience).
            runtimeOnly("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.get()}:${detectFfmpegClassifier()}")
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
            runtimeOnly(libs.ffmpeg.platform.gpl)
        }
    }

    // iOS cinterop for libopus. Prebuilt Opus.xcframework lives at
    // shared/voice-codec/libs/ and is built by
    // dist/apple/build-libopus-xcframework.sh from upstream xiph/opus v1.5.2.
    // SHA pinned in libs/Opus.xcframework.sha256.
    val xcfRoot = layout.projectDirectory.dir("libs/Opus.xcframework")
    val defFile = layout.projectDirectory.file("src/iosMain/native/libopus.def")

    fun org.jetbrains.kotlin.gradle.plugin.mpp.DefaultCInteropSettings.configureLibopus(sliceDir: org.gradle.api.file.Directory) {
        defFile(defFile)
        packageName("dev.puklic.voice.codec.libopus")
        // Headers live alongside the libopus.a slice. compilerOpts passes -I to
        // libclang at index time so `#include <opus.h>` resolves; extraOpts -libraryPath
        // tells the linker where to find libopus.a at static-link time.
        compilerOpts("-I", sliceDir.dir("Headers").asFile.absolutePath)
        extraOpts("-libraryPath", sliceDir.asFile.absolutePath)
    }

    iosArm64 {
        compilations.getByName("main").cinterops {
            create("libopus") { configureLibopus(xcfRoot.dir("ios-arm64")) }
        }
    }
    iosSimulatorArm64 {
        compilations.getByName("main").cinterops {
            create("libopus") { configureLibopus(xcfRoot.dir("ios-arm64_x86_64-simulator")) }
        }
    }
    iosX64 {
        compilations.getByName("main").cinterops {
            create("libopus") { configureLibopus(xcfRoot.dir("ios-arm64_x86_64-simulator")) }
        }
    }
}

/**
 * Detects the JavaCPP FFmpeg classifier for the *host* JVM running Gradle. Used to pick
 * a single OS/arch native bundle (~30 MB) instead of the umbrella `ffmpeg-platform-gpl`
 * artifact (~150 MB). GPL build (suffix `-gpl`) is required because Puklic links
 * libx264 for screen-share H.264 encoding (see voice architect report §3).
 */
fun detectFfmpegClassifier(): String {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    return when {
        osName.contains("linux") && osArch == "amd64" -> "linux-x86_64-gpl"
        osName.contains("linux") && osArch in setOf("aarch64", "arm64") -> "linux-arm64-gpl"
        osName.contains("mac") && osArch in setOf("aarch64", "arm64") -> "macosx-arm64-gpl"
        // Windows and macOS x86_64 are out of scope (issue #22, CLAUDE.md §Platforms).
        else -> error("Unsupported OS/arch for FFmpeg native classifier: $osName / $osArch")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // JavaCPP extracts bundled natives to a per-user cache dir. Pin under build dir
    // to keep tests hermetic.
    systemProperty(
        "org.bytedeco.javacpp.cachedir",
        layout.buildDirectory.dir("javacpp-cache").get().asFile.absolutePath,
    )
}
