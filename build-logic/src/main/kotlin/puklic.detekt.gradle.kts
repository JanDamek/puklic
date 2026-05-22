// puklic.detekt — standalone opt-in plugin for static analysis + formatting
// NOT auto-applied inside puklic.kmp-library due to Detekt 1.23.8 / AGP 8.7.2 ClassLoader isolation issue.
// Apply explicitly to modules once source code exists (Step 3+).
// TODO(step-3): wire puklic.detekt into puklic.kmp-library once source exists and detekt can load AGP types

plugins {
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

detekt {
    config.setFrom(files("${rootDir}/detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
}

ktlint {
    val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
    version.set(catalog.findVersion("ktlint").get().requiredVersion)
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}

dependencies {
    val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
    detektPlugins(catalog.findLibrary("detekt-compose-rules").get())
    add("ktlintRuleset", catalog.findLibrary("ktlint-compose-rules").get())
}
