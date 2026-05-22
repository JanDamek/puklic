plugins {
    id("puklic.ios-library")
}

kotlin {
    sourceSets {
        // For iOS-only modules, commonMain == iosMain (all targets are iOS).
        // Using commonMain avoids the lazy-initialization issue with the hierarchy template's
        // intermediate source set 'iosMain' in Kotlin 2.x.
        commonMain.dependencies {
            // Phase 1: :ios:app is a compile-only stub. :shared:compose-ui + :shared:session are
            // intentionally omitted because the iOS target of :shared:compose-ui currently has an
            // unresolved transitive androidx.lifecycle:2.8.5 conflict on the JetBrains Compose 1.8.0
            // line. Restoration is tracked under Phase 1 step 19.
            implementation(projects.ios.platform)
            implementation(libs.koin.core)
        }
    }
}
