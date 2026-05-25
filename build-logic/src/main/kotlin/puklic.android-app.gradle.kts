// puklic.android-app — Android application convention plugin
// Applied to: :android:app

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlinx.kover")
}

android {
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.puklic.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        // Sourced from root `gradle.properties` puklic.version — the single source
        // of truth shared with desktop packaging. Android is scaffolding-only at this
        // phase but the version string still tracks the rest of the project.
        versionName = (providers.gradleProperty("puklic.version").orNull)
            ?: error("puklic.version missing in gradle.properties (single source of truth)")
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
