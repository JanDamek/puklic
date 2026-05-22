plugins {
    id("puklic.jvm-library")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

dependencies {
    implementation(projects.shared.composeUi)
    implementation(projects.shared.session)
    implementation(projects.shared.platformApi)
    implementation(projects.shared.domain)
    implementation(projects.shared.ids)
    implementation(projects.shared.repositories)
    implementation(projects.shared.persistenceApi)
    implementation(projects.shared.persistenceSqldelight)
    implementation(projects.shared.protocolDiscord)
    implementation(projects.desktop.platformLinux)
    implementation(projects.desktop.platformMacos)
    implementation(projects.desktop.platformWindows)
    implementation(libs.koin.core)
    implementation(libs.decompose)
    implementation(libs.essenty.lifecycle.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(compose.desktop.currentOs)
    implementation(libs.kermit)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "dev.puklic.desktop.MainKt"
        nativeDistributions {
            packageName = "Puklic"
            packageVersion = "0.1.0"
        }
    }
}
