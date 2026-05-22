plugins {
    id("puklic.jvm-library")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

// See :shared:compose-ui — same Google Maven repo workaround for Compose 1.8.0 transitives.
repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(projects.shared.composeUi)
    implementation(projects.shared.session)
    implementation(projects.shared.platformApi)
    implementation(projects.desktop.platformLinux)
    implementation(projects.desktop.platformMacos)
    implementation(projects.desktop.platformWindows)
    implementation(libs.koin.core)
    implementation(libs.decompose)
    implementation(libs.essenty.lifecycle.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(compose.desktop.currentOs)
    implementation(libs.kermit)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "dev.puklic.desktop.MainKt"
        nativeDistributions {
            packageName = "Puklic"
            packageVersion = "0.1.0"
        }
    }
}
