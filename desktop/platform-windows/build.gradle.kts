plugins {
    id("puklic.jvm-library")
}

dependencies {
    implementation(projects.shared.platformApi)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
}
