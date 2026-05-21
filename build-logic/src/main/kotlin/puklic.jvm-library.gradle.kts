// puklic.jvm-library — JVM-only library convention plugin
// Applied to: :desktop:platform-*, :tools:*, :desktop:app

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
