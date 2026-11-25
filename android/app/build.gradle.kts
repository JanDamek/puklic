plugins {
    id("puklic.android-app")
}

android {
    namespace = "dev.puklic.android"
}

dependencies {
    implementation(projects.android.platform)
    implementation(projects.shared.composeUi)
    implementation(projects.shared.session)
    implementation(projects.shared.repositories)
    implementation(projects.shared.protocolDiscord)
    implementation(projects.shared.persistenceApi)
    implementation(projects.shared.persistenceSqldelight)
    implementation(projects.shared.platformApi)
    implementation(projects.shared.voiceApi)
    implementation(projects.shared.ids)
    implementation(libs.androidx.activity.compose)
    implementation(libs.decompose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.coil.core)
    implementation(libs.coil.network.ktor3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kermit)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.sqldelight.runtime)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}
