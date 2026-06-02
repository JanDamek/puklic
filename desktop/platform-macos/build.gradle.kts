plugins {
    id("puklic.jvm-library")
}

dependencies {
    implementation(projects.shared.platformApi)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kermit)
    // JNA: SecureStorage uses Security.framework SecItem* APIs to write iCloud-
    // synchronizable Keychain items (Issue #74). The `/usr/bin/security` CLI
    // has no `kSecAttrSynchronizable` flag, so JNA is mandatory — there is no
    // CLI fallback that preserves sync.
    implementation(libs.jna)
    implementation(libs.jna.platform)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
}
