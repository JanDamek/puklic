// puklic.android-app — Android application convention plugin
// Applied to: :android:app

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlinx.kover")
}

val millisPerMinute = 60_000L
val uploadKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH")

android {
    compileSdk = 37
    defaultConfig {
        applicationId = "cz.damek.puklic.app"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.environmentVariable("ANDROID_VERSION_CODE").orNull?.toInt()
            ?: (System.currentTimeMillis() / millisPerMinute).toInt()
        // Sourced from root `gradle.properties` puklic.version — the single source
        // of truth shared with desktop packaging. Android is scaffolding-only at this
        // phase but the version string still tracks the rest of the project.
        versionName = (providers.gradleProperty("puklic.version").orNull)
            ?: error("puklic.version missing in gradle.properties (single source of truth)")
    }
    signingConfigs {
        if (uploadKeystorePath.isPresent) {
            create("upload") {
                storeFile = file(uploadKeystorePath.get())
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
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
