plugins {
    `kotlin-dsl`
}

// Read versions from the root version catalog TOML directly
// (version catalog type-safe accessors require the catalog to be pre-compiled, which isn't the case here)
fun versionOf(key: String): String {
    val toml = file("../gradle/libs.versions.toml").readText()
    val regex = Regex("""^\s*$key\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
    return regex.find(toml)?.groupValues?.get(1)
        ?: error("Version key '$key' not found in libs.versions.toml")
}

val kotlinVersion = versionOf("kotlin")
val agpVersion = versionOf("agp")
val composeMpVersion = versionOf("compose-multiplatform")
val sqldelightVersion = versionOf("sqldelight")
val detektVersion = versionOf("detekt")
val koverVersion = versionOf("kover")
val ktlintGradleVersion = versionOf("jlleitschuh-ktlint-gradle")

dependencies {
    // Make version catalog type-safe accessors (libs.*) available to precompiled script plugins.
    // libs.javaClass.protectionDomain.codeSource.location → the generated LibrariesForLibs class directory.
    // Standard workaround for Gradle 8.x included builds with version catalogs.
    implementation(files(libs.javaClass.protectionDomain.codeSource.location))

    // Kotlin Gradle plugins
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    // kotlin-serialization plugin (org.jetbrains.kotlin.plugin.serialization)
    implementation("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")

    // Compose compiler plugin is a SEPARATE artifact from kotlin-gradle-plugin
    // (feedback workaround: compose compiler plugin NOT in kotlin-gradle-plugin)
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")

    // Compose Multiplatform plugin
    implementation("org.jetbrains.compose:compose-gradle-plugin:$composeMpVersion")

    // Android Gradle Plugin — must be on build-logic classpath alongside KMP plugins
    // to avoid ClassLoader isolation issues in Gradle 8.12
    implementation("com.android.tools.build:gradle:$agpVersion")

    // SQLDelight
    implementation("app.cash.sqldelight:gradle-plugin:$sqldelightVersion")

    // Static analysis — puklic.detekt is standalone opt-in, NOT auto-applied in kmp-library
    // (Detekt 1.23.8 can't load BaseExtension from AGP 8.7.2 via ClassLoader isolation)
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:$detektVersion")

    // ktlint
    implementation("org.jlleitschuh.gradle:ktlint-gradle:$ktlintGradleVersion")

    // Coverage
    implementation("org.jetbrains.kotlinx:kover-gradle-plugin:$koverVersion")

    // Tests for build-logic pure helpers (e.g. IosGplChecker matcher)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
