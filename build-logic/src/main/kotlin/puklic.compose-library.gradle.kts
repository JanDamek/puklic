// puklic.compose-library — extends puklic.kmp-library, adds Compose Multiplatform
// Applied to: :shared:compose-ui only
//
// Library dependencies (Decompose, Coil, Koin, etc.) are declared in the module's
// own build.gradle.kts. Convention plugins configure toolchain only.

plugins {
    id("puklic.kmp-library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
