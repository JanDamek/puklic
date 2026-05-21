plugins {
    id("puklic.compose-library")
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
