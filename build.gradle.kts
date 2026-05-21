// Root build.gradle.kts — aggregation only. No production code here.
// Convention plugins live in build-logic/. Module-specific config in each module's build.gradle.kts.

plugins {
    // Apply all KMP/AGP plugins with apply(false) to ensure a single ClassLoader is used
    // across all modules (prevents plugin load conflicts in Gradle 8.12)
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.gradle.versions) apply false
}

// TODO(step-3): Install pre-commit hook that runs ktlintCheck + detekt
// Tasks for those don't exist on the scaffold (no source, puklic.detekt not yet applied).
// Wire this when first module source lands:
//
// tasks.register("installGitHooks") {
//     doLast {
//         val hook = rootProject.file(".git/hooks/pre-commit")
//         hook.writeText("#!/bin/sh\n./gradlew ktlintCheck detekt\n")
//         hook.setExecutable(true)
//     }
// }
// gradle.projectsEvaluated { tasks.named("help") { dependsOn("installGitHooks") } }
