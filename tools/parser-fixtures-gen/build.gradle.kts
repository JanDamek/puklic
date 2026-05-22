plugins {
    id("puklic.jvm-library")
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dev.puklic.tools.fixtures.MainKt")
}

dependencies {
    implementation(projects.shared.chatParser)
    implementation(projects.shared.domain)
    implementation(projects.shared.ids)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
}
