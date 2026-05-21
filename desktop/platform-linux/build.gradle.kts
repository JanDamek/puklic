plugins {
    id("puklic.jvm-library")
}

dependencies {
    implementation(projects.shared.platformApi)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.dbus.java.core)
    implementation(libs.kermit)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}
