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
            // TODO(voice slice 6): re-enable when an Opus/concentus artifact mirror is available.
            //   concentus only ships via jitpack (com.github.jitsi:concentus:1.0.2), and the
            //   local Gradle init script `~/.gradle/init.d/cbl-public-repos.gradle` strips all
            //   non-mavenCentral repositories at projectsLoaded, so the jitpack registration
            //   in settings.gradle.kts gets removed before resolution. Until that's resolved
            //   (mirror to internal Nexus, or vendored JAR, or jitpack auth), the Opus codec
            //   backend in `dev.puklic.voice.codec` will stay stubbed. The current scaffold
            //   only uses the NoOpVoiceClient, which has no codec/AEAD dependencies.
            // implementation(libs.concentus)
            implementation(libs.bouncycastle.bcprov)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
