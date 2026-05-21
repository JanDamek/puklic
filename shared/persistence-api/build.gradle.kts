plugins {
    id("puklic.kmp-library")
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.domain)
            implementation(projects.shared.ids)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
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

sqldelight {
    databases {
        create("PuklicDatabase") {
            packageName.set("dev.puklic.db")
            srcDirs.setFrom("src/commonMain/sqldelight")
        }
    }
}

android {
    namespace = "dev.puklic.shared.persistenceapi"
}
