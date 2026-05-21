plugins {
    id("puklic.jvm-library")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

dependencies {
    implementation(projects.shared.composeUi)
    implementation(projects.shared.session)
    implementation(projects.desktop.platformLinux)
    implementation(projects.desktop.platformMacos)
    implementation(projects.desktop.platformWindows)
    implementation(libs.koin.core)
    implementation(compose.desktop.currentOs)
    implementation(libs.kermit)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}
