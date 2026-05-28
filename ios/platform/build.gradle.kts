plugins {
    id("puklic.ios-library")
}

kotlin {
    sourceSets {
        // For iOS-only modules, commonMain == iosMain (all targets are iOS).
        commonMain.dependencies {
            implementation(projects.shared.platformApi)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
