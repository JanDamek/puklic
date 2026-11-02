plugins {
    id("puklic.android-app")
}

android {
    namespace = "dev.puklic.android"
}

dependencies {
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(projects.shared.composeUi)
    implementation(projects.shared.session)
    implementation(projects.android.platform)
    implementation(libs.koin.android)
    implementation(libs.koin.core)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}
