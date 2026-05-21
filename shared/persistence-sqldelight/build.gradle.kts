plugins {
    id("puklic.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.persistenceApi)
            implementation(projects.shared.platformApi)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.jdbc.driver)
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
        }
    }
}

android {
    namespace = "dev.puklic.shared.persistencesqldelight"
}
