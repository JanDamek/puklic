plugins {
    id("puklic.jvm-library")
}

dependencies {
    implementation(projects.shared.platformApi)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kermit)
    // JNA — Advapi32 (Credential Manager) + Shell32 (ShellExecuteW) bindings. The
    // jna-platform Advapi32 interface does not expose the CredRead/Write/Delete/
    // Enumerate family, so WindowsSecureStorage declares its own thin JNA Library
    // surface; Shell32 is reused via the jna-platform binding.
    implementation(libs.jna)
    implementation(libs.jna.platform)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}
