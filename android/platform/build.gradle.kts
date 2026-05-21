plugins {
    id("puklic.android-library")
}

android {
    namespace = "dev.puklic.android.platform"
}

dependencies {
    implementation(projects.shared.platformApi)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}
