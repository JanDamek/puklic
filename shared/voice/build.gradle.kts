// :shared:voice — Voice Phase 3.0.
//
// Per architect report docs/03_infrastructure/architect-reports/2026-05-23-voice.md §2,
// this module exposes a KMP commonMain public API (VoiceClient, VoiceState, AudioDevice,
// AudioConstants) but its real audio/codec/AEAD I/O is jvm-only (concentus, BouncyCastle,
// javax.sound.sampled).
//
// We do NOT use the puklic.kmp-library convention plugin here, because that plugin
// always declares Android + iOS targets, which would force commonMain to compile against
// expect/actual surfaces that have no current actual implementation. When an Android or
// iOS audio backend is added, that engineer adds the target declaration alongside the
// real platform sources.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    jvmToolchain(21)

    // FP-14h-2a opt-in: the legacy UdpRtpTransport interface + expect newUdpRtpTransport()
    // (commonMain/transport/UdpRtpTransport.kt) and the JitterBuffer used by PlaybackPipeline
    // are marked @PuklicVoiceCodec (defined in :shared:voice-codec). Module-level opt-in
    // avoids per-callsite @OptIn annotations across DefaultVoiceClient et al. Per architect
    // report 2026-05-29-fp14h-1-v2-voice-gateway-redesign.md §2.5.
    sourceSets.all {
        languageSettings.optIn("dev.puklic.voice.codec.PuklicVoiceCodec")
    }

    sourceSets {
        commonMain.dependencies {
            // Re-export the Apache-2.0 public types so existing JVM consumers
            // (and :desktop:app) keep resolving dev.puklic.voice.VoiceClient et al.
            // transitively. See docs/03_infrastructure/architect-reports/2026-05-28-voice-api-split.md.
            api(projects.shared.voiceApi)
            api(projects.shared.voiceCodec)
            // Re-export :shared:screencast (FP-7, 2026-05-29) so existing JVM consumers
            // of dev.puklic.voice.screenshare.encoder.{VideoEncoder, VideoCodec, chooseCodec,
            // LibavVideoEncoder, libavVideoEncoder, AnnexBStreamReader} and
            // dev.puklic.voice.screenshare.{linux,source}.* keep resolving via transitive
            // api. Package names preserved across the move; only the owning Gradle module
            // changed. See architect report
            // docs/03_infrastructure/architect-reports/2026-05-29-fp7-screencast-extraction.md.
            api(projects.shared.screencast)
            implementation(projects.shared.ids)
            implementation(projects.shared.domain)
            implementation(projects.shared.platformApi)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            // DAVE end-to-end voice encryption (Phase 3.1d wiring).
            implementation(projects.shared.voiceDave)
            // Opus codec: libavcodec via JavaCPP FFmpeg GPL bundle (self-contained, no system
            // libopus needed). Opus ships *inside* org.bytedeco:ffmpeg-platform-gpl 7.1-1.5.11;
            // there is no separate `opus-platform` artifact on Maven Central.
            // Natives are extracted to ~/.javacpp/cache/ on first use (2-5s).
            // See shared/voice/src/jvmMain/kotlin/dev/puklic/voice/codec/OpusCodec.jvm.kt.
            implementation(libs.javacpp)
            implementation(libs.ffmpeg.bindings)
            // Per-OS classifier only — keeps jpackage installer size ~30 MB of natives
            // instead of ~150 MB. See Phase 5 of
            // docs/03_infrastructure/architect-reports/2026-05-23-self-contained-linux.md.
            // The umbrella `ffmpeg-platform-gpl` artifact (all classifiers) is used only
            // in tests via testRuntimeOnly below for cross-host CI convenience.
            runtimeOnly("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.get()}:${detectFfmpegClassifier()}")
            // BouncyCastle dependency moved to :shared:voice-codec/jvmMain alongside the
            // XChaCha20Poly1305 JVM actual (FP-14h-2c, issue #67, 2026-05-31). See
            // docs/03_infrastructure/architect-reports/2026-05-29-fp14h-2c-implementation.md §2.
            // Linux xdg-desktop-portal ScreenCast over session D-Bus.
            // Both artifacts published to Maven Central. See architect report
            // docs/03_infrastructure/architect-reports/2026-05-23-self-contained-linux.md §4
            // phase 3. On non-Linux hosts the class is loaded lazily, so the deps are
            // a small classpath cost (~1 MB) but never trigger D-Bus on macOS/Windows.
            implementation(libs.dbus.java.core)
            implementation(libs.dbus.java.transport.jnr.unixsocket)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
            // For tests we keep the umbrella artifact so cross-host CI / IDE test runs
            // pick the right native automatically without depending on host detection
            // matching the runner. Production runtime uses the slim classifier above.
            runtimeOnly(libs.ffmpeg.platform.gpl)
        }
    }
}

/**
 * Detects the JavaCPP FFmpeg classifier for the *host* JVM running Gradle. Used to pick
 * a single OS/arch native bundle (~30 MB) instead of the umbrella `ffmpeg-platform-gpl`
 * artifact (~150 MB, all classifiers). CI matrix builds get the correct natives because
 * each runner has its own host JVM.
 *
 * GPL build (suffix `-gpl`) is required because Puklic links libx264 for screen-share
 * H.264 encoding (see voice architect report §3).
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
    // JavaCPP extracts bundled natives to a per-user cache dir. The default is
    // `$HOME/.javacpp/cache/`, which on some hosts lives on an external volume with
    // restrictive ACLs on the pre-existing `.lock` file. Pin the cache under the project
    // build dir to keep tests hermetic and avoid permission flakes on developer machines.
    systemProperty("org.bytedeco.javacpp.cachedir", layout.buildDirectory.dir("javacpp-cache").get().asFile.absolutePath)
}
