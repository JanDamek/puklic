plugins {
    id("puklic.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.domain)
            implementation(projects.shared.ids)
            implementation(projects.shared.protocolDiscord)
            implementation(projects.shared.persistenceApi)
            implementation(projects.shared.chatParser)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
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
    namespace = "dev.puklic.shared.repositories"
}
