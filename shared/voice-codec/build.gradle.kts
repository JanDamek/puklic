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
    // FP-14h-2e (issue #69): kotlinx-serialization plugin needed by VoiceGatewayPayload
    // (`@Serializable` data classes) moved from :shared:voice/commonMain.
    alias(libs.plugins.kotlin.serialization)
    // FP-14h-2d (issue #68): atomicfu compiler plugin replaces j.u.c.atomic.* on JVM and
    // enables shared commonMain atomic primitives once future concurrency-heavy moves
    // (VideoRtpSender / PlaybackPipeline / IncomingVideoPipeline) land in this module.
    // See docs/03_infrastructure/architect-reports/2026-05-29-fp14h-2d-implementation.md.
    alias(libs.plugins.kotlinx.atomicfu)
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    jvm()
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    jvmToolchain(21)

    // FP-14h-2a opt-in: types marked @PuklicVoiceCodec inside this module (IpDiscovery,
    // JitterBuffer in commonMain) are referenced by sibling commonMain/jvmMain files
    // without requiring per-callsite @OptIn. Per architect report
    // 2026-05-29-fp14h-1-v2-voice-gateway-redesign.md §2.5.
    sourceSets.all {
        languageSettings.optIn("dev.puklic.voice.codec.PuklicVoiceCodec")
    }

    sourceSets {
        commonMain.dependencies {
            // FP-3 VoiceUdpTransport surface uses kotlinx.coroutines Flow in commonMain.
            api(libs.kotlinx.coroutines.core)
            // AudioConstants and public voice types live here. Re-exported via `api`
            // so JVM consumers of :shared:voice can import `dev.puklic.voice.AudioConstants`
            // transitively (via :shared:voice → api(this) → api(voice-api)).
            api(projects.shared.voiceApi)
            // FP-14h-2e (issue #69): voice gateway types (VoiceGatewayPayload `@Serializable`
            // envelopes, VoiceGatewayConnection JSON encoding, KtorVoiceGatewayTransport)
            // moved here from :shared:voice. JSON + Ktor WebSockets become api-exposed
            // because `ktorVoiceGatewayTransportFactory(HttpClient)` accepts and returns
            // types from these libraries.
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            api(libs.ktor.client.websockets)
            api(libs.kermit)
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
            // XChaCha20-Poly1305 JVM actual backend (FP-14h-2c, issue #67, 2026-05-31).
            // BouncyCastle 1.78 is MIT-licensed — `verifyMacAppStoreNoGplDeps` accepts it.
            // Moved from :shared:voice/jvmMain alongside XChaCha20Poly1305.jvm.kt. See
            // docs/03_infrastructure/architect-reports/2026-05-29-fp14h-2c-implementation.md §2.
            implementation(libs.bouncycastle.bcprov)
            // FP-14h-2d (issue #68): atomicfu factory functions (`atomic(...)`) used by
            // OpusCodec.jvm.kt to replace `java.util.concurrent.atomic.AtomicBoolean`.
            // The atomicfu compiler plugin lowers these to volatile fields + AtomicFieldUpdater
            // at compile time; semantics are byte-identical to j.u.c.atomic on JVM.
            implementation(libs.kotlinx.atomicfu)
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

    // PuklicCryptoBridge static library — wraps Apple CryptoKit ChaChaPoly AEAD for
    // XChaCha20-Poly1305 (FP-14h-2c, issue #67). Compiled per iOS slice from
    // src/iosMain/native/PuklicCryptoBridge/Bridge.swift by the swiftCompile* tasks
    // below; consumed via cinterop using puklic_crypto.def.
    val cryptoBridgeRoot = layout.projectDirectory.dir("src/iosMain/native/PuklicCryptoBridge")
    val cryptoBridgeDef = cryptoBridgeRoot.file("puklic_crypto.def")
    val cryptoBridgeHeaderDir = cryptoBridgeRoot
    val cryptoBridgeSwift = cryptoBridgeRoot.file("Bridge.swift")
    val cryptoBridgeOutRoot = layout.buildDirectory.dir("swift-bridge").get()

    data class IosSliceSpec(
        val taskName: String,
        val outDirName: String,
        val swiftTarget: String,
        val sdk: String,
    )

    val cryptoBridgeSlices = listOf(
        IosSliceSpec("CryptoBridgeIosArm64", "ios-arm64", "arm64-apple-ios14.0", "iphoneos"),
        IosSliceSpec(
            "CryptoBridgeIosSimulatorArm64",
            "ios-arm64-simulator",
            "arm64-apple-ios14.0-simulator",
            "iphonesimulator",
        ),
        IosSliceSpec(
            "CryptoBridgeIosX64",
            "ios-x86_64-simulator",
            "x86_64-apple-ios14.0-simulator",
            "iphonesimulator",
        ),
    )

    val cryptoBridgeTasks = cryptoBridgeSlices.associate { slice ->
        val outDir = cryptoBridgeOutRoot.dir(slice.outDirName)
        val outLib = outDir.file("libpuklic_crypto.a")
        val taskName = "swiftCompile${slice.taskName}"
        val task = tasks.register<Exec>(taskName) {
            group = "puklic"
            description = "Compile PuklicCryptoBridge Swift static library for ${slice.outDirName}"
            inputs.file(cryptoBridgeSwift)
            inputs.file(cryptoBridgeRoot.file("puklic_crypto.h"))
            // Track the Swift target triple so changes to it (or to runtime-compatibility flags)
            // bust the task cache — Gradle otherwise considers the task UP-TO-DATE based solely
            // on file inputs.
            inputs.property("swiftTarget", slice.swiftTarget)
            inputs.property("sdk", slice.sdk)
            outputs.file(outLib)
            doFirst { outDir.asFile.mkdirs() }
            commandLine(
                "xcrun",
                "--sdk",
                slice.sdk,
                "swiftc",
                "-emit-library",
                "-static",
                "-parse-as-library",
                "-O",
                "-module-name",
                "PuklicCryptoBridge",
                "-target",
                slice.swiftTarget,
                // Skip Swift back-compat shim auto-linking — the iOS deployment baseline
                // (14.0, see gradle/libs.versions.toml) ships the Swift runtime in the
                // OS, so the swiftCompatibility5x stubs from older toolchains are not
                // needed and otherwise produce undefined-symbol errors when statically
                // linked into the Kotlin/Native binary.
                "-runtime-compatibility-version",
                "none",
                "-o",
                outLib.asFile.absolutePath,
                cryptoBridgeSwift.asFile.absolutePath,
            )
        }
        slice.outDirName to (task to outDir)
    }

    fun org.jetbrains.kotlin.gradle.plugin.mpp.DefaultCInteropSettings.configureCryptoBridge(
        sliceDir: org.gradle.api.file.Directory,
    ) {
        defFile(cryptoBridgeDef)
        packageName("dev.puklic.voice.crypto.cryptokit")
        compilerOpts("-I", cryptoBridgeHeaderDir.asFile.absolutePath)
        extraOpts("-libraryPath", sliceDir.asFile.absolutePath)
    }

    iosArm64 {
        compilations.getByName("main").cinterops {
            create("libopus") { configureLibopus(xcfRoot.dir("ios-arm64")) }
            val (compileTask, outDir) = cryptoBridgeTasks.getValue("ios-arm64")
            create("puklic_crypto") {
                configureCryptoBridge(outDir)
            }.also { tasks.named(it.interopProcessingTaskName).configure { dependsOn(compileTask) } }
        }
    }
    iosSimulatorArm64 {
        compilations.getByName("main").cinterops {
            create("libopus") { configureLibopus(xcfRoot.dir("ios-arm64_x86_64-simulator")) }
            val (compileTask, outDir) = cryptoBridgeTasks.getValue("ios-arm64-simulator")
            create("puklic_crypto") {
                configureCryptoBridge(outDir)
            }.also { tasks.named(it.interopProcessingTaskName).configure { dependsOn(compileTask) } }
        }
    }
    iosX64 {
        compilations.getByName("main").cinterops {
            create("libopus") { configureLibopus(xcfRoot.dir("ios-arm64_x86_64-simulator")) }
            val (compileTask, outDir) = cryptoBridgeTasks.getValue("ios-x86_64-simulator")
            create("puklic_crypto") {
                configureCryptoBridge(outDir)
            }.also { tasks.named(it.interopProcessingTaskName).configure { dependsOn(compileTask) } }
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
