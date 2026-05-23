// :shared:voice — Voice Phase 3.0 (jvm-only initially).
//
// Per architect report docs/03_infrastructure/architect-reports/2026-05-23-voice.md §2,
// this module exposes a KMP commonMain public API (VoiceClient, VoiceState, AudioDevice,
// AudioConstants) but its real audio/codec/AEAD I/O is jvm-only (concentus, BouncyCastle,
// javax.sound.sampled). Android / iOS targets are intentionally commented out — they will
// be enabled in Phase 3.1+ once platform-native audio backends land.
//
// We do NOT use the puklic.kmp-library convention plugin here, because that plugin
// always declares Android + iOS targets, and we want to keep this module strictly
// jvm-only for now to avoid compiling against unimplemented expect/actual surfaces.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    // androidTarget()                // TODO Phase 3.1: enable when Android audio backend lands.
    // iosArm64()                     // TODO Phase 3.x: iOS voice support.
    // iosX64()
    // iosSimulatorArm64()

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
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
            // Opus codec: libavcodec via JavaCPP FFmpeg GPL bundle (self-contained, no system
            // libopus needed). Opus ships *inside* org.bytedeco:ffmpeg-platform-gpl 7.1-1.5.11;
            // there is no separate `opus-platform` artifact on Maven Central.
            // Natives are extracted to ~/.javacpp/cache/ on first use (2-5s).
            // See shared/voice/src/jvmMain/kotlin/dev/puklic/voice/codec/OpusCodec.jvm.kt.
            implementation(libs.javacpp)
            implementation(libs.ffmpeg.bindings)
            implementation(libs.ffmpeg.platform.gpl)
            implementation(libs.bouncycastle.bcprov)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
        }
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
