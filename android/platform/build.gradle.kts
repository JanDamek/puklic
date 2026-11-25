plugins {
    id("puklic.android-library")
}

android {
    namespace = "dev.puklic.android.platform"
}

dependencies {
    implementation(projects.shared.platformApi)
    implementation(projects.shared.voiceApi)
    implementation(projects.shared.ids)
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kermit)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}
