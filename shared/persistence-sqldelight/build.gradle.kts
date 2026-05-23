plugins {
    id("puklic.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.persistenceApi)
            implementation(projects.shared.platformApi)
            implementation(projects.shared.domain)
            implementation(projects.shared.ids)
            implementation(projects.shared.chatParser)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
            implementation(libs.kermit)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.jdbc.driver)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.sqlite.jdbc)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        // iosMain is only created when Kotlin/Native iOS targets are enabled (Apple host).
        // On Linux CI, iOS targets are disabled — iosMain source set is not created.
        sourceSets.findByName("iosMain")?.apply {
            dependencies {
                implementation(libs.sqldelight.native.driver)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
            implementation(libs.sqldelight.jdbc.driver)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.sqlite.jdbc)
        }
    }
}

android {
    namespace = "dev.puklic.shared.persistencesqldelight"
}
