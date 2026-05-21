plugins {
    id("puklic.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.domain)
            implementation(projects.shared.ids)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kermit)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        // iosMain only exists on Apple hosts; guarded to support Linux CI.
        sourceSets.findByName("iosMain")?.apply {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
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
    namespace = "dev.puklic.shared.protocoldiscord"
}
