plugins {
    id("puklic.jvm-library")
}

dependencies {
    implementation(projects.shared.chatParser)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
}
