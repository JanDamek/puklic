// puklic.ios-library — iOS-only convention plugin
// Applied to: :ios:app, :ios:platform
// Does NOT configure JVM toolchain; does NOT apply AGP.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
