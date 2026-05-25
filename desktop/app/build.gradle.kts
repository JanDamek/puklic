import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("puklic.jvm-library")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

dependencies {
    implementation(projects.shared.composeUi)
    implementation(projects.shared.session)
    implementation(projects.shared.voice)
    implementation(projects.shared.platformApi)
    implementation(projects.shared.domain)
    implementation(projects.shared.ids)
    implementation(projects.shared.repositories)
    implementation(projects.shared.persistenceApi)
    implementation(projects.shared.persistenceSqldelight)
    implementation(projects.shared.protocolDiscord)
    implementation(projects.desktop.platformLinux)
    implementation(projects.desktop.platformMacos)
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
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coil)
    implementation(libs.coil.core)
    implementation(libs.coil.network.ktor3)
    implementation(libs.kermit)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.logback.classic)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

compose.desktop {
    application {
        mainClass = "dev.puklic.desktop.MainKt"
        nativeDistributions {
            // Linux is the primary target (per CLAUDE.md). macOS and Windows are
            // produced from their respective host runners. jpackage cross-compile is
            // not supported; each runner emits formats native to its host. Compose
            // Desktop rejects formats that don't match the current OS, so we filter
            // by os.name here.
            // Scope per issue #22 (CLAUDE.md §Platforms):
            //   - Linux  = canonical shipping target (.deb + .AppImage)
            //   - macOS  = developer-side only (.dmg for local validation)
            //   - Windows = out of scope (no MSI target)
            val osName = System.getProperty("os.name").lowercase()
            val formats = when {
                osName.contains("linux") -> arrayOf(TargetFormat.Deb, TargetFormat.AppImage)
                osName.contains("mac") -> arrayOf(TargetFormat.Dmg)
                else -> arrayOf<TargetFormat>()
            }
            targetFormats(*formats)
            packageName = "Puklic"
            packageVersion = "0.1.0"
            description = "Lightweight native Discord client (Compose Multiplatform)"
            copyright = "© 2026 Jan Damek. Apache-2.0."
            vendor = "Jan Damek"

            // Runtime modules required by FFmpeg/JavaCPP, ktor, kotlinx, dbus-java.
            // jlink-style modules list; jpackage uses this to assemble the bundled JRE.
            modules(
                "java.naming",
                "java.management",
                "java.sql",
                "java.net.http",
                "jdk.unsupported",
                "jdk.crypto.ec",
            )

            linux {
                iconFile.set(rootProject.file("icons/linux/512x512/puklic.png"))
                appCategory = "Network"
                debMaintainer = "damek@mazlusek.com"
                menuGroup = "Internet"
                appRelease = "1"
                rpmLicenseType = "Apache-2.0"
                // Note: Compose Desktop 1.9.x (jpackage under the hood) does NOT
                // expose a DSL property for Debian `Depends:` and jpackage itself
                // has no `--linux-package-deps` flag. The runtime dependency on
                // `libsecret-tools` (needed by LinuxSecureStorage to invoke the
                // `secret-tool` CLI) is injected post-build by the
                // `patchDebDependencies` task below — see issue #15.
            }
            macOS {
                iconFile.set(rootProject.file("icons/macos/puklic.icns"))
                bundleID = "cz.damek.puklic"
                appStore = false
                dockName = "Puklic"
                // macOS / .dmg requires MAJOR > 0 (CFBundleShortVersionString rules).
                // Use 1.0.0 on macOS until the project bumps to 1.x globally.
                packageVersion = "1.0.0"
                dmgPackageVersion = "1.0.0"
                packageBuildVersion = "1.0.0"
                dmgPackageBuildVersion = "1.0.0"
            }
            // No windows {} block — Windows is out of scope (issue #22).
        }
    }
}

// Forward selected -D system properties from the Gradle JVM to the application JVM so
// `./gradlew :desktop:app:run -Dpuklic.dev.autotest=true` actually reaches Main. Compose
// Desktop's `run` task does not propagate -D values by default.
tasks.withType<JavaExec>().configureEach {
    listOf("puklic.dev.autotest").forEach { key ->
        val value = System.getProperty(key)
        if (value != null) systemProperty(key, value)
    }
}

// ---------------------------------------------------------------------------
// patchDebDependencies — inject Debian `Depends:` line into the .deb produced
// by Compose Desktop / jpackage. Required for issue #15: LinuxSecureStorage
// shells out to `secret-tool` (from libsecret-tools) to persist Discord tokens
// via the Secret Service API (GNOME Keyring / KWallet). Without it the first
// login throws PlatformUnavailable.
//
// jpackage has no `--linux-package-deps` flag and Compose 1.9.x exposes no
// DSL property for it, so we repack the .deb's control file. Runs only on
// Linux build agents (skipped on macOS dev builds, which only produce .dmg).
//
// AppImage users install libsecret-tools manually (AppImage does not honor
// .deb deps). AUR side already declares libsecret transitively via #20.
// ---------------------------------------------------------------------------
val debRuntimeDependencies = listOf(
    "libsecret-tools",
)

val patchDebDependencies = tasks.register("patchDebDependencies") {
    description = "Injects runtime Depends into the .deb produced by packageDeb (issue #15)."
    group = "compose desktop"

    val debDir = layout.buildDirectory.dir("compose/binaries/main/deb")
    val depsLine = debRuntimeDependencies.joinToString(", ")

    onlyIf {
        val os = System.getProperty("os.name").lowercase()
        os.contains("linux") && debDir.get().asFile.exists()
    }

    doLast {
        val dir = debDir.get().asFile
        val debs = dir.listFiles { f -> f.isFile && f.name.endsWith(".deb") }
            ?: emptyArray()
        if (debs.isEmpty()) {
            logger.warn("patchDebDependencies: no .deb files found in $dir; skipping")
            return@doLast
        }
        for (deb in debs) {
            logger.lifecycle("patchDebDependencies: injecting Depends '$depsLine' into ${deb.name}")
            val work = layout.buildDirectory.dir("tmp/patchDeb/${deb.nameWithoutExtension}").get().asFile
            work.deleteRecursively()
            work.mkdirs()
            // Extract control archive into a sibling dir, mutate, repack.
            exec {
                workingDir = work
                commandLine("dpkg-deb", "-R", deb.absolutePath, work.absolutePath)
            }.assertNormalExitValue()

            val control = File(work, "DEBIAN/control")
            check(control.exists()) { "DEBIAN/control missing in extracted ${deb.name}" }
            val original = control.readText()
            val patched = buildString {
                var dependsWritten = false
                for (line in original.lineSequence()) {
                    if (line.startsWith("Depends:", ignoreCase = true)) {
                        val existing = line.substringAfter(":").trim()
                        val merged = if (existing.isEmpty()) depsLine else "$existing, $depsLine"
                        appendLine("Depends: $merged")
                        dependsWritten = true
                    } else if (line.isNotEmpty() || dependsWritten) {
                        appendLine(line)
                    }
                }
                if (!dependsWritten) {
                    // No existing Depends line — append before trailing newline.
                    if (!endsWith("\n")) append("\n")
                    append("Depends: $depsLine\n")
                }
            }
            control.writeText(patched)

            // Repack in place (dpkg-deb -b <dir> <file>).
            exec {
                commandLine("dpkg-deb", "-b", work.absolutePath, deb.absolutePath)
            }.assertNormalExitValue()
        }
    }
}

tasks.matching { it.name == "packageDeb" }.configureEach {
    finalizedBy(patchDebDependencies)
}
