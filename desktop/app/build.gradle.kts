import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.nio.file.Files
import java.nio.file.Paths

plugins {
    id("puklic.jvm-library")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

// Single source of truth for the packaging version. Defined in root
// `gradle.properties` as `puklic.version`. Drives jpackage `packageVersion`
// on ALL platforms (Linux .deb / .AppImage AND macOS .dmg — no per-OS
// override) and the generated `dev.puklic.desktop.update.Version.CURRENT`
// constant consumed by `UpdateChecker`.
val puklicVersion: String = (project.findProperty("puklic.version") as? String)
    ?: error(
        "puklic.version missing in gradle.properties — it is the single source of " +
            "truth for the packaging version. Set it at the repo root and re-run.",
    )

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
            // Scope per docs/07_roadmap/phases.md §Platforms (updated 2026-05-29
            // FP-10, issue #50):
            //   - Linux   = officially shipped (.deb + .AppImage)
            //   - macOS   = officially shipped (.dmg)
            //   - Windows = officially shipped (.exe + .msi)
            val osName = System.getProperty("os.name").lowercase()
            val formats = when {
                osName.contains("linux") -> arrayOf(TargetFormat.Deb, TargetFormat.AppImage)
                osName.contains("mac") -> arrayOf(TargetFormat.Dmg)
                osName.contains("windows") -> arrayOf(TargetFormat.Exe, TargetFormat.Msi)
                else -> arrayOf<TargetFormat>()
            }
            targetFormats(*formats)
            packageName = "Puklic"
            packageVersion = puklicVersion
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
                // `patchDebPostBuild` task below — see issues #15 and #20.
            }
            macOS {
                iconFile.set(rootProject.file("icons/macos/puklic.icns"))
                bundleID = "cz.damek.puklic"
                appStore = false
                dockName = "Puklic"
                // No packageVersion override: the unified `puklic.version` from
                // gradle.properties applies on macOS too. CFBundleShortVersionString
                // (which jpackage derives from packageVersion for the .dmg/.app bundle)
                // accepts 0.x.y — the historical "MAJOR > 0" rule is App Store
                // specific (`appStore = false` here) and does not apply to
                // standalone jpackage .dmg distribution.
            }
            windows {
                // upgradeUuid is the MSI UpgradeCode — Windows uses this stable GUID
                // to detect "this is an upgrade of an existing install of the same
                // product" rather than a side-by-side parallel install. It MUST stay
                // constant across every Puklic version released; bumping it would
                // make every release install side-by-side instead of upgrading.
                // Generated 2026-05-29 specifically for Puklic.
                upgradeUuid = "b3c4a1d0-e7f5-4d8a-9c3e-6f2a1b8d5e4f"
                menuGroup = "Puklic"
                // perUserInstall = true sidesteps UAC elevation for the install —
                // the .msi writes under %LOCALAPPDATA% rather than Program Files.
                perUserInstall = true
                shortcut = true
                dirChooser = true
                console = false
                // No iconFile: jpackage requires .ico (not .png); we deliberately
                // do not commit a binary .ico to the repo. jpackage falls back to
                // its bundled default icon, which is acceptable for v1. A real
                // branded .ico can be added later by checking in
                // `icons/windows/puklic.ico` and setting iconFile here — no other
                // packaging change needed.
            }
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
// patchDebPostBuild — single conceptual "make this .deb FHS-correct" step
// applied as a post-process to the .deb produced by Compose Desktop / jpackage.
//
// Two concerns are merged into one dpkg-deb roundtrip (extract once, repack
// once) so we never leave the .deb in a half-patched intermediate state:
//
//   1. Inject Debian `Depends:` line (issue #15). LinuxSecureStorage shells
//      out to `secret-tool` (from libsecret-tools) to persist Discord tokens
//      via the Secret Service API. Without it the first login throws
//      PlatformUnavailable. jpackage has no `--linux-package-deps` flag and
//      Compose 1.9.x exposes no DSL property for it.
//
//   2. FHS integration (issue #20). jpackage ships everything under
//      /opt/puklic/ and does NOT expose the binary on PATH, the .desktop
//      entry in /usr/share/applications, or the icon in /usr/share/pixmaps.
//      Users had no `puklic` command and the app didn't appear in menus.
//      We add:
//        - /usr/bin/puklic               -> symlink to /opt/puklic/bin/Puklic
//        - /usr/share/applications/puklic.desktop  (Exec/Icon rewritten to
//                                                   absolute / themed paths)
//        - /usr/share/pixmaps/puklic.png            (icon copy)
//
// Runs only on Linux build agents (skipped on macOS dev builds — they emit
// only .dmg). AppImage users install libsecret-tools manually (AppImage
// does not honor .deb deps) and rely on the AppImage's own desktop entry.
// AUR PKGBUILD performs the same FHS bridge defensively (idempotent — the
// symlinks/files are already correct in v0.1.1+ .debs).
// ---------------------------------------------------------------------------
val debRuntimeDependencies = listOf(
    "libsecret-tools",
)

