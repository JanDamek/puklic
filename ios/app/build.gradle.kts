plugins {
    id("puklic.ios-library")
}

kotlin {
    sourceSets {
        // For iOS-only modules, commonMain == iosMain (all targets are iOS).
        // Using commonMain avoids the lazy-initialization issue with the hierarchy template's
        // intermediate source set 'iosMain' in Kotlin 2.x.
        commonMain.dependencies {
            implementation(projects.ios.platform)
            implementation(projects.shared.composeUi)
            implementation(projects.shared.session)
            implementation(libs.koin.core)
        }
    }
}
