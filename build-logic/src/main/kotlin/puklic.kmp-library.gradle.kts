// puklic.kmp-library — convention plugin for all :shared:* KMP library modules
// Targets: JVM + Android + iosArm64 + iosX64 + iosSimulatorArm64
//
// Design: convention plugins configure toolchain, targets, and test runner — NOT library versions.
// Library coordinates stay in each module's build.gradle.kts + gradle/libs.versions.toml.
// This avoids the libs-in-precompiled-scripts classpath issue (Gradle 8.x included build limitation).
//
// puklic.detekt is a standalone opt-in plugin — NOT auto-applied here.
// Reason: Detekt 1.23.8 can't load BaseExtension from AGP 8.7.2 via ClassLoader isolation (known issue #3).

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    // Default hierarchy template auto-applied by Kotlin 2.x — no explicit call needed
    jvm()
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
            }
        }
    }
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            // Each module adds its own commonMain dependencies in its build.gradle.kts
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
