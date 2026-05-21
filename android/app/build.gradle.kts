plugins {
    id("puklic.android-app")
}

android {
    namespace = "dev.puklic.android"
}

dependencies {
    implementation(projects.shared.composeUi)
    implementation(projects.shared.session)
    implementation(projects.android.platform)
    implementation(libs.koin.android)
    implementation(libs.koin.core)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}
