enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://jitpack.io") // for com.github.jitsi:concentus (Opus codec, jvm-only :shared:voice)
    }
}

rootProject.name = "puklic"

// ── Shared KMP modules ─────────────────────────────────────────────────────
include(":shared:ids")
include(":shared:domain")
include(":shared:platform-api")
include(":shared:chat-parser")
include(":shared:protocol-discord")
include(":shared:persistence-api")
include(":shared:persistence-sqldelight")
include(":shared:repositories")
include(":shared:session")
include(":shared:voice-api")
include(":shared:voice-codec")
include(":shared:screencast")
include(":shared:voice")
include(":shared:voice-dave")
include(":shared:compose-ui")

// ── Desktop modules ────────────────────────────────────────────────────────
include(":desktop:app")
include(":desktop:platform-linux")
include(":desktop:platform-macos")
include(":desktop:platform-windows")

// ── Android modules ────────────────────────────────────────────────────────
include(":android:app")
include(":android:platform")

// ── iOS modules ────────────────────────────────────────────────────────────
include(":ios:app")
include(":ios:platform")

// ── Tools ──────────────────────────────────────────────────────────────────
include(":tools:parser-fixtures-gen")
