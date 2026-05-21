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
        versionName = "0.1.0"
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
