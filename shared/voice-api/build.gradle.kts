// :shared:voice-api — Apache-2.0 public voice/screenshare types (KMP-wide).
//
// Apache-2.0 + KMP-wide (jvm + Android + iOS via puklic.kmp-library) so that
// :shared:session and :shared:compose-ui can depend on voice types without
// pulling :shared:voice JVM-only GPL deps (FFmpeg-GPL, libdave, x264, JNA,
// javacpp, BouncyCastle, dbus-java).
//
// Contents:
//   - public interfaces / sealed states / data classes (PublicApi.kt)
//   - no-op fallback implementations (NoOpVoiceClient, NoOpScreenShareClient)
//   - pure policy (DaveDowngradeDetector)
//
// No native code, no JVM-only artifacts. The fully-qualified Kotlin package is
// preserved (dev.puklic.voice[.screenshare]) so consumers' import statements
// are unchanged across the split — only Gradle dependency edges move.
//
// See docs/03_infrastructure/architect-reports/2026-05-28-voice-api-split.md
// See docs/03_infrastructure/dep-policy.md

plugins {
    id("puklic.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.ids)
            implementation(projects.shared.domain)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotest.assertions.core)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
        }
    }
}

android {
    namespace = "dev.puklic.shared.voiceapi"
}