val patchDebPostBuild = tasks.register("patchDebPostBuild") {
    description = "Post-processes the .deb produced by packageDeb: injects Depends (#15) and FHS symlinks (#20)."
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
            logger.warn("patchDebPostBuild: no .deb files found in $dir; skipping")
            return@doLast
        }
        for (deb in debs) {
            logger.lifecycle("patchDebPostBuild: post-processing ${deb.name}")
            val work = layout.buildDirectory.dir("tmp/patchDeb/${deb.nameWithoutExtension}").get().asFile
            work.deleteRecursively()
            work.mkdirs()
            // Extract full .deb (control + data) into work dir.
            exec {
                workingDir = work
                commandLine("dpkg-deb", "-R", deb.absolutePath, work.absolutePath)
            }.assertNormalExitValue()

            // --- (1) Depends injection ------------------------------------
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
                    if (!endsWith("\n")) append("\n")
                    append("Depends: $depsLine\n")
                }
            }
            control.writeText(patched)
            logger.lifecycle("patchDebPostBuild:   injected Depends '$depsLine'")

            // --- (2) FHS integration --------------------------------------
            // Compose Desktop layout under work/:
            //   opt/puklic/bin/Puklic
            //   opt/puklic/lib/puklic-Puklic.desktop
            //   opt/puklic/lib/Puklic.png
            val optBin = File(work, "opt/puklic/bin/Puklic")
            val optDesktop = File(work, "opt/puklic/lib/puklic-Puklic.desktop")
            val optIcon = File(work, "opt/puklic/lib/Puklic.png")

            // /usr/bin/puklic symlink (lowercase per Arch / common-shell convention).
            val usrBin = File(work, "usr/bin").apply { mkdirs() }
            val launcherLink = File(usrBin, "puklic")
            if (launcherLink.exists() || Files.isSymbolicLink(launcherLink.toPath())) {
                Files.delete(launcherLink.toPath())
            }
            check(optBin.exists()) { "Expected jpackage launcher at ${optBin}, not found" }
            Files.createSymbolicLink(
                launcherLink.toPath(),
                Paths.get("/opt/puklic/bin/Puklic"),
            )
            logger.lifecycle("patchDebPostBuild:   added /usr/bin/puklic -> /opt/puklic/bin/Puklic")

            // /usr/share/applications/puklic.desktop (rewrite Exec + Icon).
            if (optDesktop.exists()) {
                val appsDir = File(work, "usr/share/applications").apply { mkdirs() }
                val destDesktop = File(appsDir, "puklic.desktop")
                val desktopText = optDesktop.readText()
                    .lineSequence()
                    .map { line ->
                        when {
                            line.startsWith("Exec=") -> "Exec=/opt/puklic/bin/Puklic"
                            line.startsWith("Icon=") -> "Icon=puklic"
                            else -> line
                        }
                    }
                    .joinToString("\n")
                destDesktop.writeText(if (desktopText.endsWith("\n")) desktopText else "$desktopText\n")
                logger.lifecycle("patchDebPostBuild:   added /usr/share/applications/puklic.desktop")
            } else {
                logger.warn("patchDebPostBuild:   ${optDesktop} not present — skipping .desktop bridge")
            }

            // /usr/share/pixmaps/puklic.png.
            if (optIcon.exists()) {
                val pixmaps = File(work, "usr/share/pixmaps").apply { mkdirs() }
                optIcon.copyTo(File(pixmaps, "puklic.png"), overwrite = true)
                logger.lifecycle("patchDebPostBuild:   added /usr/share/pixmaps/puklic.png")
            } else {
                logger.warn("patchDebPostBuild:   ${optIcon} not present — skipping pixmap bridge")
            }

            // Repack in place (dpkg-deb -b <dir> <file>).
            exec {
                commandLine("dpkg-deb", "-b", work.absolutePath, deb.absolutePath)
            }.assertNormalExitValue()
        }
    }
}

tasks.matching { it.name == "packageDeb" }.configureEach {
    finalizedBy(patchDebPostBuild)
}

// ---------------------------------------------------------------------------
// generateVersionKt — emit `dev.puklic.desktop.update.Version.CURRENT` from
// the single-source-of-truth `puklic.version` Gradle property. This replaces
// the previously hand-edited `Version.kt`, eliminating drift between the
// auto-update check string and the packaging version.
// ---------------------------------------------------------------------------
val generatedVersionDir = layout.buildDirectory.dir("generated/source/version/main/kotlin")

val generateVersionKt = tasks.register("generateVersionKt") {
    description = "Generates dev/puklic/desktop/update/Version.kt from puklic.version property."
    group = "build"

    inputs.property("puklicVersion", puklicVersion)
    outputs.dir(generatedVersionDir)

    doLast {
        val outDir = generatedVersionDir.get().asFile.resolve("dev/puklic/desktop/update")
        outDir.mkdirs()
        val file = outDir.resolve("Version.kt")
        file.writeText(
            """
            package dev.puklic.desktop.update

            /**
             * Puklic desktop build version. Generated from `puklic.version` in the root
             * `gradle.properties` by the `:desktop:app:generateVersionKt` task.
             *
             * DO NOT hand-edit this file — changes here are overwritten on every build.
             * To bump the version, edit `gradle.properties` (single source of truth).
             */
            public object Version {
                public const val CURRENT: String = "$puklicVersion"
            }

            """.trimIndent(),
        )
    }
}

kotlin {
    sourceSets["main"].kotlin.srcDir(generateVersionKt.map { generatedVersionDir })
}
