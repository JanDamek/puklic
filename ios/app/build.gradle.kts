plugins {
    id("puklic.ios-library")
}

kotlin {
    sourceSets {
        // For iOS-only modules, commonMain == iosMain (all targets are iOS).
        // Using commonMain avoids the lazy-initialization issue with the hierarchy template's
        // intermediate source set 'iosMain' in Kotlin 2.x.
        commonMain.dependencies {
            implementation(projects.ios.platform)
            implementation(projects.shared.composeUi)
            implementation(projects.shared.session)
            implementation(libs.koin.core)
        }
    }
}

// ── verifyIosNoGplDeps ────────────────────────────────────────────────────
// Fails the build if the resolved dependency graph of :ios:app pulls any
// GPL-3.0 transitive dependency. The iOS App Store build is chat-only and
// MUST stay Apache-2.0 / MIT only. See:
//   docs/03_infrastructure/dep-policy.md
//   docs/03_infrastructure/architect-reports/2026-05-28-apple-distribution.md §8 row 1
//
// Matcher logic lives in build-logic/src/main/kotlin/IosGplChecker.kt
// (kept pure + unit-tested in build-logic/src/test/kotlin/IosGplCheckerTest.kt).
val verifyIosNoGplDeps by tasks.registering {
    group = "verification"
    description = "Fail if :ios:app pulls any GPL-3.0 transitive dependency."

    val reportRef =
        "docs/03_infrastructure/architect-reports/2026-05-28-apple-distribution.md"

    // Materialise the list of forbidden coordinates at config time into a
    // Provider — config-cache safe (no Project / Configuration references
    // captured in the task action).
    val violationsProvider: Provider<List<String>> = project.provider {
        configurations
            .filter { cfg -> cfg.isCanBeResolved }
            .flatMap { cfg ->
                runCatching {
                    cfg.resolvedConfiguration.lenientConfiguration.allModuleDependencies
                }.getOrElse { emptySet() }
            }
            .filter { dep -> isForbiddenGplArtifact(dep.moduleGroup, dep.moduleName) }
            .map { "${it.moduleGroup}:${it.moduleName}:${it.moduleVersion}" }
            .toSortedSet()
            .toList()
    }

    doLast {
        val violations = violationsProvider.get()
        require(violations.isEmpty()) {
            buildString {
                appendLine("Forbidden GPL-3.0 dependency in :ios:app graph:")
                violations.forEach { appendLine("  - $it") }
                appendLine("The iOS App Store build must stay Apache-2.0 / MIT only.")
                appendLine("See $reportRef")
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyIosNoGplDeps)
}
