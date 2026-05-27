// :shared:voice-dave — DAVE (Discord Audio/Video End-to-End encryption) Phase 3.1b spike.
//
// Per architect report docs/03_infrastructure/architect-reports/2026-05-23-dave-e2ee.md
// and ADR-0007, this module wraps Wire's MLS (RFC 9420) library `com.wire:core-crypto-jvm`
// behind a Puklic-internal MlsClient interface. Wire types are NEVER exposed across this
// boundary — see CLAUDE.md repo rule §3 (Discord/library DTOs must not leak).
//
// JVM-only: `core-crypto-jvm` ships a Rust JNI native (.so / .dylib / .dll). Android and
// iOS targets are added by the engineer who wires the corresponding native artifacts
// (Android .aar exists; `core-crypto-iosarm64` is currently stuck at 0.6.0-rc on Maven
// Central as of Feb 2024).
//
// Licensing: `com.wire:core-crypto-jvm` is GPL-3.0-or-later. Puklic source remains
// Apache-2.0; the distributed binary's effective license becomes GPL-3.0-or-later
// (was GPL-2.0-or-later from libx264). See ADR-0007 + docs/06_ops/licensing.md.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            // Wire core-crypto: MLS (RFC 9420) over a Rust JNI native lib. GPL-3.0-or-later.
            // 4.2.0 pinned per ADR-0007. 9.x exists but is unverified against DAVE spike;
            // upgrade only after a focused regression pass.
            // Retained as fallback backend behind the puklic.voice.dave.backend system property.
            implementation(libs.wire.core.crypto.jvm)
            // BouncyCastle HKDF for local Expand-on-top-of-Wire's MLS exporter, until
            // a backend exposes a labelled MLS-Exporter (Phase 3.2 libdave-JNI).
            implementation(libs.bouncycastle.bcprov)
            // JNA for libdave (Discord MIT C++ reference). Native lib bundled under
            // src/jvmMain/resources/libdave/<os-arch>/libdave.{dylib,so,dll}.
            implementation(libs.jna)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
