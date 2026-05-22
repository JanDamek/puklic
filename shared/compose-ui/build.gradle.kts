plugins {
    id("puklic.compose-library")
}

// Compose Multiplatform 1.8.0 transitively pulls androidx artifacts hosted on Google Maven.
// settings.gradle.kts declares google() in dependencyResolutionManagement, but the Compose
// plugin auto-registers project-level repositories which override settings (default mode is
// PREFER_PROJECT). Re-declaring google() at the module level closes the gap until we
// switch repositoriesMode to PREFER_SETTINGS in a foundational settings cleanup.
repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.domain)
            implementation(projects.shared.ids)
            implementation(projects.shared.repositories)
            implementation(projects.shared.session)
            implementation(projects.shared.platformApi)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.decompose)
            implementation(libs.decompose.compose)
            implementation(libs.essenty.lifecycle.coroutines)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.coil.core)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
        }
    }
}

android {
    namespace = "dev.puklic.shared.composeui"
}
