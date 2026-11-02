plugins {
    id("puklic.compose-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.domain)
            implementation(projects.shared.chatParser)
            implementation(projects.shared.ids)
            implementation(projects.shared.repositories)
            implementation(projects.shared.session)
            implementation(projects.shared.platformApi)
            implementation(projects.shared.persistenceApi)
            implementation(projects.shared.voiceApi)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
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
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
        }
        // iOS source set — needed for FP-12 to reach :shared:screencast iosMain
        // (IosScreenSourceEnumerator) from the iOS VoiceDock actual + the iOS
        // BroadcastPickerHost / IosShareScreenConfirmDialog Compose components.
        // Android target stays untouched — :shared:screencast has no Android sourceset
        // by design (CLAUDE.md: Android screen capture is non-shipping scaffolding).
        iosMain.dependencies {
            implementation(projects.shared.screencast)
        }
    }
}

kotlin {
    android {
        namespace = "dev.puklic.shared.composeui"
    }
}
